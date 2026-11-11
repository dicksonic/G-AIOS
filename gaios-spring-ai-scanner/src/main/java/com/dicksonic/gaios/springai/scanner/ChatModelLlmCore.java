package com.dicksonic.gaios.springai.scanner;

import com.dicksonic.gaios.llm.LLMCore;
import com.dicksonic.gaios.llm.Message;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

/**
 * ChatModel → LLMCore 适配器：让 core 的三阶段安检链（Stage III 仲裁）
 * 复用宿主 Spring 应用里已有的 ChatModel，而不是另起一个 DeepSeekCore。
 *
 * <p><b>解决什么问题：</b>core 的 {@code LlmSemanticScanner} 只认 {@link LLMCore}
 * 接口（{@code chat(List<Message>) → String}），而 Spring AI 世界里的模型入口是
 * {@link ChatModel}（{@code call(Prompt) → ChatResponse}）。本类就是这两个
 * 接口之间的翻译官：左边收 core 的极简消息列表，右边调宿主的 ChatModel。</p>
 *
 * <p><b>数据流：</b></p>
 * <pre>
 *   LlmSemanticScanner.judge()
 *     → LLMCore.chat([Message("user", 仲裁 prompt)])     ← core 侧（实际只用 user 角色）
 *     → toSpringMessage 逐条翻译 role → UserMessage/SystemMessage/AssistantMessage
 *     → chatModel.call(new Prompt(messages))             ← Spring AI 侧
 *     → response.getResult().getOutput().getText()       ← 取回纯文本
 *     → 交还 LlmSemanticScanner 解析 {"verdict":...}
 * </pre>
 *
 * <p><b>契约对齐（重要）：</b>{@link LLMCore} 的契约是"永不抛异常、失败返回
 * 字符串 {@code "error"}"（{@code LlmSemanticScanner} 靠这个契约把仲裁故障
 * 归一为 UNKNOWN，进而注册期 fail-closed）。本类严守同一契约：任何异常、
 * 空响应、空文本都折算成 {@code "error"}，绝不把 Spring AI 侧的异常漏给 core。</p>
 *
 * <p><b>标定提醒：</b>22/22 标定钉定的是 deepseek-v4-flash + prompt v2.1。
 * 复用宿主 ChatModel 意味着仲裁模型由宿主决定——装配层
 * （GaiosScannerAutoConfiguration）会在启动时打 WARN 提醒这一点，
 * 并留有"自定义 LLMCore bean 整体替换"的独立仲裁通道口。</p>
 */
public class ChatModelLlmCore implements LLMCore {

    private static final Logger log = LogManager.getLogger(ChatModelLlmCore.class);

    /**
     * LLMCore 失败约定值，与 DeepSeekCore 的失败返回保持字面一致：
     * 上游 LlmSemanticScanner 解析失败原文会归一 UNKNOWN，字符串内容本身不判等。
     */
    private static final String FAILURE = "error";

    private final ChatModel chatModel;

    public ChatModelLlmCore(ChatModel chatModel) {
        if (chatModel == null) {
            throw new IllegalArgumentException("chatModel 不能为 null");
        }
        this.chatModel = chatModel;
    }

    @Override
    public String chat(List<Message> messages) {
        try {
            List<org.springframework.ai.chat.messages.Message> springMessages = messages.stream()
                    .map(ChatModelLlmCore::toSpringMessage)
                    .toList();
            ChatResponse response = chatModel.call(new Prompt(springMessages));
            Generation result = response != null ? response.getResult() : null;
            String text = result != null ? result.getOutput().getText() : null;
            if (text == null || text.isBlank()) {
                // 空应答对仲裁=不可解析 → 按失败契约处理（上游归一 UNKNOWN，fail-closed）
                log.error("[ChatModelLlmCore] 宿主 ChatModel 返回空应答，按失败契约返回 error");
                return FAILURE;
            }
            return text;
        } catch (Exception e) {
            // LLMCore 契约：永不抛异常。宿主 ChatModel 的任何故障（限流/超时/鉴权）
            // 都折算成 "error"，由上游归一为 UNKNOWN → 注册期 fail-closed 拒绝注册
            log.error("[ChatModelLlmCore] 宿主 ChatModel 调用异常，按失败契约返回 error: {}",
                    e.getMessage(), e);
            return FAILURE;
        }
    }

    /**
     * core Message(role, content) → Spring AI Message。
     * core 侧 role 是自由字符串（实际仲裁链路只产生 "user"，见 LlmSemanticScanner），
     * 这里防御性映射全角色；未识别角色降级为 user 并留痕——不抛异常（契约同上）。
     */
    private static org.springframework.ai.chat.messages.Message toSpringMessage(Message m) {
        return switch (m.role()) {
            case "user" -> new UserMessage(m.content());
            case "system" -> new SystemMessage(m.content());
            case "assistant" -> new AssistantMessage(m.content());
            default -> {
                log.warn("[ChatModelLlmCore] 未识别的 role [{}]，降级按 user 透传", m.role());
                yield new UserMessage("[" + m.role() + "] " + m.content());
            }
        };
    }
}
