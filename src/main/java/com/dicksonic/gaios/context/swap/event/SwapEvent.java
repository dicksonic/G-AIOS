package com.dicksonic.gaios.context.swap.event;

import com.dicksonic.gaios.llm.Message;

import java.util.List;

/**
 * 一次压缩换出事件：滑出原文 + 触发元数据 + 换出后的摘要快照（D7，ref: AIOS §3.5 swap / §3.6 storage）。
 *
 * @param seq           本 Context 实例内第几次换出（从 1 开始）
 * @param ts            触发时间，ISO_LOCAL_DATE_TIME 格式
 * @param triggerTokens 触发时窗口 tokens（CharEstimate 估算值，非 usage 真值）
 * @param budget        当时的窗口预算 maxTokens
 * @param keepRecent    当时保留的最近原文条数
 * @param messages      滑出的原文。注意保真度语义：这是窗口内版本，
 *                      工具结果已是 addToolResult 800 字截断后的"Agent 实际所见"
 * @param summaryAfter  本次换出合并后的摘要快照；为 null 表示摘要环节异常（原文不丢，摘要缺失）
 */
public record SwapEvent(int seq, String ts, int triggerTokens, int budget, int keepRecent,
                        List<Message> messages, String summaryAfter) {

    public SwapEvent {
        // 防御拷贝：事件交给落盘层后，不受 Context 内部列表后续变化影响
        messages = List.copyOf(messages);
    }
}