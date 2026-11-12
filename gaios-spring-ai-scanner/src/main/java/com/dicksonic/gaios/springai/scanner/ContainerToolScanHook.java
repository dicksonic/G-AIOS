package com.dicksonic.gaios.springai.scanner;

import com.dicksonic.gaios.llm.LLMCore;
import com.dicksonic.gaios.springai.GaiosSecureToolRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * 容器全扫触发钩：所有单例就绪后（SmartInitializingSingleton）执行
 * "ChatModel 在位检查 + 标定护栏 WARN + resolver.scanAll() + 启动汇总日志"。
 *
 * <p><b>为什么扫描在这里而不是 resolver 构造器（v4 修复，2026-11-12）：</b>
 * 框架 ToolCallingAutoConfiguration 对 ToolCallbackResolver 是构造器强注入
 * （2.0.2 源码实证），真实模型 starter 的 ChatModel 又依赖 ToolCallingManager。
 * v3 的"构造即全扫"在真实 starter 在场时把
 * "resolver→registry→scanner→LLMCore→ChatModel→ToolCallingManager→resolver"
 * 闭合成创建期循环依赖，启动必炸。本钩子在所有单例（含 ChatModel/
 * ToolCallingManager）就位后才触发扫描，环在结构上不成立；
 * 启动期全扫、fail-fast、WARN 汇总三项语义全部保留。</p>
 *
 * <p><b>fail-fast 平移：</b>v3 在 gaiosArbitrationLlm bean 创建时检查
 * "容器无 ChatModel"并启动即炸；v4 该检查平移到本钩子（仅默认仲裁链——
 * 即 LLMCore bean 是我们的 {@link ChatModelLlmCore}——激活时才检查；
 * 用户自定义 SemanticScanner/LLMCore 接管仲裁通道时不检查，宿主可以
 * 完全没有 ChatModel）。时机仍在容器 refresh 内，启动失败语义不变。</p>
 */
public class ContainerToolScanHook implements SmartInitializingSingleton {

    private static final Logger log = LogManager.getLogger(ContainerToolScanHook.class);

    private final GaiosSecureToolCallbackResolver resolver;

    private final GaiosSecureToolRegistry registry;

    private final ObjectProvider<ChatModel> chatModelProvider;

    private final ObjectProvider<LLMCore> llmCoreProvider;

    public ContainerToolScanHook(GaiosSecureToolCallbackResolver resolver,
                                 GaiosSecureToolRegistry registry,
                                 ObjectProvider<ChatModel> chatModelProvider,
                                 ObjectProvider<LLMCore> llmCoreProvider) {
        this.resolver = resolver;
        this.registry = registry;
        this.chatModelProvider = chatModelProvider;
        this.llmCoreProvider = llmCoreProvider;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // 仅默认仲裁链才依赖宿主 ChatModel：做在位 fail-fast + 标定护栏
        LLMCore llm = llmCoreProvider.getIfAvailable();
        if (llm instanceof ChatModelLlmCore) {
            ChatModel chatModel = chatModelProvider.getIfAvailable();
            if (chatModel == null) {
                throw new IllegalStateException(
                        "[G-AIOS] Stage III 仲裁默认复用宿主 ChatModel，但容器中没有 ChatModel bean。修复路径："
                                + "①引入任一 spring-ai 模型 starter（如 spring-ai-starter-model-deepseek）；"
                                + "②或自定义 LLMCore bean 接管仲裁通道；"
                                + "③或 gaios.scanner.enabled=false 关闭本模块");
            }
            // 标定护栏（2026-11-08 拍板）：仲裁模型来源必须显式可见。
            // 22/22 标定钉定 deepseek-v4-flash + prompt v2.1；宿主模型不同则判定基线可能漂移
            log.warn("[G-AIOS] Stage III 仲裁 LLM 复用宿主 ChatModel [{}]。"
                            + "提醒：22/22 标定基线钉定 deepseek-v4-flash + prompt v2.1——宿主模型若非该型号，"
                            + "仲裁判定可能漂移，建议按 CalibrationRunner 重跑标定；"
                            + "要锁定独立仲裁通道，自定义 LLMCore bean 即可整体替换本通道",
                    chatModel.getClass().getName());
        }

        // 全扫：三阶段安检在这里跑完，被拦的当场立墓碑
        resolver.scanAll();

        // 启动汇总：隔离 > 0 必须 WARN 顶到运维视野（静默隔离 = 审计缺口）
        if (resolver.quarantinedCount() > 0) {
            log.warn("[G-AIOS] 容器工具安检完成：放行 {} 个，隔离 {} 个。隔离明细：{}",
                    resolver.approvedCount(), resolver.quarantinedCount(), registry.quarantined());
        } else {
            log.info("[G-AIOS] 容器工具安检完成：全部放行（{} 个）", resolver.approvedCount());
        }
    }
}
