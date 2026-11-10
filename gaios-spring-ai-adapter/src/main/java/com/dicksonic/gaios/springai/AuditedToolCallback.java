package com.dicksonic.gaios.springai;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.time.Instant;

/**
 * 调用审计装饰器：包在已通过安检的 ToolCallback 外面，
 * 对模型与 Spring AI 框架完全透明（ToolDefinition/ToolMetadata 原样透传），
 * 只在 call() 前后加计时与审计事件。
 *
 * 只装饰、不改行为：业务异常原样上抛（由 Spring AI 的
 * ToolExecutionExceptionProcessor 决定回喂模型还是抛出），审计只负责记录。
 */
public final class AuditedToolCallback implements ToolCallback {

    private static final Logger log = LogManager.getLogger(AuditedToolCallback.class);

    private final ToolCallback delegate;
    private final ToolCallAuditListener auditListener;

    public AuditedToolCallback(ToolCallback delegate, ToolCallAuditListener auditListener) {
        this.delegate = delegate;
        this.auditListener = auditListener;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return doCall(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return doCall(toolInput, toolContext);
    }

    private String doCall(String toolInput, ToolContext toolContext) {
        String toolName = delegate.getToolDefinition().name();
        Instant calledAt = Instant.now();
        long t0 = System.nanoTime();
        try {
            // 有 ToolContext 时走两参重载，避免丢上下文（如 MCP 元数据）
            String result = toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
            fire(new ToolCallAuditEvent(toolName, toolInput, result, null, elapsedMs(t0), calledAt));
            return result;
        } catch (RuntimeException e) {
            fire(new ToolCallAuditEvent(toolName, toolInput, null, e.toString(), elapsedMs(t0), calledAt));
            throw e;
        }
    }

    private void fire(ToolCallAuditEvent event) {
        try {
            auditListener.onAudit(event);
        } catch (Throwable t) {
            // 旁路契约：审计实现炸了只打日志，绝不打挂业务调用
            log.warn("[工具审计] 审计监听器异常被吞: {}", t.toString());
        }
    }

    private static long elapsedMs(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
