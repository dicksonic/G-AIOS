package com.dicksonic.gaios.springai.scanner.autoconfigure;

import com.dicksonic.gaios.llm.LLMCore;
import com.dicksonic.gaios.security.scanner.SemanticScanner;
import com.dicksonic.gaios.security.scanner.engine.E5EmbeddingEngine;
import com.dicksonic.gaios.security.scanner.impl.E5SemanticScanner;
import com.dicksonic.gaios.security.scanner.impl.LlmSemanticScanner;
import com.dicksonic.gaios.springai.GaiosSecureToolRegistry;
import com.dicksonic.gaios.springai.scanner.ChatModelLlmCore;
import com.dicksonic.gaios.springai.scanner.ContainerToolScanHook;
import com.dicksonic.gaios.springai.scanner.GaiosSecureToolCallbackResolver;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * G-AIOS 安检链自动装配（M2-7）：让"引依赖即生效"覆盖三阶段安检全流程。
 *
 * <p><b>解决什么问题：</b>M2-5 交付的 {@link GaiosSecureToolRegistry} 是纯 Java
 * 装饰器——能用，但要宿主手工 boot core、手工收集容器工具、手工送检、手工
 * 替换框架 resolver。本自动装配把这套手工活全部收编：</p>
 *
 * <p><b>装配链（全部走官方条件化注入点，零 hack）：</b></p>
 * <ol>
 *   <li><b>安检链</b>（嵌套配置，用户自定义 SemanticScanner 则整体退位）：
 *       E5EmbeddingEngine（gaios.scanner.models-dir 驱动）→ LLMCore
 *       （默认复用宿主 ChatModel，启动 WARN 打印来源+标定提醒；自定义 LLMCore
 *       bean 即独立仲裁通道）→ SemanticScanner（E5 差分 + LLM 仲裁）；</li>
 *   <li><b>GaiosSecureToolRegistry</b>：安检注册中心 bean，隔离区审计可查；</li>
 *   <li><b>容器全扫</b>（gaios.scanner.auto-scan=true 时）：注册
 *       GaiosSecureToolCallbackResolver 为 ToolCallbackResolver bean，
 *       beforeName 先于 spring-ai 的 ToolCallingAutoConfiguration，后者
 *       {@code @ConditionalOnMissingBean} 自动退位；扫描由 ContainerToolScanHook
 *       在所有单例就绪后触发（v4：创建期扫描会与真实模型 starter 闭环成
 *       循环依赖），被拦的立墓碑+启动 WARN 汇总告警。</li>
 * </ol>
 *
 * <p><b>与 M2-6 审批门的衔接：</b>M2-6 的 GaiosApprovalToolCallingManager 以
 * ObjectProvider 惰性注入 ToolCallbackResolver——本模块在场时自动拿到带安检
 * 的实例，两模块无 import 关系、经容器自然合流。</p>
 *
 * <p><b>fail-fast 哲学（注册期 fail-closed 的装配层镜像）：</b>models-dir 缺失、
 * E5 模型加载失败、宿主无 ChatModel 可用——这三种"安检链半残"状态一律启动
 * 即炸并给出修复指引（前两者在 bean 创建期，ChatModel 检查在单例就绪钩子内，
 * 都在容器 refresh 内、启动失败语义一致），绝不允许带病的安检链悄悄放行。</p>
 */
