package com.dicksonic.gaios.springai.access;

/**
 * 审批门 SPI：GaiosApprovalToolCallingManager 在每次工具执行前调用一次。
 *
 * 实现注意：
 * <ul>
 *   <li>在 Agent 循环主线程上被调用，异常会使整个工具批次失败——实现应自行兜底，
 *       拿不准就返回 deny（fail-closed，与 core 注册期同一哲学）；</li>
 *   <li>需要人工交互的场景不要在本接口里发明阻塞逻辑，用现成的
 *       {@link RiskLevelApprovalGate} + {@link ConfirmationChannel} 组合。</li>
 * </ul>
 *
 * 默认实现为 RiskLevelApprovalGate（L1/L2/L3 分级驱动）；
 * 容器中声明任意 ApprovalGate bean 即可整体替换（@ConditionalOnMissingBean 退位）。
 */
public interface ApprovalGate {

    ApprovalDecision evaluate(ApprovalRequest request);
}
