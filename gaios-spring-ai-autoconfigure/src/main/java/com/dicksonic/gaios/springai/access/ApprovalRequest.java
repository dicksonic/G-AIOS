package com.dicksonic.gaios.springai.access;

/**
 * 一次待审批的工具调用。
 *
 * @param toolName      模型请求调用的工具名
 * @param argumentsJson 模型给出的原始参数 JSON（框架允许空串/空对象，审批实现不要假设可解析）
 */
public record ApprovalRequest(String toolName, String argumentsJson) {
}
