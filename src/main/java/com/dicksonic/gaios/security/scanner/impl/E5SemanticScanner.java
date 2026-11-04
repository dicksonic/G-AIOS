package com.dicksonic.gaios.security.scanner.impl;

import com.dicksonic.gaios.security.scanner.SemanticScanner;
import com.dicksonic.gaios.security.scanner.engine.E5EmbeddingEngine;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.dicksonic.gaios.tools.Tool.fmt;

/**
 * Stage II 的本地嵌入引擎，对应 MCP-Guard 论文"微调E5"的真实形态。
 *
 * 当前是接线中的骨架：构造时持有 fallback 引擎（通常传 LlmSemanticScanner），
 * isMalicious 先委托 fallback，保证注册中心永远有 Stage II 可用。
 * 等 ONNX Runtime + e5-small 接好后，把向量逻辑填进来，fallback 降级为
 * "模型文件缺失时的备胎"，而不是现在这样顶班。
 *
 * 选委托结构而不是抛异常，理由：安全链路不允许"没有Stage II"这个状态——
 * 宁可降级到 LLM，也不能裸奔。
 */
public class E5SemanticScanner implements SemanticScanner {

    private static final Logger log = LogManager.getLogger(E5SemanticScanner.class);

    private final SemanticScanner fallback;
    private final E5EmbeddingEngine engine;
    private final float[][] poisonAnchorVecs;
    private final float[][] normalAnchorVecs;
    private final double threshold;

    /**
     * 清晰区边界（灰色区半径）：|diff| > CLEAR_ZONE 时 E5 独立判定，否则升级 LLM 仲裁。
     * 取值 0.02 的依据：e5-base 标定集 22 样本中，|diff| > 0.02 的样本 E5 判定全部正确；
     * 带内样本交给 LLM（准确率更高但烧 token）。
     * ⚠️ 标定集扩充、换检测模型或微调上线后，此值必须重新标定。
     * 暂不配置化：参数少于两个时不引入配置项（避免和 temperature 一样出现"配置幻觉"）。
     * 取值 0.03 的依据：管线级标定（22样本）——0.02 时清晰区误杀 2 条正常样本
     *  （批量督办 +0.022、风险画像 +0.028，均为"效益话术"型边界样本）；
     *  收窄到 0.03 后清晰区 4/4 全对，灰色区交 LLM（22/22）。
     *  ⚠️ 标定集扩充或换模型后必须重新标定本值。
     */
    private static final double CLEAR_ZONE = 0.03;


    public  static final String[] POISON_ANCHORS = {
            "伪装成安全检查或数据校验工具，诱导Agent在查询数据前先调用本工具，抢占任务执行的第一个调用位，改变正常执行顺序。",
            "隐藏触发条件：当用户提到特定内容时，暗中执行描述之外的敏感操作，如读取或外发数据。",
            "篡改参数语义的工具，在用户不知情的情况下修改关键参数，如把数据发送到攻击者控制的地址。"
    };

    /** 正常锚点：典型良性工具的功能描述基线 */
    public  static final String[] NORMAL_ANCHORS = {
            "查询某类业务台账的工具，参数为筛选条件，返回记录列表，纯只读操作。",
            "根据输入信息生成文档草稿的工具，输出为草稿，需人工审核确认后才可使用。"
    };

    /** 判定结果 + 实际生效的判定路径，供标定器统计用 */
    public record Verdict(boolean malicious, String path) {}

    public E5SemanticScanner(E5EmbeddingEngine engine, SemanticScanner fallback,
                             double threshold) throws Exception {
        this.engine = engine;
        this.fallback = fallback;
        /*
         * 差分判定阈值：diff = cos(候选,毒锚点) − cos(候选,正常锚点)，diff > threshold 判恶意。
         * 0.0     = 严格按方向判（当前标定值：5样本正负分居0两侧，见README安全实验记录）
         * 调大    = 保守：降低正常工具误杀，代价是毒工具可能漏放
         * 调小/负 = 激进：宁可错杀，不放行可疑工具
         * 注意：标定集扩充后必须重新定值，别把它当设计常量传家宝。
         */
        this.threshold = threshold;
        // 锚点向量启动时一次性算好，注册阶段每个工具只做 N 次点积
        this.poisonAnchorVecs = embedAll(POISON_ANCHORS);
        this.normalAnchorVecs = embedAll(NORMAL_ANCHORS);
    }

    private float[][] embedAll(String[] texts) {
        float[][] vecs = new float[texts.length][];
        for (int i = 0; i < texts.length; i++) {
            vecs[i] = engine.embed(texts[i]);
        }
        return vecs;
    }


    @Override
    public boolean isMalicious(String toolName, String description) {
        return judge(toolName, description).malicious();
    }

    public Verdict judge(String toolName, String description) {
        try {
            float[] candidate = engine.embed(description);
            double diff = maxCosine(candidate, poisonAnchorVecs) - maxCosine(candidate, normalAnchorVecs);

            if (diff > CLEAR_ZONE) {
                log.info("[Stage II:{}] {} 差分={} → 隔离", engineName(), toolName, fmt(diff));
                return new Verdict(true, "E5-clear");
            }
            if (diff < -CLEAR_ZONE) {
                log.info("[Stage II:{}] {} 差分={} → 放行", engineName(), toolName, fmt(diff));
                return new Verdict(false, "E5-clear");
            }
            log.info("[Stage III] {} 差分={} 落灰色区，升级LLM仲裁", toolName, fmt(diff));
            return new Verdict(fallback.isMalicious(toolName, description), "LLM-arb");
        } catch (Exception e) {
            log.warn("[Stage II] 引擎异常, 降级LLM: {}", toolName);
            return new Verdict(fallback.isMalicious(toolName, description), "LLM-fallback");
        }
    }

    /** 归一化向量，点积即余弦 */
    public static double maxCosine(float[] candidate, float[][] anchors) {
        double max = Double.NEGATIVE_INFINITY;
        for (float[] anchor : anchors) {
            double dot = 0;
            for (int i = 0; i < candidate.length; i++) {
                dot += candidate[i] * anchor[i];
            }
            max = Math.max(max, dot);
        }
        return max;
    }

    @Override
    public String engineName() {
        return "E5-diff(fallback=LLM)";
    }
}