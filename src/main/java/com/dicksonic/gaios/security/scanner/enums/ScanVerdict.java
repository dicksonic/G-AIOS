package com.dicksonic.gaios.security.scanner.enums;

/**
 * 语义安检三态判定结果。
 * UNKNOWN 存在的意义：仲裁服务自身故障时，"没判成"绝不能伪装成"判了安全"。
 * 处置策略（2026-11-07 用户拍板）：
 *   注册期 → fail-closed，UNKNOWN 拒绝注册进隔离区；
 *   运行期 → fail-open 保可用（待 M2/M3 运行时护栏落地时生效，当前安检只发生在注册期）。
 */
public enum ScanVerdict {
    SAFE, MALICIOUS, UNKNOWN
}