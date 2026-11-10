package com.dicksonic.gaios.security;

import java.time.Instant;

/**
 * 隔离区条目：被安检拦截工具的结构化审计证据。
 *
 * 由来：原 ToolRegistry 的隔离区只存工具名，细节散在 log4j 日志里程序取不到；
 * M2-5 Spring AI adapter（以及后续任何接入方）需要结构化记录做接入侧审计展示，
 * 故抽出本类型。纯加法，quarantined() 名单语义不变。
 *
 * @param stage  拦截阶段："Stage I"（静态正则）或 "Stage II/III"（语义安检，含 LLM 仲裁）
 * @param detail 人类可读的拦截依据（命中规则名 / 判定结论），原样进日志与审计
 */
public record QuarantineEntry(String toolName, String stage, String detail, Instant quarantinedAt) {
}
