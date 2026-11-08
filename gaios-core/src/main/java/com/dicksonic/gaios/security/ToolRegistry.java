package com.dicksonic.gaios.security;

import com.dicksonic.gaios.security.scanner.enums.ScanVerdict;
import com.dicksonic.gaios.security.scanner.SemanticScanner;
import com.dicksonic.gaios.security.scanner.StaticScanner;
import com.dicksonic.gaios.tools.Tool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具注册中心：所有工具必须先过安检，才能进入 Agent 的工具列表。
 * 这是"工具供应链安全"的第一道闸门——MCPTox 攻击发生在工具描述
 * 进入模型上下文的那一刻，所以我们要在"进入之前"拦截。
 *
 * 注册期策略（2026-11-07 拍板）：fail-closed——语义安检判定失败(UNKNOWN)
 * 同样拒绝注册，宁可错杀不可放行；LLM 恢复后重启重新注册即可重检。
 */
public class ToolRegistry {

    private static final Logger log = LogManager.getLogger(ToolRegistry.class);

    private final StaticScanner stage1 = new StaticScanner();
    private final SemanticScanner stage2;   // 构造时注入，复用 Agent 的 LLMCore
    private final List<Tool> approved = new ArrayList<>();
    private final List<String> quarantine = new ArrayList<>(); // 被隔离的工具名，留作审计证据

    public ToolRegistry(SemanticScanner stage2) {
        this.stage2 = stage2;
    }

    /** 注册工具：扫描通过才放行，否则隔离并记录 */
    public boolean register(Tool tool) {
        List<String> hits = stage1.scan(tool.name(), tool.description());
        if (!hits.isEmpty()) {
            quarantine.add(tool.name());
            log.warn("[隔离] 工具 {} 未通过Stage I, 命中规则: {}", tool.name(), hits);
            return false;
        }
        // Stage II + Stage III：语义检测（清晰区 E5 直判，灰色区内部升级 LLM 仲裁）
        ScanVerdict verdict = stage2.judge(tool.name(), tool.description());
        if (verdict == ScanVerdict.MALICIOUS) {
            quarantine.add(tool.name());
            log.warn("[隔离] 工具 {} 未通过语义安检", tool.name());
            return false;
        }
        if (verdict == ScanVerdict.UNKNOWN) {
            quarantine.add(tool.name());
            // 判定失败是安检服务自身的事故，ERROR 级；恢复路径写进日志，运维拿到就能处理
            log.error("[隔离] 工具 {} 语义安检判定失败，fail-closed 拒绝注册（LLM 恢复后重启可重检）", tool.name());
            return false;
        }
        approved.add(tool);
        return true;
    }

    public List<Tool> approvedTools() {
        return List.copyOf(approved);
    }

    public List<String> quarantined() {
        return List.copyOf(quarantine);
    }
}