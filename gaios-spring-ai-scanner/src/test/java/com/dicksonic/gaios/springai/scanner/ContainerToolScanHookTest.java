package com.dicksonic.gaios.springai.scanner;

import com.dicksonic.gaios.llm.LLMCore;
import com.dicksonic.gaios.llm.Message;
import com.dicksonic.gaios.security.scanner.SemanticScanner;
import com.dicksonic.gaios.security.scanner.enums.ScanVerdict;
import com.dicksonic.gaios.springai.GaiosSecureToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContainerToolScanHook（v4）：ChatModel 在位 fail-fast 的触发口径 +
 * 扫描触发 + 汇总日志前置状态。全程桩，不触 Spring 容器与真实模型。
 */
class ContainerToolScanHookTest {

    /** evil 开头判毒的判定桩（与各测试同语义） */
    private static SemanticScanner stubScanner() {
        return new SemanticScanner() {
            @Override
            public ScanVerdict judge(String toolName, String description) {
                return toolName.startsWith("evil") ? ScanVerdict.MALICIOUS : ScanVerdict.SAFE;
            }

            @Override
            public String engineName() {
                return "stub";
            }
        };
    }

    private static ToolCallback stubTool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("测试工具").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return "REAL_EXECUTION";
            }
        };
    }

    /** 最小 ObjectProvider 桩：只实现测试用到的两个方法 */
    private static <T> ObjectProvider<T> providerOf(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                if (instance == null) {
                    throw new IllegalStateException("stub: no instance");
                }
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }
        };
    }

    private static ChatModel stubChatModel() {
        return prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
    }

    private static GaiosSecureToolCallbackResolver resolverOf(List<ToolCallback> candidates,
                                                              GaiosSecureToolRegistry registry) {
        return new GaiosSecureToolCallbackResolver(candidates, registry);
    }

    @Test
    void 默认链_无ChatModel_启动failFast含修复指引() {
        // 默认仲裁链（LLMCore 是我们的 ChatModelLlmCore）激活但宿主无 ChatModel：
        // 半残安检链绝不允许悄悄放行——钩子在单例就绪后炸，启动失败语义与 v3 一致
        GaiosSecureToolRegistry registry = new GaiosSecureToolRegistry(stubScanner(), null);
        GaiosSecureToolCallbackResolver resolver = resolverOf(List.of(), registry);
        LLMCore defaultChainLlm = new ChatModelLlmCore(() -> {
            throw new IllegalStateException("不应走到这里：钩子应先炸");
        });
        ContainerToolScanHook hook = new ContainerToolScanHook(resolver, registry,
                providerOf(null), providerOf(defaultChainLlm));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                hook::afterSingletonsInstantiated);
        assertTrue(ex.getMessage().contains("没有 ChatModel"), "失败信息必须指向缺失的 ChatModel，实际: " + ex.getMessage());
    }

    @Test
    void 默认链_有ChatModel_扫描执行_毒工具隔离() {
        GaiosSecureToolRegistry registry = new GaiosSecureToolRegistry(stubScanner(), null);
        GaiosSecureToolCallbackResolver resolver = resolverOf(
                List.of(stubTool("query_data"), stubTool("evil_sync")), registry);
        ContainerToolScanHook hook = new ContainerToolScanHook(resolver, registry,
                providerOf(stubChatModel()),
                providerOf(new ChatModelLlmCore(stubChatModel())));

        hook.afterSingletonsInstantiated();

        assertNotNull(resolver.resolve("query_data"));
        assertInstanceOf(QuarantinedToolCallback.class, resolver.resolve("evil_sync"));
        assertEquals(1, registry.quarantined().size());
    }

    @Test
    void 用户自定义LLMCore_无ChatModel不检查_扫描照常() {
        // 独立仲裁通道（如钉回标定基线的 DeepSeekCore）：宿主可以完全没有 ChatModel
        GaiosSecureToolRegistry registry = new GaiosSecureToolRegistry(stubScanner(), null);
        GaiosSecureToolCallbackResolver resolver = resolverOf(List.of(stubTool("query_data")), registry);
        LLMCore userLlm = messages -> "safe";
        ContainerToolScanHook hook = new ContainerToolScanHook(resolver, registry,
                providerOf(null), providerOf(userLlm));

        assertDoesNotThrow(hook::afterSingletonsInstantiated);
        assertNotNull(resolver.resolve("query_data"));
    }

    @Test
    void 嵌套链退位_无LLMCore无ChatModel_扫描照常() {
        // 用户自定义 SemanticScanner 时 E5/LLMCore 都不建：钩子不做任何 ChatModel 检查
        GaiosSecureToolRegistry registry = new GaiosSecureToolRegistry(stubScanner(), null);
        GaiosSecureToolCallbackResolver resolver = resolverOf(List.of(stubTool("query_data")), registry);
        ContainerToolScanHook hook = new ContainerToolScanHook(resolver, registry,
                providerOf(null), providerOf(null));

        assertDoesNotThrow(hook::afterSingletonsInstantiated);
        assertNotNull(resolver.resolve("query_data"));
    }
}
