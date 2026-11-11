package com.dicksonic.gaios.springai.scanner;

import com.dicksonic.gaios.security.QuarantineEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 隔离墓碑：定义透传（schema 一致、审计可查）+ call 永不委托原工具 +
 * 拒绝 JSON 机器可读。原子开关断言"原工具零执行"。
 */
class QuarantinedToolCallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 稳定的 metadata 实例（真实实现都是构造期建一次，桩必须同样逼真） */
    private static final ToolMetadata STABLE_METADATA = ToolMetadata.builder().build();

    /**
     * 可探测执行的原工具桩：executed 置 true = 墓碑失职（测试必须红）。
     * definition/metadata 构造一次、每次返回同一实例——墓碑"原样透传"
     * 的 assertSame 契约只有在这个前提下才可断言。
     */
    private static ToolCallback probeTool(String name, String description, AtomicBoolean executed) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name).description(description).inputSchema("{}").build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return STABLE_METADATA;
            }

            @Override
            public String call(String toolInput) {
                executed.set(true);
                return "REAL_EXECUTION";
            }
        };
    }

    private static QuarantineEntry entryOf(String name) {
        return new QuarantineEntry(name, "Stage II/III", "语义安检判定 MALICIOUS", Instant.now());
    }

    @Test
    void 定义与元数据原样透传() {
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolCallback original = probeTool("evil_sync", "同步数据到某处", executed);
        QuarantinedToolCallback tombstone = new QuarantinedToolCallback(original, entryOf("evil_sync"));

        assertSame(original.getToolDefinition(), tombstone.getToolDefinition());
        assertSame(original.getToolMetadata(), tombstone.getToolMetadata());
    }

    @Test
    void call返回机器可读拒绝JSON_原工具零执行() throws Exception {
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolCallback original = probeTool("evil_sync", "同步数据到某处", executed);
        QuarantinedToolCallback tombstone = new QuarantinedToolCallback(original, entryOf("evil_sync"));

        String reply = tombstone.call("{}");

        assertFalse(executed.get(), "墓碑 call() 绝不许委托原工具");
        JsonNode json = MAPPER.readTree(reply);
        assertEquals("TOOL_QUARANTINED", json.get("error").asText());
        assertEquals("evil_sync", json.get("toolName").asText());
        assertEquals("Stage II/III", json.get("stage").asText());
        assertTrue(json.hasNonNull("quarantinedAt"));
    }

    @Test
    void 带ToolContext的call同样拒绝() {
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolCallback original = probeTool("evil_sync", "同步数据到某处", executed);
        QuarantinedToolCallback tombstone = new QuarantinedToolCallback(original, entryOf("evil_sync"));

        String reply = tombstone.call("{}", null);

        assertFalse(executed.get());
        assertTrue(reply.contains("TOOL_QUARANTINED"));
    }
}
