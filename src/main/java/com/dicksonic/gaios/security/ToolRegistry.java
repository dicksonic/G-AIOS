package com.dicksonic.gaios.security;

import com.dicksonic.gaios.tools.Tool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具注册中心：所有工具必须先过安检，才能进入 Agent 的工具列表。
 * 这是"工具供应链安全"的第一道闸门——MCPTox 攻击发生在工具描述
 * 进入模型上下文的那一刻，所以我们要在"进入之前"拦截。
 */
public class ToolRegistry {

    private static final Logger log = LogManager.getLogger(ToolRegistry.class);

    private final StaticScanner scanner = new StaticScanner();
    private final List<Tool> approved = new ArrayList<>();
    private final List<String> quarantine = new ArrayList<>(); // 被隔离的工具名，留作审计证据

    /** 注册工具：扫描通过才放行，否则隔离并记录 */
    public boolean register(Tool tool) {
        List<String> hits = scanner.scan(tool.name(), tool.description());
        if (hits.isEmpty()) {
            approved.add(tool);
            return true;
        }
        quarantine.add(tool.name());
        log.warn("[隔离] 工具 {} 未通过安检, 命中规则: {}", tool.name(), hits);
        return false;
    }

    public List<Tool> approvedTools() {
        return List.copyOf(approved);
    }

    public List<String> quarantined() {
        return List.copyOf(quarantine);
    }
}