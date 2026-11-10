package com.dicksonic.gaios.springai;

/**
 * 工具调用审计回调：AuditedToolCallback 在每次调用结束后触发一次（成功失败都触发）。
 *
 * 旁路契约（沿用 core UsageListener 的哲学）：
 * 实现内抛出的任何异常都会被装饰器吞掉（只打日志），绝不允许审计打挂业务调用。
 *
 * 生产落地形态：web 侧实现本接口写 call_logs；本模块自带 LoggingToolCallAuditListener 兜底。
 */
public interface ToolCallAuditListener {

    void onAudit(ToolCallAuditEvent event);
}
