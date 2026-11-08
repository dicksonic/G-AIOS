package com.dicksonic.gaios.agent;

import com.dicksonic.gaios.context.Context;
import com.dicksonic.gaios.context.summarizer.impl.LlmSummarizer;
import com.dicksonic.gaios.context.tokenizer.impl.CharEstimateTokenizer;
import com.dicksonic.gaios.llm.LLMCore;
import com.dicksonic.gaios.llm.Message;
import com.dicksonic.gaios.tools.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 最小 ReAct Agent：
 * Reasoning（决定调什么工具）+ Acting（执行）循环，
 * 直到模型给出 final 答案或达到轮数上限。
 * 对应论文 AIOS 中 Application Layer 通过 SDK 驱动内核的最小形态。
 */
public class SimpleReactAgent {
    private static final Logger log = LogManager.getLogger(SimpleReactAgent.class);

    /**
     * 论文默认轮数上限：防止模型陷入"调工具→失败→再调"的死循环烧光 token。
     * 取值 8 为 AIOS 复现标定值，研究流程（App）保持不动；
     * web 生产侧经构造参数收紧——问答场景工具单一，用不上 8 轮。
     */
    public static final int DEFAULT_MAX_TURNS = 8;

    private final int maxTurns;

    private final LLMCore llm;
    private final List<Tool> tools;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 调用结果缓存：key = 工具名+参数。AIOS Tool Manager 缓存机制的最小形态 */
    private final Map<String, String> toolResultCache = new HashMap<>();

    public SimpleReactAgent(LLMCore llm, List<Tool> tools) {
        this(llm, tools, DEFAULT_MAX_TURNS);
    }

    /**
     * @param maxTurns 轮数上限。取值依据：web 问答场景工具单一（查条文），
     *        典型路径=1 轮工具调用+1 轮收口，给 4 留足重试冗余即可。
     *        失效条件：工具集扩展到多工具链式调用（M2 隐患闭环）时需按链路长度重估。
     */
    public SimpleReactAgent(LLMCore llm, List<Tool> tools, int maxTurns) {
        this.llm = llm;
        this.tools = tools;
        this.maxTurns = maxTurns;
    }

    public String run(String userQuestion) {
        // 把工具清单拼进 system prompt——这就是 Agent 的"能力边界声明"
        String toolDocs = tools.stream()
                .map(t -> "- " + t.name() + ": " + t.description())
                .collect(Collectors.joining("\n"));
        Context ctx = new Context(
                new CharEstimateTokenizer(),
                new LlmSummarizer(prompt -> llm.chat(List.of(new Message("user", prompt)))),
                4000, 6);
        ctx.addSystem("""
                你是建设工程质量安全监管助手。你可以使用以下工具：
                %s

                每次回复必须是且仅是一个JSON对象，两种格式选一：
                {"action":"tool","tool":"工具名","args":{"参数名":"值"}}
                {"action":"final","answer":"给用户的最终回答"}

                规则：
                1. 需要查数据时先调工具，不要编造
                2. 每次只输出JSON，不要有任何其他文字
                """.formatted(toolDocs));
        ctx.addUser(userQuestion + "\n\n请给出下一步JSON决策。");
        /*
         * 历史记录：所有工具执行结果都追加到这里。
         * 注意当前实现的缺陷：每轮都是全量重发（token 膨胀）。
         * 这是刻意的——D4 做上下文管理时会亲手把它改成"摘要+快照"，
         * 那是对 AIOS Context Manager 最真实的复现。
         */
        for (int turn = 1; turn <= maxTurns; turn++) {
            String reply = llm.chat(ctx.snapshot());
            log.info("第{}轮模型决策: {}", turn, reply);
            ctx.addAssistant(reply);
            try {
                JsonNode decision = mapper.readTree(stripCodeFence(reply));
                String action = decision.path("action").asText();

                if ("final".equals(action)) {
                    return decision.path("answer").asText();
                }
                if ("tool".equals(action)) {
                    String toolName = decision.path("tool").asText();
                    Tool tool = tools.stream()
                            .filter(t -> t.name().equals(toolName))
                            .findFirst()
                            .orElse(null);
                    if (tool == null) {
                        // 模型幻觉出不存在的工具：把错误回喂，让它自我修正
                        // 伏笔：如果这里"不存在的工具"是被恶意注册进来的呢？安全内核要管这件事
                        ctx.addUser("[系统] 工具不存在: " + toolName + "，请换一个或给出最终回答");
                        continue;
                    }
                    String cacheKey = toolName + "|" + decision.path("args").toString();
                    String result = toolResultCache.computeIfAbsent(cacheKey, k -> tool.execute(decision.path("args").toString()));
                    ctx.addToolResult(toolName, result);
                    continue;
                }
                ctx.addUser("[系统] 无法识别的action: " + action);
            }
            catch (Exception e) {
                // 模型输出不是合法JSON（最常见：多嘴加了解释文字），回喂错误让它重试
                log.warn("JSON解析失败: {}", reply);
                ctx.addUser("[系统] 你上次的回复不是合法JSON，请重新输出");
            }
        }
        return "超过最大轮数(" + maxTurns + ")，未能完成。";
    }

    /** 模型有时会输出 ```json ... ``` 包裹的JSON，剥掉代码围栏 */
    private String stripCodeFence(String s) {
        return s.replace("```json", "").replace("```", "").trim();
    }
}