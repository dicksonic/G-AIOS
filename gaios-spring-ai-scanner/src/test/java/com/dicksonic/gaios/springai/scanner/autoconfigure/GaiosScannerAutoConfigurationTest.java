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
import org.springframework.boot.autoconfigure.AutoConfigurations;
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
