package com.dicksonic.gaios.security.runner;

import com.dicksonic.gaios.context.swap.impl.FileSwapStore;
import com.dicksonic.gaios.context.tokenizer.impl.CharEstimateTokenizer;
import com.dicksonic.gaios.context.Context;
import com.dicksonic.gaios.context.summarizer.impl.LlmSummarizer;
import com.dicksonic.gaios.context.structsum.StructuredSummary;
import com.dicksonic.gaios.context.summarizer.Summarizer;
import com.dicksonic.gaios.context.summarizer.impl.TemplateSummarizer;
import com.dicksonic.gaios.llm.LLMCore;
import com.dicksonic.gaios.llm.Message;
import com.dicksonic.gaios.llm.impl.DeepSeekCore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Paths;
import java.util.List;

/**
 * D6 收官验证：滑动窗口 + 结构化摘要链路。
 *
 * 两个场景：
 * - 场景 A（默认必跑）：规则摘要 TemplateSummarizer，离线、确定性，验证压缩链路本身；
 * - 场景 B（带 llm 参数才跑）：LlmSummarizer 走真实 API，对比摘要质量（会消耗 token）。
 *   运行方式：ContextCompressionRunner llm
 *
 * 预期（场景 A）：6 轮模拟中后段触发压缩，token 曲线锯齿回落且无"保留窗口超阈值"警告；
 * 摘要保留用户目标 + 工具结论；最终快照 = system + 摘要块 + 最近原文。
 */
public class ContextCompressionRunner {

    private static final Logger log = LogManager.getLogger(ContextCompressionRunner.class);

    /**
     * demo 预算。取值依据：让 6 轮模拟真实触发多次压缩以验证链路；
     * 生产不走这里，走 Context 默认 4000。失效条件：demo 轮数/消息长度变化时同步调。
     */
    private static final int DEMO_KEEP_RECENT = 4;

    private static final String SYSTEM_PROMPT =
            "你是政务监管智能体，服务于工程质量安全监督场景。"
                    + "你可以调用 query_dangers（隐患查询）、query_regulations（法规查询）、"
                    + "draft_notice（整改通知起草）三个工具。"
                    + "每轮输出 JSON 决策：{\"action\":\"tool\",\"tool\":\"工具名\",\"args\":{...}} "
                    + "或 {\"action\":\"final\",\"answer\":\"...\"}";

    public static void main(String[] args) throws Exception {
        runScenario("规则摘要 TemplateSummarizer（离线）", new TemplateSummarizer(),700);

        if (args.length > 0 && "llm".equalsIgnoreCase(args[0])) {
            LLMCore llm = new DeepSeekCore();
            runScenario("LLM 摘要 LlmSummarizer（真实 API）",
                    new LlmSummarizer(prompt -> llm.chat(List.of(new Message("user", prompt)))),800);
        } else {
            log.info("跳过 LLM 摘要对比场景；需要时以 llm 参数重跑：ContextCompressionRunner llm");
        }
    }

    private static void runScenario(String name, Summarizer summarizer, int maxTokens) {
        log.info("========== 场景：{} ==========", name);
        Context ctx = new Context(new CharEstimateTokenizer(), summarizer, maxTokens, DEMO_KEEP_RECENT,new FileSwapStore(Paths.get("swap-logs")));
        ctx.addSystem(SYSTEM_PROMPT);
        int naiveTokens = simulate(ctx);

        log.info("----- 最终快照结构 -----");
        for (Message m : ctx.snapshot()) {
            log.info("[{}] {}", m.role(), abbrev(m.content()));
        }
        StructuredSummary s = ctx.getSummary();
        log.info("----- 结构化摘要 -----\n{}", s.isEmpty() ? "（未触发压缩）" : s.render());
        log.info("----- 结论：不压缩总量 {} tokens → 实际窗口 {} tokens / 预算 {}，压缩覆盖 {} 条历史消息 -----",
                naiveTokens, ctx.currentTokens(), maxTokens, s.getCoveredMessages());
    }

    /**
     * 模拟 6 轮质安检对话。
     * 消息形态对齐真实运行（真机日志实证）：assistant 是 JSON 决策串，工具结果走 addToolResult。
     * 每轮结束调一次 snapshot()——与 SimpleReactAgent 每轮发消息前的动作一致，压缩在此触发。
     *
     * @return 不压缩情况下的整窗 token 总量（对照组，同一把 CharEstimateTokenizer 尺子）
     */
    private static int simulate(Context ctx) {
        CharEstimateTokenizer counter = new CharEstimateTokenizer();
        int naive = counter.count(SYSTEM_PROMPT);

        // {用户提问, 工具名, 工具结果}
        String[][] rounds = {
                {"帮我查一下城投大厦项目的安全隐患，看看有没有逾期未整改的。",
                        "query_dangers",
                        "城投大厦项目共 3 条隐患：①塔吊基础积水（已逾期 5 天未整改）；②临边防护缺失（整改中）；③施工电梯超载报警记录 2 次（未处理）。"},
                {"塔吊基础积水这种逾期不整改的，处罚依据是哪条法规？",
                        "query_regulations",
                        "《建设工程安全生产管理条例》第 64 条：逾期未整改的，责令限期改正，处 5 万元以上 10 万元以下罚款；情节严重的责令停业整顿。"},
                {"按这个给我起草一份整改通知。",
                        "draft_notice",
                        "已生成《责令限期整改通知书（草稿）》：致城投大厦项目部……限期 3 日内完成塔吊基础排水，逾期未改将依据条例第 64 条处罚。"},
                {"通知里再加上上次监督例会的通报批评记录。",
                        "draft_notice",
                        "已更新《责令限期整改通知书（草稿）》，新增 2026 年第 8 次监督例会通报批评记录。"},
                {"另外这种一般隐患的整改期限，法规上是怎么规定的？",
                        "query_regulations",
                        "《安全生产事故隐患排查治理暂行规定》第 15 条：一般事故隐患应立即组织整改，整改期限一般不超过 7 日；重大事故隐患应停产停业整改。"},
                {"好，把通知里的整改期限改成 7 天，并加上期满后监督站复查的要求。",
                        "draft_notice",
                        "已更新《责令限期整改通知书（草稿）》：整改期限 7 日，期满由监督站复查，复查不合格将依法从重处罚。"},
        };
        for (int i = 0; i < rounds.length; i++) {
            ctx.addUser(rounds[i][0]);
            String decision = "{\"action\":\"tool\",\"tool\":\"" + rounds[i][1] + "\",\"args\":{}}";
            ctx.addAssistant(decision);
            ctx.addToolResult(rounds[i][1], rounds[i][2]);
            naive += counter.count(rounds[i][0]) + counter.count(decision) + counter.count(rounds[i][2]);

            ctx.snapshot();
            log.info("[轮 {}] 窗口 {} tokens", i + 1, ctx.currentTokens());
        }
        return naive;
    }

    private static String abbrev(String s) {
        String oneLine = s.replace('\n', ' ');
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 60) + "…";
    }
}