package com.dicksonic.gaios.security;

import com.dicksonic.gaios.security.scanner.enums.ScanVerdict;
import com.dicksonic.gaios.security.scanner.SemanticScanner;
import com.dicksonic.gaios.tools.Tool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 注册期 fail-closed 验证（2026-11-07 拍板）：
 * UNKNOWN → 拒绝注册进隔离区；SAFE → 放行；MALICIOUS → 隔离。手写 stub，不碰真实 LLM/E5。
 */
class ToolRegistryFailClosedTest {

    /** 只会返回固定判定结果的安检 stub */
    private static SemanticScanner stubScanner(ScanVerdict fixed) {
        return new SemanticScanner() {
            @Override
            public ScanVerdict judge(String toolName, String description) {
                return fixed;
            }

            @Override
            public String engineName() {
                return "stub";
            }
        };
    }

    private static Tool innocentTool() {
        return new Tool() {
            @Override
            public String name() {
                return "query_demo";
            }

            @Override
            public String description() {
                return "查询演示台账数据，只读。";
            }

            @Override
            public String execute(String argsJson) {
                return "{}";
            }
        };
    }

    @Test
    void 判定失败UNKNOWN_拒绝注册且进隔离区() {
        ToolRegistry registry = new ToolRegistry(stubScanner(ScanVerdict.UNKNOWN));

        boolean ok = registry.register(innocentTool());

        assertFalse(ok, "UNKNOWN 不得放行（注册期 fail-closed）");
        assertTrue(registry.approvedTools().isEmpty());
        assertTrue(registry.quarantined().contains("query_demo"), "UNKNOWN 工具必须留隔离区审计痕迹");
    }

    @Test
    void 判定安全SAFE_正常放行() {
        ToolRegistry registry = new ToolRegistry(stubScanner(ScanVerdict.SAFE));

        boolean ok = registry.register(innocentTool());

        assertTrue(ok);
        assertEquals(1, registry.approvedTools().size());
        assertTrue(registry.quarantined().isEmpty());
    }

    @Test
    void 判定恶意MALICIOUS_隔离() {
        ToolRegistry registry = new ToolRegistry(stubScanner(ScanVerdict.MALICIOUS));

        boolean ok = registry.register(innocentTool());

        assertFalse(ok);
        assertTrue(registry.approvedTools().isEmpty());
        assertTrue(registry.quarantined().contains("query_demo"));
    }
}