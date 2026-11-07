package com.dicksonic.gaios.security.scanner.impl;

import com.dicksonic.gaios.llm.LLMCore;
import com.dicksonic.gaios.security.scanner.enums.ScanVerdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LlmSemanticScanner 三态判定验证：stub LLMCore 脚本化应答，不碰真实 API。
 */
class LlmSemanticScannerTest {

    /** 只会返回固定应答的 LLM stub */
    private static LLMCore stubLlm(String fixedReply) {
        return messages -> fixedReply;
    }

    @Test
    void 合法malicious应答_判MALICIOUS() {
        LlmSemanticScanner scanner = new LlmSemanticScanner(
                stubLlm("{\"verdict\":\"malicious\",\"reason\":\"抢占调用位\"}"));

        assertEquals(ScanVerdict.MALICIOUS, scanner.judge("t", "某工具描述"));
    }

    @Test
    void 带代码围栏的safe应答_判SAFE() {
        LlmSemanticScanner scanner = new LlmSemanticScanner(
                stubLlm("```json\n{\"verdict\":\"safe\",\"reason\":\"正常只读工具\"}\n```"));

        assertEquals(ScanVerdict.SAFE, scanner.judge("t", "某工具描述"));
    }

    @Test
    void LLM故障返回error_判UNKNOWN而非SAFE() {
        // DeepSeekCore 契约：失败返回 "error"——绝不能被当成"判了安全"
        LlmSemanticScanner scanner = new LlmSemanticScanner(stubLlm("error"));

        assertEquals(ScanVerdict.UNKNOWN, scanner.judge("t", "某工具描述"));
    }

    @Test
    void verdict字段缺失或取值越界_判UNKNOWN() {
        LlmSemanticScanner scanner = new LlmSemanticScanner(
                stubLlm("{\"result\":\"probably safe\"}"));

        assertEquals(ScanVerdict.UNKNOWN, scanner.judge("t", "某工具描述"));
    }
}