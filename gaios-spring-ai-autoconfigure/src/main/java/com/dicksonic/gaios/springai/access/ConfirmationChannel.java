package com.dicksonic.gaios.springai.access;

/**
 * L2 人工确认通道：G 端"人工闸门"的挂接点。
 *
 * 同步语义：返回 true=批准执行，false=拒绝。实现形态举例：
 * <ul>
 *   <li>demo：控制台打印待确认调用，读 y/n；</li>
 *   <li>生产（M3+ 方向）：写待办表挂起，Web 侧确认后放行；</li>
 *   <li>对接甲方现有审批流引擎。</li>
 * </ul>
 *
 * 未配置任何 channel bean 时，RiskLevelApprovalGate 对 L2 一律 fail-closed 拒绝——
 * "该确认的确认不了"视同拒绝，绝不静默放行。
 */
public interface ConfirmationChannel {

    boolean confirm(ApprovalRequest request);
}
