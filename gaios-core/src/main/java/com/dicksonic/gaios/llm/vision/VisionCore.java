package com.dicksonic.gaios.llm.vision;

/**
 * 视觉模型核心。与 LLMCore 平级的第二条模型通道：LLMCore 管文本决策，VisionCore 管图片理解。
 * 契约同 LLMCore.chat：实现永不抛异常，失败返回 "error"。
 */
public interface VisionCore {

    /**
     * 识别单张图片。
     *
     * @return 模型输出的文本（内容形态由 prompt 决定，结构化解析是调用方职责）；
     *         失败返回 "error"，与 LLMCore.chat 的失败哨兵一致
     */
    String recognize(VisionRequest request);
}