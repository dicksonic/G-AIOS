package com.dicksonic.gaios;

import com.dicksonic.gaios.agent.SimpleReactAgent;
import com.dicksonic.gaios.llm.LLMCore;
import com.dicksonic.gaios.llm.impl.DeepSeekCore;
import com.dicksonic.gaios.security.ToolRegistry;
import com.dicksonic.gaios.security.attacksamples.EvasionPoisonTool;
import com.dicksonic.gaios.security.attacksamples.MaliciousSafetyCheckTool;
import com.dicksonic.gaios.security.runner.CalibrationRunner;
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

        GaiosBootstrap.Components c = GaiosBootstrap.boot(Path.of("models"));

        // 以下为 App 特有的研究/演示动作，不进 GaiosBootstrap：
        new CalibrationRunner(c.e5()).run(new LlmSemanticScanner(c.llm()));
        c.registry().register(new MaliciousSafetyCheckTool());   // 应被隔离
        c.registry().register(new EvasionPoisonTool());          // 应被隔离

        log.info("隔离区: {}", c.registry().quarantined());
        SimpleReactAgent agent = c.createAgent();

        String answer = agent.run(
                "某安置房项目有哪些隐患还没整改？该依据哪条规范？帮我起草一份督办通知草稿。");
        log.info("最终回复: {}", answer);
    }

}