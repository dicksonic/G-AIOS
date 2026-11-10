package com.dicksonic.gaios;

import com.dicksonic.gaios.agent.SimpleReactAgent;
import com.dicksonic.gaios.llm.LLMCore;
import com.dicksonic.gaios.llm.impl.DeepSeekCore;
import com.dicksonic.gaios.security.ToolRegistry;
import com.dicksonic.gaios.security.scanner.SemanticScanner;
import com.dicksonic.gaios.security.scanner.engine.E5EmbeddingEngine;
import com.dicksonic.gaios.security.scanner.impl.E5SemanticScanner;
import com.dicksonic.gaios.security.scanner.impl.LlmSemanticScanner;
import com.dicksonic.gaios.tools.impl.DangerQueryTool;
import com.dicksonic.gaios.tools.impl.NoticeDraftTool;
import com.dicksonic.gaios.tools.impl.RegulationQueryTool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

/**
 * 运行时装配工厂：把 E5 → LLM → 三阶段安检 → ToolRegistry → 业务工具 的装配链
 * 从 App.main 抽出，供 App（论文复现 demo）与外部工程（smartsiteai web 壳）复用。
 *
 * 边界约定：
 * 1. 只装配"生产侧"组件：3 个业务工具；标定（CalibrationRunner）与毒工具样本
 *    属于研究/演示动作，留在 App.main，不进工厂；
 * 2. 工厂不直接给 agent：调用方拿到 Components 后可先往 registry 注册自己的工具
 *    （如 web 壳的视觉识别工具），再 createAgent()——approvedTools() 快照在那一刻才取。
 */
public final class GaiosBootstrap {

    private static final Logger log = LogManager.getLogger(GaiosBootstrap.class);

    private GaiosBootstrap() {
    }

    /**
     * 装配完成的运行时组件。
     *
     * @param e5       E5 嵌入引擎（安检 Stage II 与条文语义检索共用同一实例，M1 复用点）
     * @param llm      文本模型核心
     * @param registry 工具注册中心（3 个业务工具已过三阶段安检）
     * @param scanner  装配好的 Stage II/III 语义安检链（M2-5 加法：Spring AI adapter
     *                 复用同一条管线注册第三方 ToolCallback，保证判定逻辑只有一处事实源）
     */
    public record Components(E5EmbeddingEngine e5, LLMCore llm, ToolRegistry registry, SemanticScanner scanner) {

        /** 取当前 approvedTools() 快照创建 Agent；调用方应在此之后不再注册新工具。 */
        public SimpleReactAgent createAgent() {
            return new SimpleReactAgent(llm, registry.approvedTools());
        }

        /** web 生产入口：自定义轮数上限；研究流程走无参版，保持论文默认 8 */
        public SimpleReactAgent createAgent(int maxTurns) {
            return new SimpleReactAgent(llm, registry.approvedTools(), maxTurns);
        }
    }

    /** App 研究/演示流程入口：装配 + 内置模拟业务工具 */
    public static Components boot(Path modelsDir) throws Exception {
        return boot(modelsDir, true);
    }

    /**
     * @param modelsDir E5 模型文件目录。显式传参是为了解除对工作目录的隐式依赖：
     *                  外部工程（web 壳）启动目录与 G-AIOS 不同，必须能指绝对路径。
     */
    public static Components boot(Path modelsDir, boolean withBuiltinMockTools) throws Exception {
        E5EmbeddingEngine e5 = new E5EmbeddingEngine(modelsDir);
        LLMCore llm = new DeepSeekCore();
        // 0.0 沿用 App 演示链路的实测取值（Stage II 阈值偏移参数，语义见 E5SemanticScanner 定义）
        SemanticScanner stage2 = new E5SemanticScanner(e5, new LlmSemanticScanner(llm), 0.0);
        ToolRegistry registry = new ToolRegistry(stage2);
        if (withBuiltinMockTools) {
            registry.register(new DangerQueryTool());
            registry.register(new RegulationQueryTool());
            registry.register(new NoticeDraftTool());
        }
        log.info("[GaiosBootstrap] 运行时装配完成：models={}, 业务工具 {} 个，隔离区 {}",
                modelsDir.toAbsolutePath(), registry.approvedTools().size(), registry.quarantined());
        return new Components(e5, llm, registry, stage2);
    }
}