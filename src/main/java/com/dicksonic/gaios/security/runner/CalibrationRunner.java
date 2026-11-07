package com.dicksonic.gaios.security.runner;

import com.dicksonic.gaios.security.scanner.enums.ScanVerdict;
import com.dicksonic.gaios.security.scanner.SemanticScanner;
import com.dicksonic.gaios.security.scanner.data.CalibrationSet;
import com.dicksonic.gaios.security.scanner.engine.E5EmbeddingEngine;
import com.dicksonic.gaios.security.scanner.impl.E5SemanticScanner;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.dicksonic.gaios.tools.Tool.fmt;

/**
 * 标定器：用 CalibrationSet 跑差分，输出 margin。
 * margin = 毒组最小差分 - 正常组最大差分；margin > 0 可分离，越厚阈值容错越大。
 * 锚点引用 E5SemanticScanner 的同一份常量——标定测的必须是线上判定的真实水位。
 */
public class CalibrationRunner {

    private static final Logger log = LogManager.getLogger(CalibrationRunner.class);
    private final E5EmbeddingEngine engine;

    public CalibrationRunner(E5EmbeddingEngine engine) {
        this.engine = engine;
    }

    public void run(SemanticScanner fallbackScanner) throws Exception {
        float[][] poisonAnchors = embedAll(E5SemanticScanner.POISON_ANCHORS);
        float[][] normalAnchors = embedAll(E5SemanticScanner.NORMAL_ANCHORS);

        double minPoison = Double.MAX_VALUE;
        double maxNormal = Double.NEGATIVE_INFINITY;

        log.info("========== 标定开始（{} 毒 / {} 正常）==========",
                CalibrationSet.POISONED_SAMPLES.length, CalibrationSet.NORMAL_SAMPLES.length);

        for (String s : CalibrationSet.POISONED_SAMPLES) {
            double d = diff(s, poisonAnchors, normalAnchors);
            minPoison = Math.min(minPoison, d);
            log.info("[毒]   diff={}", fmt(d));
        }
        for (String s : CalibrationSet.NORMAL_SAMPLES) {
            double d = diff(s, poisonAnchors, normalAnchors);
            maxNormal = Math.max(maxNormal, d);
            log.info("[正常] diff={}", fmt(d));
        }

        double margin = minPoison - maxNormal;
        log.info("毒组min={}  正常组max={}  margin={}", fmt(minPoison), fmt(maxNormal), fmt(margin));
        log.info(margin > 0 ? "✅ 可分离，阈值建议取: " + fmt((minPoison + maxNormal) / 2) : "❌ 两组交叉，需扩锚点或升级模型");

        // ===== 管线级标定：三阶段综合准确率 =====
        // 注意：构造一个独立的 scanner 实例（不复用注册那个，避免统计污染）
        SemanticScanner llm = fallbackScanner;
        E5SemanticScanner pipeline = new E5SemanticScanner(engine, llm, 0.0);

        int e5Total = 0, e5Correct = 0, llmTotal = 0, llmCorrect = 0, unknown = 0;

        for (String s : CalibrationSet.POISONED_SAMPLES) {
            E5SemanticScanner.Verdict v = pipeline.judgeDetailed("calib", s);
            if ("E5-clear".equals(v.path())) {
                e5Total++;
                if (v.outcome() == ScanVerdict.MALICIOUS) e5Correct++;
            } else if (v.outcome() == ScanVerdict.UNKNOWN) {
                unknown++;
            } else {
                llmTotal++;
                if (v.outcome() == ScanVerdict.MALICIOUS) llmCorrect++;
            }
        }
        for (String s : CalibrationSet.NORMAL_SAMPLES) {
            E5SemanticScanner.Verdict v = pipeline.judgeDetailed("calib", s);
            if ("E5-clear".equals(v.path())) {
                e5Total++;
                if (v.outcome() == ScanVerdict.SAFE) e5Correct++;
            } else if (v.outcome() == ScanVerdict.UNKNOWN) {
                unknown++;
            } else {
                llmTotal++;
                if (v.outcome() == ScanVerdict.SAFE) llmCorrect++;
            }
        }

        log.info("========== 管线级标定 ==========");
        log.info("清晰区(E5判定): {} 个, 正确 {} 个", e5Total, e5Correct);
        log.info("灰色区(LLM仲裁): {} 个, 正确 {} 个", llmTotal, llmCorrect);
        // UNKNOWN 单列：既不算对也不算错，fail-open 时代"蒙对/误判"的水分从此挤掉
        log.info("仲裁失败(UNKNOWN): {} 个（不计入分母；fail-closed 下这些样本实际会被隔离）", unknown);
        log.info("综合准确率: {}/{}", e5Correct + llmCorrect, e5Total + llmTotal);
    }

    /** diff = cos(候选,毒锚点max) - cos(候选,正常锚点max)，与 E5SemanticScanner 判定同式 */
    private double diff(String text, float[][] pv, float[][] nv) throws Exception {
        float[] vec = engine.embed(text);
        return E5SemanticScanner.maxCosine(vec, pv) - E5SemanticScanner.maxCosine(vec, nv);
    }

    private float[][] embedAll(String[] texts) throws Exception {
        float[][] vecs = new float[texts.length][];
        for (int i = 0; i < texts.length; i++) {
            vecs[i] = engine.embed(texts[i]);
        }
        return vecs;
    }
}