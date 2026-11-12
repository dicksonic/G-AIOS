package com.dicksonic.gaios.springai.scanner.autoconfigure;

import com.dicksonic.gaios.llm.LLMCore;
import com.dicksonic.gaios.security.scanner.SemanticScanner;
import com.dicksonic.gaios.security.scanner.engine.E5EmbeddingEngine;
import com.dicksonic.gaios.security.scanner.enums.ScanVerdict;
import com.dicksonic.gaios.springai.GaiosSecureToolRegistry;
import com.dicksonic.gaios.springai.scanner.GaiosSecureToolCallbackResolver;
import com.dicksonic.gaios.springai.scanner.QuarantinedToolCallback;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 自动装配行为：默认接管 / 开关退位 / auto-scan 旋钮 / 启动隔离告警 /
 * 用户自定义退位 / 与框架同场竞争 / models-dir 缺失 fail-fast。
 *
 * 安检链（E5/LLMCore/SemanticScanner）属重资源装配：测试一律以桩
 * SemanticScanner 令整条嵌套链退位，不触 DJL/ONNX 与任何真实模型。
 * 断言一律裸 JUnit（Boot 4 的 spring-boot-test 不带 AssertJ 传递依赖——
 * 虽然本模块 pom 已加 assertj-core 供失败上下文代理编译，断言风格与 M2-6 对齐）。
 */
class GaiosScannerAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GaiosScannerAutoConfiguration.class));

    /** 判定桩：evil 开头判毒（复用 resolver 单测的语义） */
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

    private static ToolCallback stubTool(String name, String description) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(description).inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return "REAL_EXECUTION";
            }
        };
    }

    private static <T> void assertSingleBeanOfType(
            org.springframework.context.ApplicationContext context, Class<T> type) {
        assertEquals(1, context.getBeanNamesForType(type).length,
                "容器中应有且仅有一个 " + type.getSimpleName());
    }

    @Test
    void 默认装配_注册中心与带安检resolver就位_安检链内部件退位() {
        runner.withBean(SemanticScanner.class, GaiosScannerAutoConfigurationTest::stubScanner)
                .withBean(ToolCallback.class, () -> stubTool("query_data", "查询测试数据"))
                .run(context -> {
                    assertSingleBeanOfType(context, GaiosSecureToolRegistry.class);
                    assertSingleBeanOfType(context, ToolCallbackResolver.class);
                    assertInstanceOf(GaiosSecureToolCallbackResolver.class,
                            context.getBean(ToolCallbackResolver.class));
                    assertNotNull(context.getBean(ToolCallbackResolver.class).resolve("query_data"));
                    // 用户桩接管判定链 → E5/LLMCore 一个都不建（不抢不猜）
                    assertEquals(0, context.getBeanNamesForType(E5EmbeddingEngine.class).length);
                    assertEquals(0, context.getBeanNamesForType(LLMCore.class).length);
                });
    }

    @Test
    void 总开关关闭_全部bean不装配() {
        runner.withPropertyValues("gaios.scanner.enabled=false")
                .run(context -> {
                    assertEquals(0, context.getBeanNamesForType(GaiosSecureToolRegistry.class).length);
                    assertEquals(0, context.getBeanNamesForType(ToolCallbackResolver.class).length);
                    assertEquals(0, context.getBeanNamesForType(GaiosScannerProperties.class).length);
                });
    }

    @Test
    void autoScan关闭_注册中心在_resolver不接管() {
        runner.withPropertyValues("gaios.scanner.auto-scan=false")
                .withBean(SemanticScanner.class, GaiosScannerAutoConfigurationTest::stubScanner)
                .run(context -> {
                    // 安检链 bean 照常装配（手工送检入口保留），框架 resolver 不被顶掉
                    assertSingleBeanOfType(context, GaiosSecureToolRegistry.class);
                    assertEquals(0, context.getBeanNamesForType(ToolCallbackResolver.class).length);
                });
    }

    @Test
    void 被拦工具_启动即隔离_墓碑与审计条目就位() {
        runner.withBean(SemanticScanner.class, GaiosScannerAutoConfigurationTest::stubScanner)
                .withBean(ToolCallback.class, () -> stubTool("evil_sync", "同步测试数据"))
                .run(context -> {
                    ToolCallback resolved = context.getBean(ToolCallbackResolver.class).resolve("evil_sync");
                    assertInstanceOf(QuarantinedToolCallback.class, resolved);
                    GaiosSecureToolRegistry registry = context.getBean(GaiosSecureToolRegistry.class);
                    assertEquals(1, registry.quarantined().size());
                    assertEquals("evil_sync", registry.quarantined().get(0).toolName());
                });
    }

    @Test
    void 用户自定义resolver_我们的退位() {
        ToolCallbackResolver userResolver = toolName -> null;
        runner.withBean(SemanticScanner.class, GaiosScannerAutoConfigurationTest::stubScanner)
                .withBean(ToolCallbackResolver.class, () -> userResolver)
                .run(context -> {
                    assertSingleBeanOfType(context, ToolCallbackResolver.class);
                    assertSame(userResolver, context.getBean(ToolCallbackResolver.class));
                    // 注册中心仍在：用户只是接管解析面，安检链与隔离区不动
                    assertSingleBeanOfType(context, GaiosSecureToolRegistry.class);
                });
    }

    @Test
    void 与框架同场_我们的resolver顶掉框架默认_框架ToolCallingManager照常装配() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        GaiosScannerAutoConfiguration.class, ToolCallingAutoConfiguration.class))
                .withBean(SemanticScanner.class, GaiosScannerAutoConfigurationTest::stubScanner)
                .withBean(ToolCallback.class, () -> stubTool("query_data", "查询测试数据"))
                .run(context -> {
                    // beforeName 生效：框架 ToolCallbackResolver 的 @ConditionalOnMissingBean 退位
                    assertSingleBeanOfType(context, ToolCallbackResolver.class);
                    assertInstanceOf(GaiosSecureToolCallbackResolver.class,
                            context.getBean(ToolCallbackResolver.class));
                    // 框架 ToolCallingManager 照常装配（注入的正是我们的 resolver）
                    assertSingleBeanOfType(context, ToolCallingManager.class);
                    assertNotNull(context.getBean(ToolCallbackResolver.class).resolve("query_data"));
                });
    }

    /**
     * 2026-11-12 生产事故复现拓扑（阶段 B 真机首跑炸出）：框架
     * ToolCallingAutoConfiguration 对 ToolCallbackResolver 是构造器强注入
     * （2.0.2 sources 实证），真实模型 starter 的 ChatModel 又依赖
     * ToolCallingManager。v3"构造即全扫"在此拓扑下闭环成创建期循环依赖。
     * v4 = 懒解析 ChatModel + 单例就绪后扫描，本拓扑必须绿。
     *
     * <p>注意：桩 SemanticScanner 令默认链退位，本用例锁定的是
     * "manager 急切注入 resolver + ChatModel 依赖 manager"的接线拓扑无环；
     * 默认链（ChatModelLlmCore）创建期不触碰 ChatModel 的契约由
     * ChatModelLlmCoreTest 的懒解析用例钉死——两层合起来覆盖事故根因。
     * （E5EmbeddingEngine 是具体类且构造即加载 ONNX，测试侧无法替身，
     * 默认链端到端拓扑回归归阶段 B 真机 V1-V5。）</p>
     */
    @Test
    void 真实starter拓扑_manager急切注入resolver_不发生循环依赖_扫描在单例就绪后完成() {
        runner.withUserConfiguration(RealStarterTopologyConfig.class)
                .withBean(SemanticScanner.class, GaiosScannerAutoConfigurationTest::stubScanner)
                .withBean(ToolCallback.class, () -> stubTool("query_data", "查询测试数据"))
                .run(context -> {
                    assertNull(context.getStartupFailure(),
                            "manager 急切注入 resolver 的拓扑下启动不得失败");
                    assertSingleBeanOfType(context, ChatModel.class);
                    // SmartInitializingSingleton 钩子在 refresh 内已跑完：解析立即可用
                    assertNotNull(context.getBean(ToolCallbackResolver.class).resolve("query_data"));
                });
    }

    /** 复现生产拓扑的配置：与框架/真实 starter 的依赖方向逐边对齐 */
    @Configuration(proxyBeanMethods = false)
    static class RealStarterTopologyConfig {

        /** 框架 ToolCallingManager 的替身：构造器强注入 resolver（2.0.2 同款） */
        @Bean
        FakeToolCallingManager fakeToolCallingManager(ToolCallbackResolver resolver) {
            return new FakeToolCallingManager(resolver);
        }

        /** 真实 starter 的 ChatModel 替身：依赖 ToolCallingManager（DeepSeekChatModel 同款） */
        @Bean
        ChatModel fakeChatModel(FakeToolCallingManager manager) {
            return prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))));
        }
    }

    static final class FakeToolCallingManager {
        @SuppressWarnings("unused")
        final ToolCallbackResolver resolver;

        FakeToolCallingManager(ToolCallbackResolver resolver) {
            this.resolver = resolver;
        }
    }

    @Test
    void 安检链生效但modelsDir缺失_启动failFast含修复指引() {
        // 无用户 SemanticScanner → 嵌套链激活 → E5 bean 创建时 fail-fast（不触 ONNX）
        runner.run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure, "安检链生效但 models-dir 缺失时必须启动失败");
            assertTrue(failureChainContains(failure, "gaios.scanner.models-dir"),
                    "失败信息必须指向缺失的配置项，实际: " + failure);
        });
    }

    /** 顺 getCause 链找关键词（启动失败会被 Bean 创建异常层层包装） */
    private static boolean failureChainContains(Throwable failure, String keyword) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (String.valueOf(t.getMessage()).contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
