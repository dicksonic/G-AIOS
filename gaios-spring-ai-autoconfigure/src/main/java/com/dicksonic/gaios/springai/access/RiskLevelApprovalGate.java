package com.dicksonic.gaios.springai.access;

import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 默认审批门：按工具风险分级裁决。
 *
 * <pre>
 *   L1 → 自动放行
 *   L2 → 交 ConfirmationChannel 人工确认；无通道/通道异常/确认未通过 = 拒绝（fail-closed）
 *   L3 → 硬拒绝
 * </pre>
 *
 * fail-closed 与 core 注册期 UNKNOWN 进隔离区是同一哲学：
 * 安全判定链条上任何一环"判不了"，结果都是拒绝，绝不静默放行。
 */
public class RiskLevelApprovalGate implements ApprovalGate {

    private static final Logger log = LogManager.getLogger(RiskLevelApprovalGate.class);

    private final Map<String, ToolRiskLevel> toolLevels;
    private final ToolRiskLevel defaultLevel;
    private final ConfirmationChannel confirmationChannel; // null=未配置人工通道

    /**
     * @param toolLevels          工具名 → 分级（来自 gaios.access.tool-levels.*）
     * @param defaultLevel        未配置工具的分级（null 按 L1 处理）
     * @param confirmationChannel L2 人工确认通道，允许 null（null 时 L2 全拒）
     */
    public RiskLevelApprovalGate(Map<String, ToolRiskLevel> toolLevels, ToolRiskLevel defaultLevel,
            ConfirmationChannel confirmationChannel) {
        this.toolLevels = Map.copyOf(toolLevels);
        this.defaultLevel = defaultLevel == null ? ToolRiskLevel.L1 : defaultLevel;
        this.confirmationChannel = confirmationChannel;
    }

    @Override
    public ApprovalDecision evaluate(ApprovalRequest request) {
        ToolRiskLevel level = toolLevels.getOrDefault(request.toolName(), defaultLevel);
        return switch (level) {
            case L1 -> ApprovalDecision.approve();
            case L3 -> {
                log.warn("[审批门] 工具 {} 分级 L3 高危，策略硬拒绝（参数={}）", request.toolName(), request.argumentsJson());
                yield ApprovalDecision.deny(
                        "工具 " + request.toolName() + " 分级为 L3（高危操作），安全策略禁止 Agent 自动执行");
            }
            case L2 -> evaluateL2(request);
        };
    }

    private ApprovalDecision evaluateL2(ApprovalRequest request) {
        if (confirmationChannel == null) {
            log.warn("[审批门] 工具 {} 分级 L2 需人工确认，但未配置 ConfirmationChannel，fail-closed 拒绝",
                    request.toolName());
            return ApprovalDecision.deny("工具 " + request.toolName()
                    + " 分级为 L2，需人工确认后执行；当前未配置确认通道（ConfirmationChannel bean），按 fail-closed 拒绝");
        }
        boolean confirmed;
        try {
            confirmed = confirmationChannel.confirm(request);
        }
        catch (RuntimeException e) {
            // 通道自身故障同样 fail-closed；原文进日志便于排查
            log.error("[审批门] 确认通道执行异常，fail-closed 拒绝: {}", e.toString());
            return ApprovalDecision.deny("人工确认通道执行异常，按 fail-closed 拒绝: " + e.getMessage());
        }
        if (!confirmed) {
            log.info("[审批门] 工具 {} 人工确认未通过，拒绝执行", request.toolName());
            return ApprovalDecision.deny("工具 " + request.toolName() + " 的人工确认未通过，操作员拒绝了本次执行");
        }
        log.info("[审批门] 工具 {} 人工确认通过，放行执行", request.toolName());
        return ApprovalDecision.approve();
    }
}
