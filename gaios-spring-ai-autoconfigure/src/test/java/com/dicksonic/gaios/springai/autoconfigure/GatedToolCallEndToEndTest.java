package com.dicksonic.gaios.springai.autoconfigure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.dicksonic.gaios.springai.access.GaiosApprovalToolCallingManager;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端：scripted ChatModel + 真实 ChatClient 自动装配链。
 * 证明"引依赖即生效"——不做任何手工接线，审批门已在工具执行路径上。
 *
 * 剧本：模型第一轮请求调用工具 → advisor 执行（过门）→ 第二轮模型看到
 * 工具响应（真实结果或审批拒绝）→ 返回文本收尾。
 */
class GatedToolCallEndToEndTest {

    /** 剧本化 ChatModel：按队列依次返回响应，记录每次收到的 Prompt 供断言 */
    static final class ScriptedChatModel implements ChatModel {
        final Queue<ChatResponse> script = new ArrayDeque<>();
        final List<Prompt> seenPrompts = new ArrayList<>();

        @Override
        public ChatOptions getOptions() {
            // 关键：框架从 chatModel.getOptions() 决定工具支持面——默认实现返回裸
            // ChatOptions，工具回调不会进 options，ToolCallingAdvisor 直接跳过工具循环。
            // 真实模型返回实现 ToolCallingChatOptions 的模型选项，桩必须同样逼真。
            return ToolCallingChatOptions.builder().build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            seenPrompts.add(prompt);
            ChatResponse next = script.poll();
            if (next == null) {
                throw new IllegalStateException("剧本耗尽：模型被调用了超出预期的次数");
            }
            return next;
        }
    }

    private static ChatResponse toolCallRound(String toolName) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", toolName, "\"基坑\"")))
                .build())));
    }

    private static ChatResponse textRound(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ToolCallback tool(String name, AtomicInteger counter) {
        return FunctionToolCallback.builder(name, (Function<String, String>) q -> {
                    counter.incrementAndGet();
                    return "查询结果:基坑边坡 1:1.5";
                })
                .description(name + " 演示工具")
                .inputType(String.class)
                .build();
    }

    private static ToolResponseMessage lastToolResponse(Prompt prompt) {
        return prompt.getInstructions()
                .stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("第二轮 Prompt 中没有 ToolResponseMessage"));
    }

    private ApplicationContextRunner e2eRunner(ScriptedChatModel model) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        GaiosAccessAutoConfiguration.class,
                        ToolCallingAutoConfiguration.class,
                        ChatClientAutoConfiguration.class))
                .withBean(ChatModel.class, () -> model);
    }

    @Test
    void L3工具_执行被拦_模型收到审批拒绝并收尾() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.script.add(toolCallRound("delete_hazard_records"));
        model.script.add(textRound("审批未通过，无法执行删除操作"));
        AtomicInteger executed = new AtomicInteger();
        ToolCallback dangerous = tool("delete_hazard_records", executed);

        e2eRunner(model)
                .withPropertyValues("gaios.access.tool-levels.delete_hazard_records=L3")
                .run(context -> {
                    ChatClient client = context.getBean(ChatClient.Builder.class).build();
                    String answer = client.prompt()
                            .user("把隐患台账全部删掉")
                            .tools(dangerous)
                            .call()
                            .content();

                    assertEquals(0, executed.get(), "L3 工具不得执行");
                    assertEquals("审批未通过，无法执行删除操作", answer);
                    assertEquals(2, model.seenPrompts.size(), "剧本两轮：工具请求轮 + 收尾轮");

                    ToolResponseMessage toolResponse = lastToolResponse(model.seenPrompts.get(1));
                    assertEquals("call_1", toolResponse.getResponses().get(0).id());
                    assertTrue(toolResponse.getResponses().get(0).responseData()
                                    .contains(GaiosApprovalToolCallingManager.DENY_MARKER),
                            "模型第二轮应看到审批拒绝响应");
                });
    }

    @Test
    void L1工具_真实执行_模型拿到真实结果() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.script.add(toolCallRound("query_regulations"));
        model.script.add(textRound("基坑边坡放坡比例不应大于 1:1.5"));
        AtomicInteger executed = new AtomicInteger();
        ToolCallback query = tool("query_regulations", executed);

        e2eRunner(model)
                .withPropertyValues("gaios.access.tool-levels.query_regulations=L1")
                .run(context -> {
                    ChatClient client = context.getBean(ChatClient.Builder.class).build();
                    String answer = client.prompt()
                            .user("基坑边坡有什么要求")
                            .tools(query)
                            .call()
                            .content();

                    assertEquals(1, executed.get(), "L1 工具应真实执行一次");
                    assertEquals("基坑边坡放坡比例不应大于 1:1.5", answer);

                    ToolResponseMessage toolResponse = lastToolResponse(model.seenPrompts.get(1));
                    assertTrue(toolResponse.getResponses().get(0).responseData().contains("查询结果"),
                            "模型第二轮应看到真实工具结果");
                });
    }
}
