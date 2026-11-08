package com.dicksonic.gaios.context.structsum;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构化摘要（D6.2）：被滑出窗口的历史消息的信息载体。
 *
 * 四个区块对应"Agent 继续任务所需的最小信息"：
 * - userGoal：为什么干（只在首次压缩时确定，之后不覆盖，防止目标漂移）；
 * - toolFindings：已经查到了什么（按工具名去重，保留最后一次结论，
 *   与 SimpleReactAgent 的工具结果去重缓存语义一致）；
 * - keyFacts / pendingTasks：规则版（TemplateSummarizer）抽不好，留给 LlmSummarizer 补。
 *
 * coveredMessages 记录已压缩的消息条数，用于审计与排障。
 */
public class StructuredSummary {

    private String userGoal = "";
    /** toolName -> 结论，LinkedHashMap 保持出现顺序，put 覆盖实现"保留最后一次" */
    private final Map<String, String> toolFindings = new LinkedHashMap<>();
    private final List<String> keyFacts = new ArrayList<>();
    private final List<String> pendingTasks = new ArrayList<>();
    private int coveredMessages = 0;

    public String getUserGoal() {
        return userGoal;
    }

    public void setUserGoal(String userGoal) {
        this.userGoal = userGoal == null ? "" : userGoal;
    }

    public Map<String, String> getToolFindings() {
        return toolFindings;
    }

    public List<String> getKeyFacts() {
        return keyFacts;
    }

    public List<String> getPendingTasks() {
        return pendingTasks;
    }

    public int getCoveredMessages() {
        return coveredMessages;
    }

    public void setCoveredMessages(int coveredMessages) {
        this.coveredMessages = coveredMessages;
    }

    public boolean isEmpty() {
        return userGoal.isEmpty() && toolFindings.isEmpty()
                && keyFacts.isEmpty() && pendingTasks.isEmpty();
    }

    /** 渲染为可注入 prompt 的文本块。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("[历史摘要] 以下为已压缩的 ").append(coveredMessages)
          .append(" 条历史消息的要点，供你继续任务参考，不要向用户复述：\n");
        if (!userGoal.isEmpty()) {
            sb.append("- 用户目标：").append(userGoal).append('\n');
        }
        if (!toolFindings.isEmpty()) {
            sb.append("- 已完成的工具调用及结论：\n");
            int i = 1;
            for (Map.Entry<String, String> e : toolFindings.entrySet()) {
                sb.append("  ").append(i++).append(". ")
                  .append(e.getKey()).append(" → ").append(e.getValue()).append('\n');
            }
        }
        if (!keyFacts.isEmpty()) {
            sb.append("- 关键事实：\n");
            for (String f : keyFacts) {
                sb.append("  - ").append(f).append('\n');
            }
        }
        if (!pendingTasks.isEmpty()) {
            sb.append("- 待办事项：\n");
            for (String t : pendingTasks) {
                sb.append("  - ").append(t).append('\n');
            }
        }
        return sb.toString().trim();
    }
}