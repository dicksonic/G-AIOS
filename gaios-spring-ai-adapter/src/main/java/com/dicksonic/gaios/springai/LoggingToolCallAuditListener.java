package com.dicksonic.gaios.springai;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 默认审计实现：log4j 落日志。成功 INFO、失败 WARN。
 * 参数与结果原文打印（与 core "错误路径打印原文" 的约定一致）；
 * 生产侧若担心敏感参数落盘，应换实现做脱敏，而不是在这里截断——
 * 骨架期的可观测性优先于洁癖。
 */
public class LoggingToolCallAuditListener implements ToolCallAuditListener {

    private static final Logger log = LogManager.getLogger(LoggingToolCallAuditListener.class);

    @Override
    public void onAudit(ToolCallAuditEvent event) {
        if (event.success()) {
            log.info("[工具审计] {} 调用成功, 耗时{}ms, 参数={}, 返回={}",
                    event.toolName(), event.durationMs(), event.argsJson(), event.resultJson());
        } else {
            log.warn("[工具审计] {} 调用失败, 耗时{}ms, 参数={}, 错误={}",
                    event.toolName(), event.durationMs(), event.argsJson(), event.error());
        }
    }
}
