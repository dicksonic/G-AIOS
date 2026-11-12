package com.dicksonic.gaios.springai.scanner;

import com.dicksonic.gaios.security.QuarantineEntry;
import com.dicksonic.gaios.springai.GaiosSecureToolRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.util.Assert;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 带安检的 ToolCallbackResolver：顶掉 spring-ai 框架默认 resolver，
 * 让"按名解析工具"这条路上只剩已过安检的工具和隔离墓碑。
 *
 * <p><b>解决什么问题：</b>框架默认的 {@code ToolCallbackResolver}
 * （ToolCallingAutoConfiguration 装配）把容器里所有 ToolCallback 原样注册，
 * 模型按名就能调到——投毒工具与正常工具一视同仁。本类把全部候选工具逐个送进
 * {@link GaiosSecureToolRegistry} 过三阶段安检
 * （Stage I 正则 → Stage II E5 差分 → Stage III LLM 仲裁），之后按名解析
 * 只能解析出两种结果：</p>
 * <ul>
 *   <li>放行工具 → 带调用审计的包装实例（AuditedToolCallback）；</li>
 *   <li>被拦工具 → {@link QuarantinedToolCallback} 墓碑（call 返回拒绝 JSON）；</li>
 *   <li>从未登记的名字 → null（与框架 StaticToolCallbackResolver 语义一致）。</li>
 * </ul>
 *
 * <p><b>数据流：</b></p>
 * <pre>
 *   构造：收齐候选 + registry（不扫描，见下）
 *   scanAll()：候选逐个 registry.register(candidate)
 *     → 放行：approvedByName[name] = 审计包装实例
 *     → 拦截：tombstonesByName[name] = 墓碑（隔离条目原样挂上去）
 *   运行时 resolve(name) 只是查这两张表，不再触发安检
 * </pre>
 *
 * <p><b>扫描时机（v4 修复，2026-11-12）：</b>v3 是"构造即全扫"，但框架
 * ToolCallingAutoConfiguration 对 ToolCallbackResolver 是<b>构造器强注入</b>
 * （2.0.2 源码实证），而真实模型 starter 的 ChatModel 依赖 ToolCallingManager——
 * 构造期扫描一旦触发灰色区仲裁，会把
 * "resolver→registry→scanner→LLMCore→ChatModel→ToolCallingManager→resolver"
 * 闭合成创建期循环依赖（真实 starter 在场时启动必炸；纯桩测试不暴露）。
 * 故构造只收候选，扫描推迟到 {@link #scanAll()}——由装配层在
 * 所有单例就绪后（SmartInitializingSingleton）触发，启动期全扫语义不变。
 * 手工构造的使用方必须显式调用 scanAll()，否则 resolve() 直接抛异常。</p>
 *
 * <p><b>已声明的覆盖边界（与 M2-8 候选挂钩）：</b>本 resolver 管的是
 * "按名解析"路径。框架默认 {@code resolutionFallbackEnabled=false} 时，
 * 执行层优先按 ToolCallingChatOptions 里的回调实例匹配；调用方若把被拦
 * 工具的原始引用显式传给 {@code .tools(...)}，执行层不会经过本 resolver。
 * 该路径的执行层拦截（与 M2-6 审批门联动）列入 M2-8 候选，本类不假装覆盖。</p>
 *
 * <p><b>线程安全：</b>scanAll() 由装配层在启动期单线程调用（幂等，重复调用
 * 直接返回）；扫描完成后两张表不再变更，运行时 resolve 是只读操作。</p>
 */
public final class GaiosSecureToolCallbackResolver implements ToolCallbackResolver {

    private static final Logger log = LogManager.getLogger(GaiosSecureToolCallbackResolver.class);

    /** 放行表：工具名 → 安检通过且挂了调用审计的包装实例（保序，启动日志可复读） */
    private final Map<String, ToolCallback> approvedByName = new LinkedHashMap<>();

    /** 墓碑表：工具名 → 隔离墓碑（被拦工具按名解析的落点） */
    private final Map<String, QuarantinedToolCallback> tombstonesByName = new LinkedHashMap<>();

    private final Collection<ToolCallback> candidates;

    private final GaiosSecureToolRegistry registry;

    /** 扫描完成标记：false 时 resolve 拒绝服务（防"未扫描先放行"的语义空洞） */
    private volatile boolean scanned = false;

    /**
     * 只收候选与注册中心，<b>不扫描</b>（扫描见 {@link #scanAll()}，
     * 时机约束见类级 javadoc 的 v4 修复说明）。
     *
     * @param candidates 容器里收集到的全部候选工具（ToolCallback beans
     *                   + 非 MCP ToolCallbackProvider 的工具清单，收集方负责去重）
     * @param registry   安检注册中心（构造后可通过它查 quarantined() 审计明细）
     */
    public GaiosSecureToolCallbackResolver(Collection<ToolCallback> candidates,
                                           GaiosSecureToolRegistry registry) {
        Assert.notNull(candidates, "candidates 不能为 null");
        Assert.notNull(registry, "registry 不能为 null");
        this.candidates = candidates;
        this.registry = registry;
    }

    /**
     * 执行容器全扫：候选工具逐个过三阶段安检，结果分流进放行表/墓碑表。
     * 幂等：重复调用直接返回（扫描是启动期一次性动作，二次扫描只会得到
     * 相同结果却再烧一遍仲裁 token）。
     */
    public synchronized void scanAll() {
        if (scanned) {
            return;
        }
        for (ToolCallback candidate : candidates) {
            String name = candidate.getToolDefinition().name();
            if (approvedByName.containsKey(name) || tombstonesByName.containsKey(name)) {
                // 同名冲突：框架 StaticToolCallbackResolver 是静默覆盖，我们覆盖但留 WARN——
                // 同名是投毒的常见伪装姿势（冒充知名工具名），不能无声放过
                log.warn("[容器安检] 工具名 [{}] 出现重复注册，后者覆盖前者（同名伪装是常见投毒姿势，请确认来源）", name);
                approvedByName.remove(name);
                tombstonesByName.remove(name);
            }
            ToolCallback secured = registry.register(candidate);
            if (secured != null) {
                approvedByName.put(name, secured);
            } else {
                // register 返回 null = 被拦，registry.quarantined() 末尾就是本次的隔离条目
                // （adapter 内部已做极端防御：core 未写条目时会合成兜底记录，绝不断档）
                List<QuarantineEntry> entries = registry.quarantined();
                QuarantineEntry entry = entries.get(entries.size() - 1);
                tombstonesByName.put(name, new QuarantinedToolCallback(candidate, entry));
                log.warn("[容器安检] 工具 [{}] 被隔离（stage={}），已立墓碑", name, entry.stage());
            }
        }
        scanned = true;
    }

    /**
     * 按名解析：放行 → 审计包装实例；被拦 → 墓碑；未登记 → null。
     * 语义与框架 StaticToolCallbackResolver 完全对齐（未知名返回 null 而非抛异常），
     * 调用方（DefaultToolCallingManager 的 fallback 分支）零适配。
     *
     * <p>前置：scanAll() 必须已执行。未扫描先解析会让"未登记"与"未过检"
     * 无法区分（静默放行风险），故直接抛异常——这只会发生在手工装配
     * 漏调 scanAll() 的场景，正常自动装配路径不会触发。</p>
     */
    @Override
    public @Nullable ToolCallback resolve(String toolName) {
        Assert.hasText(toolName, "toolName 不能为空");
        if (!scanned) {
            throw new IllegalStateException(
                    "[容器安检] resolver 尚未执行全扫，resolve 拒绝服务（防未扫描先放行）。"
                            + "手工构造时请显式调用 scanAll()；自动装配路径由 ContainerToolScanHook 触发");
        }
        ToolCallback approved = approvedByName.get(toolName);
        if (approved != null) {
            return approved;
        }
        // 被拦工具点到名 → 给墓碑不给 null：框架拿到 null 会硬抛
        // IllegalStateException("No ToolCallback found") 炸掉整个对话，
        // 墓碑则回喂机器可读拒绝，让模型改道继续任务
        return tombstonesByName.get(toolName);
    }

    /** 放行数（启动汇总日志/测试断言用；scanAll 之前调用语义未定义） */
    public int approvedCount() {
        return approvedByName.size();
    }

    /** 隔离数（启动汇总日志/测试断言用；scanAll 之前调用语义未定义） */
    public int quarantinedCount() {
        return tombstonesByName.size();
    }
}
