package com.dicksonic.gaios.springai.scanner;

import com.dicksonic.gaios.security.QuarantineEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 隔离墓碑（Quarantine Tombstone）：替被安检拦下的工具"站岗"的 ToolCallback。
 *
 * <p><b>解决什么问题：</b>被拦工具从放行清单里消失后，如果模型仍然按名字
 * 点名要调它（比如工具描述已泄漏进上下文、或调用方开了
 * {@code spring.ai.tools.resolution.fallback.enabled=true} 按名解析），
 * 框架默认行为是硬抛 {@code IllegalStateException("No ToolCallback found")}——
 * 整个对话直接炸掉，模型连"这工具不能用"都不知道。</p>
 *
 * <p><b>墓碑的做法：</b>名字照常在册（ToolDefinition 原样透传），但
 * {@code call()} 绝不委托原工具，返回一段机器可读的拒绝 JSON 回喂模型。
 * 模型拿到结构化拒绝后可以换个工具继续任务——fail-closed 但对话不死。</p>
 *
 * <p><b>数据流：</b></p>
 * <pre>
 *   模型点名 quarantinedTool → ToolCallingManager 按名 resolve
 *     → GaiosSecureToolCallbackResolver 查到墓碑
 *     → tombstone.call() 返回 {"error":"TOOL_QUARANTINED",...}
 *     → 作为 ToolResponseMessage 回喂模型 → 模型改走别的工具
 * </pre>
 *
 * <p><b>边界：</b>本类只持有原工具的 definition/metadata（让 schema 一致、
 * 审计可查），构造时就与原工具的执行能力物理隔离——{@code call()} 两个
 * 重载都不存在委托路径，不存在"墓碑误放行"的可能。</p>
 */
public final class QuarantinedToolCallback implements ToolCallback {

    private static final Logger log = LogManager.getLogger(QuarantinedToolCallback.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolDefinition definition;
    private final ToolMetadata metadata;
    private final QuarantineEntry entry;

    /**
     * @param original 被拦的原工具（只借 definition/metadata，执行能力当场丢弃）
     * @param entry    隔离区条目（哪个阶段拦的、依据是什么），原样进拒绝 JSON
     */
    public QuarantinedToolCallback(ToolCallback original, QuarantineEntry entry) {
        this.definition = original.getToolDefinition();
        this.metadata = original.getToolMetadata();
        this.entry = entry;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return metadata;
    }

    @Override
    public String call(String toolInput) {
        return refuse();
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return refuse();
    }

    /**
     * 拒绝语义回喂：机器可读 JSON（模型可理解"工具被隔离"并改道），
     * 同时 WARN 留痕——被拦工具被执行层点到名是异常事件，值得进审计视野。
     */
    private String refuse() {
        log.warn("[隔离墓碑] 被隔离工具 {} 被执行层点名（stage={}, detail={}），已拒绝并回喂模型",
                entry.toolName(), entry.stage(), entry.detail());
        ObjectNode json = MAPPER.createObjectNode();
        json.put("error", "TOOL_QUARANTINED");
        json.put("toolName", entry.toolName());
        json.put("stage", entry.stage());
        json.put("detail", entry.detail());
        json.put("quarantinedAt", entry.quarantinedAt().toString());
        return json.toString();
    }
}
