package com.dicksonic.gaios.springai.access;

/**
 * 工具风险分级：AIOS Access Manager 的 L1/L2/L3 在 Spring AI 侧的落地。
 *
 * <ul>
 *   <li><b>L1</b> 只读查询类（查条文/查台账）：自动放行，零人工介入；</li>
 *   <li><b>L2</b> 写操作类（发通知/改状态）：需人工确认，经 ConfirmationChannel 通道；</li>
 *   <li><b>L3</b> 高危类（删除/外发/资金）：硬拒绝，Agent 任何情况下不得自动执行。</li>
 * </ul>
 *
 * 分级元数据刻意放 Spring 侧（gaios.access.tool-levels.* 配置），不进 gaios-core——
 * core 保持零 Spring；Access Manager 若未来进 core 一等公民，届时再收敛配置源。
 */
public enum ToolRiskLevel {

    /** 自动放行 */
    L1,

    /** 需人工确认（无确认通道时 fail-closed 拒绝） */
    L2,

    /** 策略硬拒绝 */
    L3
}
