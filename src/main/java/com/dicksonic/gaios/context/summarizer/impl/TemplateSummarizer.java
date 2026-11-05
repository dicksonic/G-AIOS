package com.dicksonic.gaios.context.summarizer.impl;

import com.dicksonic.gaios.context.structsum.StructuredSummary;
import com.dicksonic.gaios.context.summarizer.Summarizer;
import com.dicksonic.gaios.llm.Message;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则模板摘要器（D6.2 第一步，先手写后引库纪律）。
 *
 * 不调用 LLM，零成本、零延迟、可离线跑，负责填充：
 * - userGoal：对话中第一条"真正的"用户消息（仅首次压缩时设置，之后不覆盖）；
 * - toolFindings：解析 [工具 xxx 返回] 消息，按工具名去重保留最后一次结论。
 * keyFacts / pendingTasks 超出规则能力，留空，由 LlmSummarizer（第二步）补充。
 *
 * 格式契约：只认 Context.addToolResult 的写入格式 "[工具 {toolName} 返回] {content}"。
 * 若 addToolResult 的格式变更，只需改下面这个正则常量。
 */
public class TemplateSummarizer implements Summarizer {
    private static final Logger log = LogManager.getLogger(TemplateSummarizer.class);

    private static final Pattern TOOL_RESULT =
            Pattern.compile("^\\[工具\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*返回\\]\\s*(.*)$", Pattern.DOTALL);

    /**
     * 单条工具结论截断长度。
     * 取值依据：摘要是给 LLM 继续推理用的提示，120 字足以保留"查到了什么"的核心结论。
     * 失效条件：发现压缩后 Agent 丢失关键数字/条款号导致答错时，上调或改用 LlmSummarizer。
     */
    private static final int FINDING_MAX_CHARS = 120;

    /** 用户目标截断长度。取值依据：一句话目标；失效条件：多目标长需求场景。 */
    private static final int GOAL_MAX_CHARS = 200;

    @Override
    public StructuredSummary summarize(List<Message> evicted, StructuredSummary previous) {
        StructuredSummary s = previous != null ? previous : new StructuredSummary();

        // 用户目标只在首次压缩时确定，之后不覆盖——目标不应随窗口滑动而漂移。
        // 注意：工具结果也是 user 角色，必须先排除，否则目标会被抢成 "[工具 xxx 返回]..."
        if (s.getUserGoal().isEmpty()) {
            for (Message m : evicted) {
                if ("user".equals(m.role()) && !TOOL_RESULT.matcher(m.content()).matches()) {
                    s.setUserGoal(truncate(m.content(), GOAL_MAX_CHARS));
                    log.info("[TemplateSummarizer] 锚定用户目标：{}", s.getUserGoal());
                    break;
                }
            }
        }

        // 工具结论：每条 [工具 xxx 返回] 消息自带工具名，原子可解析，
        // 不需要配对它前面的 Action——同名工具 put 覆盖，天然实现"保留最后一次结论"
        for (Message m : evicted) {
            Matcher t = TOOL_RESULT.matcher(m.content());
            if (t.matches()) {
                s.getToolFindings().put(t.group(1), truncate(t.group(2).trim(), FINDING_MAX_CHARS));
            }
        }
        log.info("[TemplateSummarizer] 合并 {} 条滑出消息，工具结论现 {} 条：{}", evicted.size(), s.getToolFindings().size(), s.getToolFindings().keySet());
        return s;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}