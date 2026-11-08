package com.dicksonic.gaios.context.summarizer;

/**
 * 最小 LLM 调用抽象（函数式接口）。
 *
 * 存在理由：context 包不反向依赖 llm 包的具体实现，LlmSummarizer 只面向这个接口编程；
 * 接线时用 lambda 适配现有 LLMCore 即可。
 */
@FunctionalInterface
public interface PromptExecutor {

    /** 发送单轮 prompt，返回模型文本。抛出的异常由 LlmSummarizer 捕获并降级。 */
    String execute(String prompt);
}