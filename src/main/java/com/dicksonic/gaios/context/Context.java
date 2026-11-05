package com.dicksonic.gaios.context;

import com.dicksonic.gaios.context.structsum.StructuredSummary;
import com.dicksonic.gaios.context.summarizer.Summarizer;
import com.dicksonic.gaios.context.summarizer.impl.TemplateSummarizer;
import com.dicksonic.gaios.context.tokenizer.Tokenizer;
import com.dicksonic.gaios.context.tokenizer.impl.CharEstimateTokenizer;
import com.dicksonic.gaios.llm.Message;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话上下文管理器，对应 AIOS 论文的 Context Manager。
 * 职责：维护 Agent 与模型之间的完整消息序列，并提供两道保护：
 * 1. 单条防爆：addToolResult 对超长工具结果截断（TOOL_RESULT_LIMIT）；
 * 2. 整窗预算（D6）：超阈值时把最老的消息滑出窗口、压缩成结构化摘要留在窗口内。
 *    思想 ref: AIOS §3.5 Memory Manager（80% 阈值驱逐、驱逐不丢弃而是保真留存），
 *    上下文窗口压缩本身为论文外延伸。
 */
public class Context {

    private static transient Logger logger = LogManager.getLogger(Context.class);

    /** 单个工具结果的最大保留长度，超过则截断 */
    private static final int TOOL_RESULT_LIMIT = 800;

    /**
     * 整窗 token 预算。
     * 取值依据：起点 prompt=818 tokens，DeepSeek 实际上限 64k；玩具项目取 4000
     * 是为了让压缩链路在 demo 里能真实触发。
     * 失效条件：接真实长任务或换大窗口模型时上调。
     */
    private static final int MAX_TOKENS = 4000;

    /**
     * 触发压缩的占用比例。取值依据：借自 AIOS §3.5 Memory Manager 的 80% block limit。
     * 失效条件：摘要质量不足导致旧信息丢失时调低，让压缩批次更小、更频繁。
     */
    private static final double TRIGGER_RATIO = 0.8;

    /**
     * 压缩时保留的最近原文消息条数。
     * 取值依据：约等于最近 2 个 ReAct 迭代（每迭代 assistant 决策 + 工具结果等 2~3 条），
     * 配合 MAX_TURNS=5 保证近期推理连贯。
     * 失效条件：发现 Agent 重复调用已调过的工具（近期细节被压掉）时上调。
     */
    private static final int KEEP_RECENT_MESSAGES = 6;


    private final List<Message> messages = new ArrayList<>();

    private final Tokenizer tokenizer;
    private final Summarizer summarizer;
    private final int maxTokens;
    private final int keepRecentMessages;
    private StructuredSummary summary = new StructuredSummary();

    /** 默认装配：字符估算 + 规则摘要。SimpleReactAgent 用这个，无需改动。 */
    public Context() {
        this(new CharEstimateTokenizer(), new TemplateSummarizer(), MAX_TOKENS, KEEP_RECENT_MESSAGES);
    }

    public Context(Tokenizer tokenizer, Summarizer summarizer, int maxTokens, int keepRecentMessages) {
        this.tokenizer = tokenizer;
        this.summarizer = summarizer;
        this.maxTokens = maxTokens;
        this.keepRecentMessages = keepRecentMessages;
    }


    /**
     * 角色：开发者意志的注入点。人设、能力清单、输出格式等全局规则，整个会话只加一次
     * @param content
     */
    public void addSystem(String content)    { messages.add(new Message("system", content)); }
    /*
    * user 角色：外部世界对模型的输入。两种来源：
    * ① 真实用户的问题（addUser 直调）；
    * ② 工具执行结果（addToolResult 内部调用）——模型视角里的"环境响应"。
    * 选 user 而非 system 是刻意的：工具结果是动态数据，不应享有规则级权重。
     */
    public void addUser(String content)      { messages.add(new Message("user", content)); }

    /**
     * assistant 角色：模型自身历史输出的存档。让下一轮决策能看到"我上一轮说过什么"，维持多轮连贯
     * @param content
     */
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
        compressIfNeeded();
        if (summary.isEmpty()) {
            return List.copyOf(messages);
        }
        List<Message> out = new ArrayList<>();
        int i = 0;
        // 头部 system 消息原样保留
        while (i < messages.size() && "system".equals(messages.get(i).role())) {
            out.add(messages.get(i));
            i++;
        }
        out.add(new Message("system", summary.render()));
        out.addAll(messages.subList(i, messages.size()));
        return List.copyOf(out);
    }

    /** 当前整窗 token 数（原文消息 + 摘要渲染文本），审计与调参用。 */
    public int currentTokens() {
        int t = summary.isEmpty() ? 0 : tokenizer.count(summary.render());
        for (Message m : messages) {
            t += tokenizer.count(m.content());
        }
        return t;
    }

    /** 审计与排障用：当前结构化摘要（未压缩过则为空白摘要）。 */
    public StructuredSummary getSummary() {
        return summary;
    }

    /**
     * 超阈值时，把最老的可滑出消息整批交给摘要器，只保留最近 keepRecentMessages 条原文。
     * 头部 system 消息永不滑出。
     * 单趟压缩（非循环）：滑出后若仍超阈值，说明保留窗口本身太大，打警告提示调参。
     */
    private void compressIfNeeded() {
        int threshold = (int) (maxTokens * TRIGGER_RATIO);
        if (currentTokens() <= threshold) {
            return;
        }
        int start = 0;
        while (start < messages.size() && "system".equals(messages.get(start).role())) {
            start++;
        }
        int evictCount = messages.size() - start - keepRecentMessages;
        if (evictCount <= 0) {
            logger.info("[Context] 警告：保留窗口本身已超阈值（{} tokens / 预算 {}），请上调 maxTokens 或下调 keepRecentMessages",currentTokens(),maxTokens);
            return;
        }
        List<Message> evicted = new ArrayList<>(messages.subList(start, start + evictCount));
        logger.info("[Context] 触发压缩：滑出 {} 条消息进摘要（触发时 {} tokens / 预算 {}）", evicted.size(), currentTokens(), maxTokens);
        summary = summarizer.summarize(evicted, summary);
        summary.setCoveredMessages(summary.getCoveredMessages() + evicted.size());
        messages.subList(start, start + evictCount).clear();
    }
}