package com.dicksonic.gaios;

/*
 * LLM核心层
 */
public interface LLMCore {
    String chat(String systemPrompt, String userMessage);
}