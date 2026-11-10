package com.dicksonic.gaios.springai.access;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 审批关卡 ToolCallingManager 装饰器：Spring AI 工具执行的唯一闸口。
 *
 * <p><b>为什么拦在这，而不是 ToolCallingAdvisor hook</b>（2.0.2 源码实证）：
 * advisor 的 doBeforeCall 在模型调用前、doAfterCall 在模型响应后——
 * 两个 hook 都拿不到"工具即将执行"这个时刻；hook 返回后 advisor 立刻调
 * {@code toolCallingManager.executeToolCalls(...)}。执行闸口就是 ToolCallingManager 本身。
 * 且 ChatClientAutoConfiguration 的 ToolCallingAdvisor.Builder bean 直接注入容器中的
 * ToolCallingManager bean——装饰它即对全部 ChatClient 全局生效，零 advisor 子类化。</p>
 *
 * <p><b>放行语义零重写</b>：批准的调用逐个以"单调用 ChatResponse"委托给框架
 * DefaultToolCallingManager 执行——观测/限额/异常处理/工具解析/returnDirect 判定
 * 全部复用框架原路径，本类只做两件事：判定（ApprovalGate）与结果拼装
 * （保持每个 toolCall 都有对应 ToolResponse，历史中的 assistant message 原样保留
 * 全部调用，不破坏模型侧 tool_call ↔ tool response 的配对约束）。</p>
 *
 * <p><b>已知限制</b>：框架的"单批次总调用数"限额（maxTotalToolCalls）在逐调用委托下
 * 弱化为每次委托只见一个调用；按工具的限额（含历史计数）不受影响。
 * 安全语义不依赖该限额，故可接受；若未来依赖批次限额，需改整批委托+预判定。</p>
 */
public final class GaiosApprovalToolCallingManager implements ToolCallingManager {

    private static final Logger log = LogManager.getLogger(GaiosApprovalToolCallingManager.class);

    /** 拒绝响应中的机器可读标记，模型可据此识别"这是审批拒绝而非业务错误" */
    public static final String DENY_MARKER = "APPROVAL_DENIED";

    private final ToolCallingManager delegate;
    private final ApprovalGate gate;

    public GaiosApprovalToolCallingManager(ToolCallingManager delegate, ApprovalGate gate) {
        if (delegate == null || gate == null) {
            throw new IllegalArgumentException("delegate 与 gate 均不能为空");
        }
        this.delegate = delegate;
        this.gate = gate;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        Optional<Generation> toolCallGeneration = chatResponse.getResults()
            .stream()
            .filter(g -> g.getOutput().hasToolCalls())
            .findFirst();
        if (toolCallGeneration.isEmpty()) {
            // 无工具调用：与框架行为一致，原样委托
            return delegate.executeToolCalls(prompt, chatResponse);
        }
        AssistantMessage assistantMessage = toolCallGeneration.get().getOutput();

        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        boolean anyExecuted = false;
        boolean aggReturnDirect = true;
        for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
            ApprovalDecision decision = gate.evaluate(new ApprovalRequest(toolCall.name(), toolCall.arguments()));
            if (!decision.approved()) {
                log.warn("[审批门] 拒绝执行 {}: {}", toolCall.name(), decision.reason());
                responses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(),
                        denialJson(toolCall.name(), decision.reason())));
                continue;
            }
            ToolExecutionResult single = executeOneApproved(prompt, assistantMessage, toolCall);
            responses.addAll(lastToolResponseMessage(single).getResponses());
            anyExecuted = true;
            // returnDirect 聚合与框架规则一致：全部被批准调用的结果取 &&
            // （拒绝的调用未执行，不参与聚合——与框架限额中断路径的先例一致）
            aggReturnDirect &= single.returnDirect();
        }

        List<Message> history = new ArrayList<>(prompt.getInstructions());
        history.add(assistantMessage);
        history.add(ToolResponseMessage.builder().responses(responses).build());
        return ToolExecutionResult.builder()
            .conversationHistory(history)
            .returnDirect(anyExecuted && aggReturnDirect)
            .build();
    }

    /**
     * 单个已批准调用的执行：构造只含该调用的 ChatResponse 委托框架执行。
     * 观测/限额/异常处理/工具解析/returnDirect 判定全部走框架原路径。
     */
    private ToolExecutionResult executeOneApproved(Prompt prompt, AssistantMessage source,
            AssistantMessage.ToolCall toolCall) {
        AssistantMessage single = AssistantMessage.builder()
            .content(source.getText())
            .properties(source.getMetadata())
            .toolCalls(List.of(toolCall))
            .build();
        ChatResponse singleResponse = new ChatResponse(List.of(new Generation(single)));
        return delegate.executeToolCalls(prompt, singleResponse);
    }

    /** 取单调用委托产物中的 ToolResponseMessage（历史末位） */
    private static ToolResponseMessage lastToolResponseMessage(ToolExecutionResult result) {
        Message last = result.conversationHistory().get(result.conversationHistory().size() - 1);
        if (!(last instanceof ToolResponseMessage toolResponseMessage)) {
            // 框架契约内不应发生；真发生了必须炸出来，不能静默吞成"审批已通过但没结果"
            throw new IllegalStateException(
                    "单调用委托未返回 ToolResponseMessage，框架行为异常，末位消息类型: " + last.getMessageType());
        }
        return toolResponseMessage;
    }

    /** 拒绝响应：回喂模型的 JSON，措辞明确告知"审批拒绝、别再重试" */
    private static String denialJson(String toolName, String reason) {
        return "{\"error\":\"" + DENY_MARKER + "\",\"tool\":\"" + escapeJson(toolName) + "\",\"reason\":\""
                + escapeJson(reason) + "。该调用被安全审批策略拦截，请勿重试，并如实告知用户审批未通过。\"}";
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
