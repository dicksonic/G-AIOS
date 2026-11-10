package com.dicksonic.gaios.springai.autoconfigure;

import com.dicksonic.gaios.springai.access.ApprovalGate;
import com.dicksonic.gaios.springai.access.ConfirmationChannel;
import com.dicksonic.gaios.springai.access.GaiosApprovalToolCallingManager;
import com.dicksonic.gaios.springai.access.RiskLevelApprovalGate;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * G-AIOS 审批关卡自动装配（Access Manager L1/L2/L3）。
 *
 * <p>装配链（全部走 Spring AI 官方条件化注入点，零 hack）：</p>
 * <ol>
 *   <li>本配置注册 {@code ToolCallingManager} bean = GaiosApprovalToolCallingManager
 *       （beforeName 先于 spring-ai 的 ToolCallingAutoConfiguration，
 *       后者 @ConditionalOnMissingBean 自动退位）；</li>
 *   <li>ChatClientAutoConfiguration 的 ToolCallingAdvisor.Builder bean 直接注入
 *       容器中的 ToolCallingManager——即我们的带门实例；</li>
 *   <li>所有经 ChatClient 的工具调用在执行前过 ApprovalGate。</li>
 * </ol>
 *
 * <p>退位语义：用户自定义 ToolCallingManager 或 ApprovalGate bean 均可整体替换
 * （@ConditionalOnMissingBean）；gaios.access.enabled=false 全链关闭。</p>
 */
@AutoConfiguration(beforeName = "org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration")
@ConditionalOnClass(ToolCallingManager.class)
@ConditionalOnProperty(prefix = "gaios.access", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(GaiosAccessProperties.class)
public class GaiosAccessAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ApprovalGate gaiosApprovalGate(GaiosAccessProperties properties,
            ObjectProvider<ConfirmationChannel> confirmationChannel) {
        return new RiskLevelApprovalGate(properties.getToolLevels(), properties.getDefaultLevel(),
                confirmationChannel.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    ToolCallingManager gaiosToolCallingManager(ApprovalGate gate,
            ObjectProvider<ToolCallbackResolver> toolCallbackResolver,
            ObjectProvider<ToolExecutionExceptionProcessor> exceptionProcessor,
            ObjectProvider<ObservationRegistry> observationRegistry) {
        // 委托链复用框架装配件：resolver/processor 由 spring-ai 的 ToolCallingAutoConfiguration
        // 提供（bean 定义注册先后不影响 DI——实例化时才按需创建），缺席则用 builder 内置默认。
        var builder = ToolCallingManager.builder()
            .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP));
        toolCallbackResolver.ifAvailable(builder::toolCallbackResolver);
        exceptionProcessor.ifAvailable(builder::toolExecutionExceptionProcessor);
        return new GaiosApprovalToolCallingManager(builder.build(), gate);
    }
}
