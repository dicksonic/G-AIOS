package com.dicksonic.gaios.springai.autoconfigure;

import com.dicksonic.gaios.springai.access.ApprovalDecision;
import com.dicksonic.gaios.springai.access.ApprovalGate;
import com.dicksonic.gaios.springai.access.ApprovalRequest;
import com.dicksonic.gaios.springai.access.ConfirmationChannel;
import com.dicksonic.gaios.springai.access.GaiosApprovalToolCallingManager;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 自动装配行为：接管 / 开关退位 / 用户自定义优先 / 属性绑定 / 确认通道注入。
 * 只装 ToolCallingAutoConfiguration（无需 ChatModel），验证 bean 链而非模型行为。
 *
 * 断言一律用裸 JUnit：Boot 4 的 spring-boot-test 不再传递 AssertJ，
 * 不为一个测试文件引依赖（与"先手写再引库"同理）。
 */
class GaiosAccessAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    GaiosAccessAutoConfiguration.class, ToolCallingAutoConfiguration.class));

    private static <T> void assertSingleBeanOfType(
            org.springframework.context.ApplicationContext context, Class<T> type) {
        assertEquals(1, context.getBeanNamesForType(type).length,
                "容器中应有且仅有一个 " + type.getSimpleName());
    }

    @Test
    void 默认接管_ToolCallingManager被换成带门实例_框架默认退位() {
        runner.run(context -> {
            assertSingleBeanOfType(context, ToolCallingManager.class);
            assertInstanceOf(GaiosApprovalToolCallingManager.class,
                    context.getBean(ToolCallingManager.class));
            assertSingleBeanOfType(context, ApprovalGate.class);
        });
    }

    @Test
    void 开关关闭_审批链全退_框架默认manager接管() {
        runner.withPropertyValues("gaios.access.enabled=false")
                .run(context -> {
                    assertSingleBeanOfType(context, ToolCallingManager.class);
                    assertInstanceOf(DefaultToolCallingManager.class,
                            context.getBean(ToolCallingManager.class));
                    assertEquals(0, context.getBeanNamesForType(ApprovalGate.class).length,
                            "开关关闭时不应有 ApprovalGate bean");
                });
    }

    @Test
    void 用户自定义ApprovalGate_默认门退位_manager仍带门() {
        ApprovalGate custom = request -> ApprovalDecision.deny("全局拒绝");
        runner.withBean(ApprovalGate.class, () -> custom)
                .run(context -> {
                    assertSame(custom, context.getBean(ApprovalGate.class));
                    assertInstanceOf(GaiosApprovalToolCallingManager.class,
                            context.getBean(ToolCallingManager.class));
                });
    }

    @Test
    void 用户自定义ToolCallingManager_我们的装饰器退位() {
        ToolCallingManager userManager = ToolCallingManager.builder().build();
        runner.withBean(ToolCallingManager.class, () -> userManager)
                .run(context -> {
                    assertSingleBeanOfType(context, ToolCallingManager.class);
                    assertSame(userManager, context.getBean(ToolCallingManager.class));
                });
    }

    @Test
    void 分级属性绑定生效() {
        runner.withPropertyValues(
                        "gaios.access.tool-levels.query_regulations=L1",
                        "gaios.access.tool-levels.delete_hazard_records=L3")
                .run(context -> {
                    ApprovalGate gate = context.getBean(ApprovalGate.class);
                    assertTrue(gate.evaluate(new ApprovalRequest("query_regulations", "{}")).approved());
                    ApprovalDecision denied = gate.evaluate(new ApprovalRequest("delete_hazard_records", "{}"));
                    assertFalse(denied.approved());
                    assertNotNull(denied.reason());
                    assertTrue(denied.reason().contains("L3"));
                    // 未配置工具走默认 L1
                    assertTrue(gate.evaluate(new ApprovalRequest("other_tool", "{}")).approved());
                });
    }

    @Test
    void 确认通道bean被注入门() {
        runner.withBean(ConfirmationChannel.class, () -> request -> true)
                .withPropertyValues("gaios.access.tool-levels.draft_notice=L2")
                .run(context -> {
                    ApprovalGate gate = context.getBean(ApprovalGate.class);
                    // 通道确认通过 → 放行；若无通道注入此用例会 fail-closed 拒绝
                    assertTrue(gate.evaluate(new ApprovalRequest("draft_notice", "{}")).approved());
                });
    }
}
