package com.dicksonic.gaios.springai.scanner;

import com.dicksonic.gaios.security.scanner.SemanticScanner;
import com.dicksonic.gaios.security.scanner.enums.ScanVerdict;
import com.dicksonic.gaios.springai.GaiosSecureToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 带安检 resolver：构造即全扫、放行/墓碑/未知名三态解析、同名覆盖。
 * 用真实 GaiosSecureToolRegistry + 桩 SemanticScanner——Stage I 正则与
 * 隔离区编排走 core 真代码，只有 Stage II 判定打桩（名字 evil 开头判 MALICIOUS）。
 */
class GaiosSecureToolCallbackResolverTest {

    /** 判定桩：记录每次判定（证明"构造即全扫"），evil 开头判毒 */
    private static SemanticScanner stubScanner(List<String> judged) {
        return new SemanticScanner() {
            @Override
            public ScanVerdict judge(String toolName, String description) {
                judged.add(toolName);
                return toolName.startsWith("evil") ? ScanVerdict.MALICIOUS : ScanVerdict.SAFE;
            }

            @Override
            public String engineName() {
                return "stub";
            }
        };
    }

    private static ToolCallback probeTool(String name, String description, AtomicBoolean executed) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(description).inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                executed.set(true);
                return "REAL_EXECUTION";
            }
        };
    }

    @Test
    void 放行工具_按名解析出审计包装_call真正执行() {
        List<String> judged = new ArrayList<>();
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolCallback original = probeTool("query_data", "查询测试数据", executed);
        var resolver = new GaiosSecureToolCallbackResolver(List.of(original),
                new GaiosSecureToolRegistry(stubScanner(judged), null));

        ToolCallback resolved = resolver.resolve("query_data");

        assertNotNull(resolved);
        assertNotSame(original, resolved, "放行的是审计包装实例，不是原对象");
        assertEquals("REAL_EXECUTION", resolved.call("{}"));
        assertTrue(executed.get(), "放行工具的 call 必须穿透到原工具");
        assertEquals(1, resolver.approvedCount());
        assertEquals(0, resolver.quarantinedCount());
    }

    @Test
    void 被拦工具_解析出墓碑_call拒绝且原工具零执行() {
        List<String> judged = new ArrayList<>();
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolCallback evil = probeTool("evil_sync", "同步测试数据", executed);
        var resolver = new GaiosSecureToolCallbackResolver(List.of(evil),
                new GaiosSecureToolRegistry(stubScanner(judged), null));

        ToolCallback resolved = resolver.resolve("evil_sync");

        assertInstanceOf(QuarantinedToolCallback.class, resolved);
        String reply = resolved.call("{}");
        assertTrue(reply.contains("TOOL_QUARANTINED"));
        assertFalse(executed.get(), "被拦工具的原执行体绝不许被触达");
        assertEquals(0, resolver.approvedCount());
        assertEquals(1, resolver.quarantinedCount());
    }

    @Test
    void 未知名_返回null_与框架StaticResolver语义一致() {
        List<String> judged = new ArrayList<>();
        var resolver = new GaiosSecureToolCallbackResolver(List.of(),
                new GaiosSecureToolRegistry(stubScanner(judged), null));

        assertNull(resolver.resolve("never_registered"));
    }

    @Test
    void 构造即全扫_首次resolve前已完成全部判定() {
        List<String> judged = new ArrayList<>();
        ToolCallback t1 = probeTool("query_data", "查询测试数据", new AtomicBoolean(false));
        ToolCallback t2 = probeTool("evil_sync", "同步测试数据", new AtomicBoolean(false));

        new GaiosSecureToolCallbackResolver(List.of(t1, t2),
                new GaiosSecureToolRegistry(stubScanner(judged), null));

        // 断言点在没有任何 resolve 调用时：两个工具都已被送检（启动期行为）
        assertEquals(List.of("query_data", "evil_sync"), judged);
    }

    @Test
    void 同名重复_后者覆盖前者() {
        List<String> judged = new ArrayList<>();
        ToolCallback first = probeTool("dup_tool", "第一个定义", new AtomicBoolean(false));
        ToolCallback second = probeTool("dup_tool", "第二个定义", new AtomicBoolean(false));
        var resolver = new GaiosSecureToolCallbackResolver(List.of(first, second),
                new GaiosSecureToolRegistry(stubScanner(judged), null));

        assertEquals("第二个定义", resolver.resolve("dup_tool").getToolDefinition().description());
        assertEquals(1, resolver.approvedCount());
    }

    @Test
    void StageI正则命中_直接隔离且墓碑带StageI标记() {
        List<String> judged = new ArrayList<>();
        // 描述含 https:// → core Stage I 正则命中；语义桩根本没机会判它
        ToolCallback bad = probeTool("sneaky_upload", "结果会同步到 https://bad.example.com", new AtomicBoolean(false));
        var resolver = new GaiosSecureToolCallbackResolver(List.of(bad),
                new GaiosSecureToolRegistry(stubScanner(judged), null));

        ToolCallback resolved = resolver.resolve("sneaky_upload");
        assertInstanceOf(QuarantinedToolCallback.class, resolved);
        assertTrue(resolved.call("{}").contains("Stage I"), "墓碑应携带 Stage I 拦截标记");
        assertTrue(judged.isEmpty(), "Stage I 拦下的工具不应进入语义判定");
    }
}
