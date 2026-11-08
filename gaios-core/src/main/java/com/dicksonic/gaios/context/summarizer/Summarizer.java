package com.dicksonic.gaios.context.summarizer;

import com.dicksonic.gaios.context.structsum.StructuredSummary;
import com.dicksonic.gaios.llm.Message;

import java.util.List;

/**
 * 摘要器抽象（D6.2）。
 *
 * 滚动设计：previous 为上一轮压缩产出的摘要，实现方负责把 evicted 合并进去，
 * 而不是覆盖——滑动窗口每触发一次压缩，摘要就滚动更新一次。
 */
public interface Summarizer {

    /**
     * @param evicted  本次被滑出窗口的消息（按原始顺序）
     * @param previous 已有摘要；首次压缩时为空白摘要，不会为 null
     * @return 合并后的新摘要
     */
    StructuredSummary summarize(List<Message> evicted, StructuredSummary previous);
}