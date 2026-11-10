package com.dicksonic.gaios.springai;

import com.dicksonic.gaios.GaiosBootstrap;
import com.dicksonic.gaios.security.QuarantineEntry;
import com.dicksonic.gaios.security.ToolRegistry;
import com.dicksonic.gaios.security.scanner.SemanticScanner;
import com.dicksonic.gaios.tools.Tool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.ai.tool.ToolCallback;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * G-AIOS × Spring AI 注册点装饰器（姿势 C 骨架）。
 *
 * 职责：Spring AI 的 ToolCallback 在进入模型上下文之前，
 * 先过桥进 core 的三阶段安检（Stage I 正则 → Stage II E5 差分 → Stage III LLM 仲裁），
 * 放行的包上调用审计装饰器，被拦的进隔离区留证。
 *
 * 关键边界：
 * 1. 判定逻辑零重写——内部持有一个 core 的 ToolRegistry，
 *    通过 SpringAiToolBridge 把 ToolCallback 适配成 gaios Tool 送检，
 *    core 安检策略演进（阈值/规则/仲裁 prompt）自动生效，本模块无感知；
 * 2. 注册期 fail-closed 语义原样继承（UNKNOWN 也隔离）；
 * 3. 刻意不碰 Spring 容器（无 BeanPostProcessor/auto-config）：
 *    纯 Java 装饰器，任何宿主都能用；Spring Boot 自动装配属后续分包；
 * 4. 只在启动/注册期使用，非线程安全（与 core ToolRegistry 一致）。
 *
 * 典型接线（Spring AI 2.0，工具显式传给 ChatClient）：
 * <pre>
 *   GaiosSecureToolRegistry secure = GaiosSecureToolRegistry.from(components, new LoggingToolCallAuditListener());
 *   secure.register(myToolCallback);          // 第三方工具逐个过闸
 *   chatClient.prompt().tools(secure.approvedCallbacks().toArray(ToolCallback[]::new))...
 * </pre>
 */
public final class GaiosSecureToolRegistry {

    private static final Logger log = LogManager.getLogger(GaiosSecureToolRegistry.class);

    private final ToolRegistry coreRegistry;      // 三阶段编排的唯一事实源（core）
    private final ToolCallAuditListener auditListener;
    private final List<ToolCallback> approved = new ArrayList<>();
    private final List<QuarantineEntry> quarantined = new ArrayList<>();

    /**
     * @param stage2        Stage II/III 语义安检链（生产取 GaiosBootstrap.Components#scanner()；
     *                      测试可注入桩实现）
     * @param auditListener 调用审计回调；传 null 用 LoggingToolCallAuditListener 兜底
     */
    public GaiosSecureToolRegistry(SemanticScanner stage2, ToolCallAuditListener auditListener) {
        this.coreRegistry = new ToolRegistry(stage2);
        this.auditListener = auditListener != null ? auditListener : new LoggingToolCallAuditListener();
    }

    /**
     * 生产装配捷径：复用 GaiosBootstrap 已装配的安检链。
     * 调用方自己持有 Components（boot 时 withBuiltinMockTools 按场景选），
     * 本方法只借它的 scanner，不动它的 registry——core 侧业务工具与
     * Spring AI 侧第三方工具的隔离区互不污染。
     */
    public static GaiosSecureToolRegistry from(GaiosBootstrap.Components components,
                                               ToolCallAuditListener auditListener) {
        return new GaiosSecureToolRegistry(components.scanner(), auditListener);
    }

    /**
     * 注册一个 Spring AI ToolCallback：安检通过返回带审计的包装实例，被隔离返回 null。
     * 返回 null 时不抛异常——注册期收集全部结果后统一处置（启动日志/审计页），
     * 与 core register 的 boolean 语义对齐。
     */
    public ToolCallback register(ToolCallback candidate) {
        String name = candidate.getToolDefinition().name();
        int entriesBefore = coreRegistry.quarantineEntries().size();
        boolean pass = coreRegistry.register(new SpringAiToolBridge(candidate));
        if (!pass) {
            quarantined.add(lastQuarantineEntry(name, entriesBefore));
            return null;
        }
        AuditedToolCallback secured = new AuditedToolCallback(candidate, auditListener);
        approved.add(secured);
        log.info("[安检放行] Spring AI 工具 {} 已过三阶段安检并挂调用审计", name);
        return secured;
    }

    /** 取 core 刚写入的隔离条目；极端防御：条数没涨时合成兜底记录，绝不让审计断档 */
    private QuarantineEntry lastQuarantineEntry(String toolName, int entriesBefore) {
        List<QuarantineEntry> entries = coreRegistry.quarantineEntries();
        if (entries.size() > entriesBefore) {
            return entries.get(entries.size() - 1);
        }
        return new QuarantineEntry(toolName, "UNKNOWN_STAGE", "core 未返回隔离细节（ToolRegistry 行为异常，需排查）", Instant.now());
    }

    /** 已过安检的工具（含审计装饰），传给 ChatClient .tools(...) 的就是这份清单 */
    public List<ToolCallback> approvedCallbacks() {
        return List.copyOf(approved);
    }

    /** 隔离区结构化记录：谁被拦、哪个阶段、依据是什么 */
    public List<QuarantineEntry> quarantined() {
        return List.copyOf(quarantined);
    }

    /**
     * Spring AI ToolCallback → gaios Tool 的送检桥。
     * 安检只读 name/description；execute 理论上不会被注册期安检链路调到，
     * 仍按语义委托——桥不搞"安检时能用、运行时炸"的半成品。
     */
    private static final class SpringAiToolBridge implements Tool {

        private final ToolCallback delegate;

        private SpringAiToolBridge(ToolCallback delegate) {
            this.delegate = delegate;
        }

        @Override
        public String name() {
            return delegate.getToolDefinition().name();
        }

        @Override
        public String description() {
            return delegate.getToolDefinition().description();
        }

        @Override
        public String execute(String argsJson) {
            return delegate.call(argsJson);
        }
    }
}
