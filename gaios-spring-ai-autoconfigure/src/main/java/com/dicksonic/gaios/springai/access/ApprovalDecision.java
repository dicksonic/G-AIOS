package com.dicksonic.gaios.springai.access;

/**
 * 审批裁决。
 *
 * @param approved true=放行执行
 * @param reason   拒绝原因（approved=true 时为 null）。拒绝原因会原样回喂模型，
 *                 是"模型可读的拒绝解释"——措辞应让模型明白别再重试、如实告知用户。
 */
public record ApprovalDecision(boolean approved, String reason) {

    public static ApprovalDecision approve() {
        return new ApprovalDecision(true, null);
    }

    public static ApprovalDecision deny(String reason) {
        if (reason == null || reason.isBlank()) {
            // 空原因会把"为什么没执行"变成猜谜，模型与用户都拿不到信息——不许发生
            throw new IllegalArgumentException("deny 必须给出人类可读的 reason");
        }
        return new ApprovalDecision(false, reason);
    }
}
