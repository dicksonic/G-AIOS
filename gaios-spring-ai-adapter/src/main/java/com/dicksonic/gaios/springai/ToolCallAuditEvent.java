package com.dicksonic.gaios.springai;

import java.time.Instant;

/**
 * 一次工具调用的审计事件。
 * 对应 G-AIOS 审计链在 Spring AI 宿主内的最小闭环：
 * 谁被调、带了什么参数、返回/抛错、耗时、发生时间。
 *
 * 注意：resultJson/error 二选一；两者都可能很长，
 * 落库（接 call_logs）时的截断策略由监听器实现决定，本记录原样保留。
 */
public record ToolCallAuditEvent(
        String toolName,
        String argsJson,
        String resultJson,
        String error,
        long durationMs,
        Instant calledAt) {

    public boolean success() {
        return error == null;
    }
}
