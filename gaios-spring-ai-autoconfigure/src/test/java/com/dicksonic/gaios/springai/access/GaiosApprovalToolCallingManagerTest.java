package com.dicksonic.gaios.springai.access;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 审批关卡 ToolCallingManager：判定与拼装的四个关键路径。
 * 委托层用真实 DefaultToolCallingManager（非桩）——单调用委托的正确性由此背书。
 */
class GaiosApprovalToolCallingManagerTest {

    private static ToolCallback functionTool(String name, AtomicInteger counter) {
        return FunctionToolCallback.builder(name, (Function<String, String>) q -> {
                    counter.incrementAndGet();
                    return "执行结果:" + q;
                })
                .description(name + " 的演示工具")
                .inputType(String.class)
                .build();
    }

    private static ChatResponse toolCallResponse(AssistantMessage.ToolCall... calls) {
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(calls)).build())));
    }

    private static Prompt promptWith(ToolCallback... callbacks) {
        return new Prompt(List.of(new UserMessage("演示请求")),
                ToolCallingChatOptions.builder().toolCallbacks(callbacks).build());
    }

    private static ToolResponseMessage lastToolResponse(ToolExecutionResult result) {
        Message last = result.conversationHistory().get(result.conversationHistory().size() - 1);
        assertInstanceOf(ToolResponseMessage.class, last);
        return (ToolResponseMessage) last;
    }

    @Test
    void 混合批次_放行真执行_拒绝合成响应_历史配对完整() {
        AtomicInteger queryCount = new AtomicInteger();
        AtomicInteger deleteCount = new AtomicInteger();
        ToolCallback query = functionTool("query_regulations", queryCount);
        ToolCallback delete = functionTool("delete_hazard_records", deleteCount);

        ApprovalGate gate = new RiskLevelApprovalGate(
                Map.of("delete_hazard_records", ToolRiskLevel.L3), ToolRiskLevel.L1, null);
        GaiosApprovalToolCallingManager manager = new GaiosApprovalToolCallingManager(
                ToolCallingManager.builder().build(), gate);

        ChatResponse response = toolCallResponse(
                new AssistantMessage.ToolCall("call_1", "function", "query_regulations", "\"基坑\""),
                new AssistantMessage.ToolCall("call_2", "function", "delete_hazard_records", "{}"));
        ToolExecutionResult result = manager.executeToolCalls(promptWith(query, delete), response);

        assertEquals(1, queryCount.get(), "L1 工具应真实执行");
        assertEquals(0, deleteCount.get(), "L3 工具不得执行");

        // 历史 = 原 instructions + 原始 assistant message（保留全部 toolCalls）+ 一条 ToolResponseMessage
        List<Message> history = result.conversationHistory();
        assertEquals(3, history.size());
        AssistantMessage inHistory = (AssistantMessage) history.get(1);
        assertEquals(2, inHistory.getToolCalls().size(), "历史中 assistant message 必须保留全部 toolCalls（配对约束）");

        ToolResponseMessage toolResponse = lastToolResponse(result);
        assertEquals(2, toolResponse.getResponses().size(), "每个 toolCall 都必须有对应 ToolResponse");
        assertEquals("call_1", toolResponse.getResponses().get(0).id());
        assertTrue(toolResponse.getResponses().get(0).responseData().contains("执行结果"));
        assertEquals("call_2", toolResponse.getResponses().get(1).id());
        assertTrue(toolResponse.getResponses().get(1).responseData()
                .contains(GaiosApprovalToolCallingManager.DENY_MARKER));

        assertFalse(result.returnDirect());
    }

    @Test
    void 全部拒绝_零执行_单条拒绝响应() {
        AtomicInteger deleteCount = new AtomicInteger();
        ToolCallback delete = functionTool("delete_hazard_records", deleteCount);
        ApprovalGate gate = new RiskLevelApprovalGate(
                Map.of("delete_hazard_records", ToolRiskLevel.L3), ToolRiskLevel.L1, null);
        GaiosApprovalToolCallingManager manager = new GaiosApprovalToolCallingManager(
                ToolCallingManager.builder().build(), gate);

        ChatResponse response = toolCallResponse(
                new AssistantMessage.ToolCall("call_1", "function", "delete_hazard_records", "{}"));
        ToolExecutionResult result = manager.executeToolCalls(promptWith(delete), response);

        assertEquals(0, deleteCount.get());
        ToolResponseMessage toolResponse = lastToolResponse(result);
        assertEquals(1, toolResponse.getResponses().size());
        assertTrue(toolResponse.getResponses().get(0).responseData()
                .contains(GaiosApprovalToolCallingManager.DENY_MARKER));
        assertFalse(result.returnDirect());
    }

    @Test
    void 全部放行_执行路径与框架一致() {
        AtomicInteger queryCount = new AtomicInteger();
        ToolCallback query = functionTool("query_regulations", queryCount);
        ApprovalGate gate = new RiskLevelApprovalGate(Map.of(), ToolRiskLevel.L1, null);
        GaiosApprovalToolCallingManager manager = new GaiosApprovalToolCallingManager(
                ToolCallingManager.builder().build(), gate);

        ChatResponse response = toolCallResponse(
                new AssistantMessage.ToolCall("call_1", "function", "query_regulations", "\"高支模\""));
        ToolExecutionResult result = manager.executeToolCalls(promptWith(query), response);

        assertEquals(1, queryCount.get());
        ToolResponseMessage toolResponse = lastToolResponse(result);
        assertEquals(1, toolResponse.getResponses().size());
        assertTrue(toolResponse.getResponses().get(0).responseData().contains("执行结果"));
    }

    @Test
    void 无工具调用_委托框架原语义_门不被触发() {
        // 框架契约：无工具调用时 DefaultToolCallingManager 抛 IllegalStateException。
        // 装饰器原样委托该分支——行为与裸框架一致，且审批门不应被触发。
        AtomicInteger gateCalls = new AtomicInteger();
        ApprovalGate countingGate = r -> {
            gateCalls.incrementAndGet();
            return ApprovalDecision.approve();
        };
        GaiosApprovalToolCallingManager manager = new GaiosApprovalToolCallingManager(
                ToolCallingManager.builder().build(), countingGate);

        ChatResponse plainText = new ChatResponse(List.of(new Generation(new AssistantMessage("没有工具调用"))));
        assertThrows(IllegalStateException.class, () -> manager.executeToolCalls(promptWith(), plainText));
        assertEquals(0, gateCalls.get(), "无工具调用时不应触发审批门");
    }
}
