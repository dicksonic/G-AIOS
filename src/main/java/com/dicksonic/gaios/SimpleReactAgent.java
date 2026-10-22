package com.dicksonic.gaios;

import com.dicksonic.gaios.tools.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 最小 ReAct Agent：
 * Reasoning（决定调什么工具）+ Acting（执行）循环，
 * 直到模型给出 final 答案或达到轮数上限。
 * 对应论文 AIOS 中 Application Layer 通过 SDK 驱动内核的最小形态。
 */
public class SimpleReactAgent {
    private static final Logger log = LogManager.getLogger(SimpleReactAgent.class);

    /** 轮数上限：防止模型陷入"调工具→失败→再调"的死循环烧光 token */
    private static final int MAX_TURNS = 5;

    private final LLMCore llm;
    private final List<Tool> tools;
    private final ObjectMapper mapper = new ObjectMapper();

    public SimpleReactAgent(LLMCore llm, List<Tool> tools) {
        this.llm = llm;
        this.tools = tools;
    }

    public String run(String userQuestion) {
        // 把工具清单拼进 system prompt——这就是 Agent 的"能力边界声明"
        String toolDocs = tools.stream()
                .map(t -> "- " + t.name() + ": " + t.description())
                .collect(Collectors.joining("\n"));

        String system = """
                你是建设工程质量安全监管助手。你可以使用以下工具：
                %s

                每次回复必须是且仅是一个JSON对象，两种格式选一：
                {"action":"tool","tool":"工具名","args":{"参数名":"值"}}
                {"action":"final","answer":"给用户的最终回答"}

                规则：
                1. 需要查数据时先调工具，不要编造
                2. 每次只输出JSON，不要有任何其他文字
                """.formatted(toolDocs);

        /*
         * 历史记录：所有工具执行结果都追加到这里。
         * 注意当前实现的缺陷：每轮都是全量重发（token 膨胀）。
         * 这是刻意的——D4 做上下文管理时会亲手把它改成"摘要+快照"，
         * 那是对 AIOS Context Manager 最真实的复现。
         */
        String history = "";
        for (int turn = 1; turn <= MAX_TURNS; turn++) {
            String userMsg = userQuestion
                    + (history.isEmpty() ? "" : "\n\n已执行的工具结果：\n" + history)
                    + "\n\n现在请给出下一步JSON决策。";

            String reply = llm.chat(system, userMsg);
            log.info("第{}轮模型决策: {}", turn, reply);

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
                        history += "\n[系统] 工具不存在: " + toolName + "，请换一个或给出最终回答";
                        continue;
                    }
                    String result = tool.execute(decision.path("args").toString());
                    history += "\n[工具 " + toolName + " 返回] " + result;
                    continue;
                }
                history += "\n[系统] 无法识别的action: " + action;
            }
            catch (Exception e) {
                // 模型输出不是合法JSON（最常见：多嘴加了解释文字），回喂错误让它重试
                log.warn("JSON解析失败: {}", reply);
                history += "\n[系统] 你上次的回复不是合法JSON，请重新输出";
            }
        }
        return "超过最大轮数，未能完成。已执行记录：" + history;
    }

    /** 模型有时会输出 ```json ... ``` 包裹的JSON，剥掉代码围栏 */
    private String stripCodeFence(String s) {
        return s.replace("```json", "").replace("```", "").trim();
    }
}