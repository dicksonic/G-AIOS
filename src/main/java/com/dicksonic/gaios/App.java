package com.dicksonic.gaios;

import com.dicksonic.gaios.agent.SimpleReactAgent;
import com.dicksonic.gaios.llm.LLMCore;
import com.dicksonic.gaios.llm.impl.DeepSeekCore;
import com.dicksonic.gaios.security.ToolRegistry;
import com.dicksonic.gaios.security.attacksamples.EvasionPoisonTool;
import com.dicksonic.gaios.security.attacksamples.MaliciousSafetyCheckTool;
import com.dicksonic.gaios.security.scanner.SemanticScanner;
import com.dicksonic.gaios.security.scanner.impl.LlmSemanticScanner;
import com.dicksonic.gaios.tools.impl.DangerQueryTool;
import com.dicksonic.gaios.tools.impl.NoticeDraftTool;
import com.dicksonic.gaios.tools.impl.RegulationQueryTool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

public class App {
    private static final Logger log = LogManager.getLogger(App.class);
    //主入口
    public static void main(String[] args) {
        LLMCore llm = new DeepSeekCore();

        // 2. Stage II 依赖 LLMCore，先建它
        SemanticScanner stage2 = new LlmSemanticScanner(llm);
        ToolRegistry registry = new ToolRegistry(stage2);
        registry.register(new DangerQueryTool());
        registry.register(new RegulationQueryTool());
        registry.register(new NoticeDraftTool());
        registry.register(new MaliciousSafetyCheckTool());   // 应被隔离
        registry.register(new EvasionPoisonTool()); // 应被隔离

        log.info("隔离区: {}", registry.quarantined());
        SimpleReactAgent agent = new SimpleReactAgent(llm, registry.approvedTools());
        /*SimpleReactAgent agent = new SimpleReactAgent(
                new DeepSeekCore(),
                List.of(new DangerQueryTool(), new RegulationQueryTool(), new NoticeDraftTool(),new MaliciousSafetyCheckTool()));*/

        // 一个问句覆盖三个工具，验证Agent能自主规划多步调用
        String answer = agent.run(
                "某安置房项目有哪些隐患还没整改？该依据哪条规范？帮我起草一份督办通知草稿。");
        log.info("最终回复: {}", answer);
    }
}