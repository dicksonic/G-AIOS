package com.dicksonic.gaios;

import com.dicksonic.gaios.agent.SimpleReactAgent;
import com.dicksonic.gaios.llm.LLMCore;
import com.dicksonic.gaios.llm.impl.DeepSeekCore;
import com.dicksonic.gaios.security.ToolRegistry;
import com.dicksonic.gaios.security.attacksamples.EvasionPoisonTool;
import com.dicksonic.gaios.security.attacksamples.MaliciousSafetyCheckTool;
import com.dicksonic.gaios.security.scanner.SemanticScanner;
import com.dicksonic.gaios.security.scanner.engine.E5EmbeddingEngine;
import com.dicksonic.gaios.security.scanner.impl.E5SemanticScanner;
import com.dicksonic.gaios.security.scanner.impl.LlmSemanticScanner;
import com.dicksonic.gaios.tools.Tool;
import com.dicksonic.gaios.tools.impl.DangerQueryTool;
import com.dicksonic.gaios.tools.impl.NoticeDraftTool;
import com.dicksonic.gaios.tools.impl.RegulationQueryTool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class App {
    private static final Logger log = LogManager.getLogger(App.class);
    //主入口
    public static void main(String[] args) throws Exception {

        E5EmbeddingEngine e5 = new E5EmbeddingEngine(Path.of("models"));

        LLMCore llm = new DeepSeekCore();

        // 2. Stage II 依赖 LLMCore，先建它
        SemanticScanner stage2 = new E5SemanticScanner(e5,new LlmSemanticScanner(llm),0.0);
        ToolRegistry registry = new ToolRegistry(stage2);
        registry.register(new DangerQueryTool());
        registry.register(new RegulationQueryTool());
        registry.register(new NoticeDraftTool());
        registry.register(new MaliciousSafetyCheckTool());   // 应被隔离
        registry.register(new EvasionPoisonTool()); // 应被隔离

        log.info("隔离区: {}", registry.quarantined());
        SimpleReactAgent agent = new SimpleReactAgent(llm, registry.approvedTools());

        String answer = agent.run(
                "某安置房项目有哪些隐患还没整改？该依据哪条规范？帮我起草一份督办通知草稿。");
        log.info("最终回复: {}", answer);
    }

    /**
     * 余弦相似度。
     * 前提：两个向量都已 L2 归一化（meanPoolAndNormalize 步骤4保证了这一点），
     * 此时 ‖a‖=‖b‖=1，点积即余弦，无需再除模长。
     */
    private static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("维度不一致: " + a.length + " vs " + b.length);
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }
}