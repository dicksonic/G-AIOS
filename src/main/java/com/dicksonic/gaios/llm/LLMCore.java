package com.dicksonic.gaios.llm;

import java.util.List;

/*
 * LLM核心层
 */
public interface LLMCore {
    String chat(List<Message> messages);
}