@AutoConfiguration(beforeName = "org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration")
@ConditionalOnClass({SemanticScanner.class, ToolCallbackResolver.class})
@ConditionalOnProperty(prefix = "gaios.scanner", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(GaiosScannerProperties.class)
public class GaiosScannerAutoConfiguration {

    /**
     * 安检链默认装配：E5 → LLMCore → SemanticScanner。
     * 类级条件：用户自定义任何一个 SemanticScanner bean，本嵌套配置整体退位——
     * 用户显式接管判定链时，连 E5/LLMCore 都不替他建（不抢不猜）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(SemanticScanner.class)
    static class DefaultScannerConfiguration {

        /**
         * E5 嵌入引擎（Stage II 差分底座）。destroyMethod=close：ONNX session
         * 与 tokenizer 持有 native 资源，容器关闭时必须显式释放。
         */
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        E5EmbeddingEngine gaiosE5EmbeddingEngine(GaiosScannerProperties properties) {
            if (!StringUtils.hasText(properties.getModelsDir())) {
                throw new IllegalStateException(
                        "[G-AIOS] 安检链需要 E5 模型，但 gaios.scanner.models-dir 未配置。修复路径："
                                + "①配置 gaios.scanner.models-dir 指向 E5 模型目录（含 tokenizer.json "
                                + "与 model_quantized_base.onnx）；②或自定义 SemanticScanner bean 整体接管判定链；"
                                + "③或 gaios.scanner.enabled=false 关闭本模块");
            }
            Path modelDir = Path.of(properties.getModelsDir());
            try {
                return new E5EmbeddingEngine(modelDir);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "[G-AIOS] E5 模型加载失败（gaios.scanner.models-dir=" + modelDir.toAbsolutePath()
                                + "）。请确认目录存在且含 tokenizer.json 与 model_quantized_base.onnx", e);
            }
        }

        /**
         * 仲裁 LLM（Stage III）。默认复用宿主 ChatModel——宿主的模型配置（鉴权/
         * 代理/超时）零成本继承。自定义 LLMCore bean 则本 bean 退位=独立仲裁通道
         * （例如钉回标定基线的 DeepSeekCore），无需任何配置项。
         *
         * <p><b>v4 修复（2026-11-12）：创建期只存 Supplier，首次仲裁才取实例。</b>
         * 急切 getIfAvailable() 会把"resolver→registry→scanner→LLMCore→ChatModel
         * →框架 ToolCallingManager→resolver"闭合成创建期循环依赖（框架 manager 对
         * resolver 是构造器强注入，2.0.2 源码实证；真实模型 starter 的 ChatModel
         * 依赖 manager）。"容器无 ChatModel"的 fail-fast 与标定护栏 WARN 平移到
         * ContainerToolScanHook（单例就绪后执行，启动失败语义不变）。</p>
         */
        @Bean
        @ConditionalOnMissingBean(LLMCore.class)
        LLMCore gaiosArbitrationLlm(ObjectProvider<ChatModel> chatModelProvider) {
            return new ChatModelLlmCore(() -> {
                ChatModel chatModel = chatModelProvider.getIfAvailable();
                if (chatModel == null) {
                    // 正常到不了这里：ContainerToolScanHook 已在扫描前 fail-fast。
                    // 留着兜底：auto-scan=false（手工送检模式）下没有钩子，仲裁时
                    // 异常由 ChatModelLlmCore 按契约折算 "error" → UNKNOWN → fail-closed
                    throw new IllegalStateException(
                            "[G-AIOS] Stage III 仲裁默认复用宿主 ChatModel，但容器中没有 ChatModel bean。修复路径："
                                    + "①引入任一 spring-ai 模型 starter（如 spring-ai-starter-model-deepseek）；"
                                    + "②或自定义 LLMCore bean 接管仲裁通道；"
                                    + "③或 gaios.scanner.enabled=false 关闭本模块");
                }
                return chatModel;
            });
        }

        /**
         * Stage II/III 语义安检链：E5 差分 →（差分越阈值时）LLM 仲裁。
         * 阈值偏移 0.0 沿用 GaiosBootstrap/App 演示链路的标定实测取值。
         */
        @Bean
        @ConditionalOnMissingBean
        SemanticScanner gaiosSemanticScanner(E5EmbeddingEngine e5, LLMCore llm) {
            // E5SemanticScanner 构造器声明 throws Exception：毒/正常锚点向量在构造时
            // 一次性预计算（GaiosBootstrap.boot 自己声明抛出所以那边无感，这里必须收编）——
            // 锚点预计算失败=安检链半残，按本类 fail-fast 惯例启动即炸
            try {
                return new E5SemanticScanner(e5, new LlmSemanticScanner(llm), 0.0);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "[G-AIOS] Stage II 语义安检链装配失败（E5 锚点向量预计算异常）", e);
            }
        }
    }

    /**
     * 安检注册中心：隔离区审计（quarantined()）与手工送检入口。
     * 审计回调传 null → adapter 内置 LoggingToolCallAuditListener 兜底；
     * 生产接 call_logs 计量时自定义本 bean 或 ToolCallAuditListener 通道（M2-8 议）。
     */
    @Bean
    @ConditionalOnMissingBean
    GaiosSecureToolRegistry gaiosSecureToolRegistry(SemanticScanner scanner) {
        return new GaiosSecureToolRegistry(scanner, null);
    }

    /**
     * 容器全扫 + 带安检 resolver 接管（本模块的核心交付）。
     * 候选收集逻辑镜像 spring-ai ToolCallingAutoConfiguration 官方实现：
     * ToolCallback beans + 非 MCP 的 ToolCallbackProvider 工具清单；
     * MCP provider 同样排除（避免启动期过早 listTools() 触发远端初始化，
     * MCP 工具的执行层安检列入 M2-8 候选）。
     */
    @Bean
    @ConditionalOnMissingBean(ToolCallbackResolver.class)
    @ConditionalOnProperty(prefix = "gaios.scanner", name = "auto-scan", havingValue = "true", matchIfMissing = true)
    GaiosSecureToolCallbackResolver gaiosSecureToolCallbackResolver(
            GaiosSecureToolRegistry registry,
            List<ToolCallback> toolCallbacks,
            // 框架同款废弃变体：List<ToolCallbackProvider> 形态的老 bean 也纳入收集，保 parity
            ObjectProvider<List<ToolCallbackProvider>> toolCallbackProviderList,
            ObjectProvider<ToolCallbackProvider> toolCallbackProviders) {

        List<ToolCallback> candidates = new ArrayList<>(toolCallbacks);
        List<ToolCallbackProvider> allProviders = new ArrayList<>(
                toolCallbackProviderList.stream().flatMap(List::stream).toList());
        allProviders.addAll(toolCallbackProviders.stream().toList());
        allProviders.stream()
                .distinct()
                .filter(provider -> !isMcpToolCallbackProvider(ResolvableType.forInstance(provider)))
                .forEach(provider -> candidates.addAll(List.of(provider.getToolCallbacks())));

        // v4：此处只收集候选、不扫描——扫描由 gaiosContainerToolScan 钩子在
        // 所有单例就绪后触发（创建期扫描会闭环循环依赖，见 ContainerToolScanHook javadoc）。
        // 返回具体类而非 ToolCallbackResolver 接口：钩子需要按具体类型注入
        // （@Bean 方法返回接口时，Spring 类型预测不认具体类注入点）。
        return new GaiosSecureToolCallbackResolver(candidates, registry);
    }

    /**
     * 容器全扫触发钩（v4）：单例全部就绪后执行 ChatModel 在位检查
     * + 标定护栏 WARN + scanAll() + 汇总日志。启动期 fail-fast 语义不变
     * （SmartInitializingSingleton 仍在容器 refresh 内执行，异常=启动失败）。
     */
    @Bean
    @ConditionalOnBean(GaiosSecureToolCallbackResolver.class)   // 用户自定义 resolver 时我们的没注册，钩子一并退位
    @ConditionalOnProperty(prefix = "gaios.scanner", name = "auto-scan", havingValue = "true", matchIfMissing = true)
    ContainerToolScanHook gaiosContainerToolScan(GaiosSecureToolCallbackResolver resolver,
                                                 GaiosSecureToolRegistry registry,
                                                 ObjectProvider<ChatModel> chatModelProvider,
                                                 ObjectProvider<LLMCore> llmCoreProvider) {
        return new ContainerToolScanHook(resolver, registry, chatModelProvider, llmCoreProvider);
    }

    /**
     * MCP provider 判定（镜像 spring-ai ToolCallingAutoConfiguration 同名私有方法）：
     * 类路径里没有 mcp 构件时靠类型名逐级比对，不引编译期依赖。
     */
    private static boolean isMcpToolCallbackProvider(ResolvableType type) {
        if (type.getType().getTypeName().equals("org.springframework.ai.mcp.SyncMcpToolCallbackProvider")
                || type.getType().getTypeName().equals("org.springframework.ai.mcp.AsyncMcpToolCallbackProvider")) {
            return true;
        }
        ResolvableType superType = type.getSuperType();
        return superType != ResolvableType.NONE && isMcpToolCallbackProvider(superType);
    }
}
