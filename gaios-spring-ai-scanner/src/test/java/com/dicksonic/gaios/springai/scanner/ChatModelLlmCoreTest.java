package com.dicksonic.gaios.springai.scanner;

import com.dicksonic.gaios.llm.Message;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChatModelLlmCore：role 翻译与 LLMCore 失败契约（永不抛、失败返回 "error"）。
 * 全程桩 ChatModel，不触任何真实模型。
 */
class ChatModelLlmCoreTest {

    /** 捕获型 ChatModel 桩：记下收到的 Prompt，按预设返回/抛异常 */
    private static final class CapturingChatModel implements ChatModel {
        Prompt lastPrompt;
        String reply = "{\"verdict\":\"safe\"}";
        RuntimeException toThrow;

        @Override
        public ChatResponse call(Prompt prompt) {
            if (toThrow != null) {
                throw toThrow;
            }
            this.lastPrompt = prompt;
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }
    }

    @Test
    void user角色透传_返回模型原文() {
        CapturingChatModel stub = new CapturingChatModel();
        ChatModelLlmCore core = new ChatModelLlmCore(stub);

        String result = core.chat(List.of(new Message("user", "审查这个工具")));

        assertEquals("{\"verdict\":\"safe\"}", result);
        assertNotNull(stub.lastPrompt);
        assertEquals(1, stub.lastPrompt.getInstructions().size());
        assertEquals(MessageType.USER, stub.lastPrompt.getInstructions().get(0).getMessageType());
        assertEquals("审查这个工具", stub.lastPrompt.getInstructions().get(0).getText());
    }

    @Test
    void system与assistant角色正确映射() {
        CapturingChatModel stub = new CapturingChatModel();
        ChatModelLlmCore core = new ChatModelLlmCore(stub);

        core.chat(List.of(
                new Message("system", "你是审计员"),
                new Message("assistant", "收到"),
                new Message("user", "开始审查")));

        List<org.springframework.ai.chat.messages.Message> instructions = stub.lastPrompt.getInstructions();
        assertEquals(MessageType.SYSTEM, instructions.get(0).getMessageType());
        assertEquals(MessageType.ASSISTANT, instructions.get(1).getMessageType());
        assertEquals(MessageType.USER, instructions.get(2).getMessageType());
    }

    @Test
    void 宿主异常折算error_绝不向外抛出() {
        CapturingChatModel stub = new CapturingChatModel();
        stub.toThrow = new RuntimeException("模拟宿主限流/超时/鉴权失败");
        ChatModelLlmCore core = new ChatModelLlmCore(stub);

        // LLMCore 契约（LlmSemanticScanner 依赖它把仲裁故障归一 UNKNOWN → fail-closed）
        assertEquals("error", core.chat(List.of(new Message("user", "任意"))));
    }

    @Test
    void 空应答折算error() {
        // 模型返回零 Generation 的空响应：对仲裁=不可解析，必须按失败契约处理
        ChatModel emptyStub = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of());
            }
        };
        ChatModelLlmCore core = new ChatModelLlmCore(emptyStub);

        assertEquals("error", core.chat(List.of(new Message("user", "任意"))));
    }
}
