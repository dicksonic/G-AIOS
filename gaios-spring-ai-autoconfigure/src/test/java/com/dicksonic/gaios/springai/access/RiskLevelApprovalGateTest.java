package com.dicksonic.gaios.springai.access;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分级审批门判定矩阵：L1 放行 / L3 硬拒 / L2 三种通道情形 + fail-closed 哲学。
 */
class RiskLevelApprovalGateTest {

    private static ApprovalRequest req(String tool) {
        return new ApprovalRequest(tool, "{}");
    }

    @Test
    void L1自动放行() {
        RiskLevelApprovalGate gate = new RiskLevelApprovalGate(
                Map.of("query_regulations", ToolRiskLevel.L1), ToolRiskLevel.L1, null);
        assertTrue(gate.evaluate(req("query_regulations")).approved());
        // 未配置工具走默认级别（L1）
        assertTrue(gate.evaluate(req("unknown_tool")).approved());
    }

    @Test
    void L3硬拒绝且给出模型可读原因() {
        RiskLevelApprovalGate gate = new RiskLevelApprovalGate(
                Map.of("delete_hazard_records", ToolRiskLevel.L3), ToolRiskLevel.L1, null);
        ApprovalDecision d = gate.evaluate(req("delete_hazard_records"));
        assertFalse(d.approved());
        assertNotNull(d.reason());
        assertTrue(d.reason().contains("L3"));
    }

    @Test
    void L2无确认通道failClosed拒绝() {
        RiskLevelApprovalGate gate = new RiskLevelApprovalGate(
                Map.of("draft_notice", ToolRiskLevel.L2), ToolRiskLevel.L1, null);
        ApprovalDecision d = gate.evaluate(req("draft_notice"));
        assertFalse(d.approved());
        assertTrue(d.reason().contains("fail-closed"));
    }

    @Test
    void L2通道确认通过则放行() {
        AtomicInteger confirmCalls = new AtomicInteger();
        ConfirmationChannel channel = r -> {
            confirmCalls.incrementAndGet();
            assertEquals("draft_notice", r.toolName());
            return true;
        };
        RiskLevelApprovalGate gate = new RiskLevelApprovalGate(
                Map.of("draft_notice", ToolRiskLevel.L2), ToolRiskLevel.L1, channel);
        assertTrue(gate.evaluate(req("draft_notice")).approved());
        assertEquals(1, confirmCalls.get());
    }

    @Test
    void L2通道拒绝则拒绝() {
        RiskLevelApprovalGate gate = new RiskLevelApprovalGate(
                Map.of("draft_notice", ToolRiskLevel.L2), ToolRiskLevel.L1, r -> false);
        assertFalse(gate.evaluate(req("draft_notice")).approved());
    }

    @Test
    void L2通道异常failClosed拒绝() {
        ConfirmationChannel broken = r -> {
            throw new RuntimeException("审批流服务不可用");
        };
        RiskLevelApprovalGate gate = new RiskLevelApprovalGate(
                Map.of("draft_notice", ToolRiskLevel.L2), ToolRiskLevel.L1, broken);
        ApprovalDecision d = gate.evaluate(req("draft_notice"));
        assertFalse(d.approved());
        assertTrue(d.reason().contains("fail-closed"));
    }
}
