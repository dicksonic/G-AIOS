package com.dicksonic.gaios.context;

import com.dicksonic.gaios.llm.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话上下文管理器，对应 AIOS 论文的 Context Manager。
 * 职责：维护 Agent 与模型之间的完整消息序列，
 * 并在写入时执行第一道保护——防止超长内容撑爆后续轮次。
 */
public class Context {

    /** 单个工具结果的最大保留长度，超过则截断 */
    private static final int TOOL_RESULT_LIMIT = 800;

    private final List<Message> messages = new ArrayList<>();

    public void addSystem(String content)    { messages.add(new Message("system", content)); }
    public void addUser(String content)      { messages.add(new Message("user", content)); }
    public void addAssistant(String content) { messages.add(new Message("assistant", content)); }

    /**
     * 工具结果以 user 角色回喂（当前轮次里模型还没有新的输出，
     * 工具结果等价于"环境对上一轮 assistant 决策的响应"）。
     *
     * 截断策略是最朴素的上下文压缩：
     * 真正的 AIOS 还有 text/logits 快照和换出机制，
     * 我们先把"无限增长"这个洞堵上。
     */
    public void addToolResult(String toolName, String result) {
        String content = result.length() > TOOL_RESULT_LIMIT
                ? result.substring(0, TOOL_RESULT_LIMIT) + "...[截断,原长" + result.length() + "字符]"
                : result;
        messages.add(new Message("user", "[工具 " + toolName + " 返回] " + content));
    }

    /**
     * 返回当前消息序列的不可变快照。
     * 名字沿用 AIOS 论文术语：上下文快照是抢占/恢复调度的前提，
     * 虽然现在还没调度器，先把"可随时拿走一份当前状态"的能力备好。
     */
    public List<Message> snapshot() {
        return List.copyOf(messages);
    }
}