package com.dicksonic.gaios.llm;

/**
 * 真实 token 计量回调：模型核心在成功响应时触发一次，失败不触发。
 * 监听器是计量旁路，实现内抛出的异常必须被核心吞掉（只打日志），绝不允许打挂主流程。
 * 原为 DeepSeekCore 内嵌接口，M2 视觉核心复用同一契约，提升为顶层接口；
 * DeepSeekCore.UsageListener 继承本接口，旧代码（web 侧 AgentService）零改动。
 */
public interface UsageListener {
    void onUsage(int promptTokens, int completionTokens, int totalTokens);
}