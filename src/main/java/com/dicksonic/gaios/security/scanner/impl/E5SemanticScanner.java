package com.dicksonic.gaios.security.scanner.impl;

import com.dicksonic.gaios.security.scanner.SemanticScanner;

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

    private final SemanticScanner fallback;
    // TODO(d5-3): ai.onnxruntime 的 OrtSession + e5-small 的 ONNX 模型文件
    // TODO(d5-3): 若干条手写恶意描述的锚点向量（投毒三段式：触发+动作+伪装理由）

    public E5SemanticScanner(SemanticScanner fallback) {
        this.fallback = fallback;
    }

    @Override
    public boolean isMalicious(String toolName, String description) {
        // TODO(d5-3): ① 分词 ② ONNX 推理出向量 ③ 与锚点向量算余弦相似度
        //             ④ max相似度 > 阈值 → return true，否则 return false
        return fallback.isMalicious(toolName, description);
    }

    @Override
    public String engineName() {
        return "E5-ONNX(fallback=LLM)";
    }
}