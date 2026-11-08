package com.dicksonic.gaios.context.summarizer.impl;

import com.dicksonic.gaios.context.summarizer.PromptExecutor;
import com.dicksonic.gaios.context.structsum.StructuredSummary;
import com.dicksonic.gaios.context.summarizer.Summarizer;
import com.dicksonic.gaios.llm.Message;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * LLM 摘要器（D6.2 第二步增强）。
 *
 * 让 LLM 把【新增历史】合并进【已有摘要】并输出更新后的完整摘要，
 * 相比规则版能补上 keyFacts / pendingTasks。
 *
 * 可靠性设计（工程纪律：错误路径打印原文）：
 * - 调用异常 → 打印原始异常，降级 TemplateSummarizer；
 * - 返回格式不符（四个区块一个都解析不出来）→ 打印原始返回，降级 TemplateSummarizer；
 * - userGoal 解析缺失时继承上一版摘要的目标，防止目标漂移。
 */
public class LlmSummarizer implements Summarizer {

    private static transient Logger logger = LogManager.getLogger(LlmSummarizer.class);

    private final PromptExecutor llm;
    private final TemplateSummarizer fallback = new TemplateSummarizer();

    public LlmSummarizer(PromptExecutor llm) {
        this.llm = llm;
    }

    @Override
    public StructuredSummary summarize(List<Message> evicted, StructuredSummary previous) {
        StructuredSummary base = previous != null ? previous : new StructuredSummary();
        String resp;
        try {
            resp = llm.execute(buildPrompt(evicted, base));
        } catch (Exception e) {
            logger.warn("[LlmSummarizer] LLM 摘要调用失败，降级为规则摘要。原始异常：{}",e.getMessage(), e);
            return fallback.summarize(evicted, base);
        }
        StructuredSummary parsed = parse(resp);
        if (parsed == null) {
            logger.warn("[LlmSummarizer] LLM 摘要格式不符，降级为规则摘要。原始返回：{}" ,resp );
            return fallback.summarize(evicted, base);
        }
        if (parsed.getUserGoal().isEmpty()) {
            parsed.setUserGoal(base.getUserGoal());
        }
        parsed.setCoveredMessages(base.getCoveredMessages());
        // parse 成功、userGoal 继承处理完之后，return 前加：
        logger.info("[LlmSummarizer] LLM 摘要合并成功：工具结论 {} 条、事实 {} 条、待办 {} 条",
                parsed.getToolFindings().size(), parsed.getKeyFacts().size(), parsed.getPendingTasks().size());
        return parsed;
    }

    private String buildPrompt(List<Message> evicted, StructuredSummary previous) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是对话历史压缩器。把【新增历史】合并进【已有摘要】，输出更新后的完整摘要。\n");
        sb.append("严格要求：只输出以下四个区块，每个区块一行起，不要输出任何其他内容；无内容的区块写\"无\"；"
                + "每条不超过 30 字，事实最多 5 条、待办最多 3 条，超出自行合并取舍。\n");
        sb.append("目标：<用户的最终目标，一句话>\n");
        sb.append("工具：<工具名> => <该工具查到的结论，一句话>（每个工具一行）\n");
        sb.append("事实：<对话中已确认的关键事实>（每行一条）\n");
        sb.append("待办：<还没做完的事>（每行一条）\n\n");
        sb.append("【已有摘要】\n").append(previous.isEmpty() ? "（空）" : previous.render()).append("\n\n");
        sb.append("【新增历史】\n");
        for (Message m : evicted) {
            sb.append(m.role()).append(": ").append(m.content()).append('\n');
        }
        return sb.toString();
    }

    /** 宽松解析：逐行识别四个区块前缀；一个区块都解析不出则返回 null 触发降级。 */
    private StructuredSummary parse(String resp) {
        if (resp == null) {
            return null;
        }
        StructuredSummary s = new StructuredSummary();
        boolean any = false;
        for (String rawLine : resp.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (hasPrefix(line, "目标")) {
                String v = value(line);
                if (!"无".equals(v)) {
                    s.setUserGoal(v);
                    any = true;
                }
            } else if (hasPrefix(line, "工具")) {
                String v = value(line);
                if (!"无".equals(v)) {
                    int idx = v.indexOf("=>");
                    if (idx > 0) {
                        s.getToolFindings().put(v.substring(0, idx).trim(), v.substring(idx + 2).trim());
                        any = true;
                    }
                }
            } else if (hasPrefix(line, "事实")) {
                String v = value(line);
                if (!"无".equals(v)) {
                    s.getKeyFacts().add(v);
                    any = true;
                }
            } else if (hasPrefix(line, "待办")) {
                String v = value(line);
                if (!"无".equals(v)) {
                    s.getPendingTasks().add(v);
                    any = true;
                }
            }
        }
        return any ? s : null;
    }

    private static boolean hasPrefix(String line, String prefix) {
        return line.startsWith(prefix + "：") || line.startsWith(prefix + ":");
    }

    private static String value(String line) {
        int i = line.indexOf('：');
        if (i < 0) {
            i = line.indexOf(':');
        }
        return i < 0 ? line : line.substring(i + 1).trim();
    }
}