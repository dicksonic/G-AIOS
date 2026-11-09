# G-AIOS × SmartSiteAssistant 架构设计文档

> 版本：v1.0　日期：2026-11-08　状态：M1 收口后、M2 开工前的架构基线
> 范围：G-AIOS 运行时内核（com.dicksonic.gaios，v0.1）+ SmartSiteAssistant 业务 web 工程（com.reg.assistant，1.0.0）

---

## 1. 背景与定位

两个工程承担不同使命，架构评审必须分开用标尺：

| 工程 | 定位 | 演进方向 | 评价标尺 |
|---|---|---|---|
| G-AIOS | AIOS/MCPTox/MCP-Guard 三论文复现项目 → **G 端 Agent 安全运行时**（长期平台化） | 从玩具级向可投产内核演进，master 标注论文章节、里程碑打 tag | 接口可替换性、零业务耦合、可独立发布 |
| SmartSiteAssistant | 2026 年度目标"产品 AI 助手 demo"载体，G-AIOS 的第一个真实用户 | 按可投产标准交付（M1 问答 / M2 隐患识别 / M3 综合报告） | 薄壳、业务适配清晰、可运维 |

两者关系：**G-AIOS 是被依赖的库（Maven 坐标 com.dicksonic:g-aios:0.1，本地仓库），web 工程是唯一消费方**。依赖方向单向，不允许反向。

---

## 2. 当前架构全景

### 2.1 分层视图

```
┌─────────────────────────────────────────────────────────────────┐
│  SmartSiteAssistant（Spring Boot 3.4.5 / Java 21 / 端口 8087）     │
│                                                                   │
│  前端  React+Vite+shadcn（src/main/webapp）                        │
│        Home / AssistantPage(四场景+对比模式) / Dashboard / Platform │
│  ─────────────────────────────────────────────────────────────  │
│  接入层  controller: AssistantController(ask/askAgent/四场景)      │
│                    IntegrationController(HMAC免登) StatsController │
│          common: TraceIdFilter / GlobalExceptionHandler           │
│  ─────────────────────────────────────────────────────────────  │
│  业务层  service.AssistantService ── 直连 LLM 通道（Baseline）     │
│          agent.service.AgentService ── Agent 通道（G-AIOS）        │
│          agent.service.KnowledgeSearchService（E5 向量检索）       │
│  ─────────────────────────────────────────────────────────────  │
│  适配层  agent.RegulationSearchTool ── G-AIOS Tool 接口实现        │
│          provider.ModelProvider(mock|http) + MeteredGateway       │
│          config.GaiosConfig（装配 Bean，fail-fast）                │
│  ─────────────────────────────────────────────────────────────  │
│  数据层  MyBatis mapper × 5 ── MySQL 5.7（Flyway V1，8 表）        │
└──────────────────────────┬──────────────────────────────────────┘
                           │ Maven 依赖（进程内）
┌──────────────────────────▼──────────────────────────────────────┐
│  G-AIOS（纯 Java 21 库，log4j→logback 桥接，无 Spring）            │
│                                                                   │
│  agent.SimpleReactAgent（ReAct，maxTurns 可配）                    │
│  context.Context ── Tokenizer/Summarizer/SwapStore 三接口          │
│     ├─ CharEstimateTokenizer / TemplateSummarizer(默认)           │
│     ├─ LlmSummarizer(PromptExecutor 解耦) / FileSwapStore(D7.1)   │
│  tool.Tool（业务工具经适配器注册）                                  │
│  security 三阶段管线：StaticScanner(Ⅰ正则) → E5SemanticScanner      │
│     (Ⅱ差分) → LlmSemanticScanner(Ⅲ仲裁)；ToolRegistry fail-closed │
│  llm.LLMCore ← DeepSeekCore（退避重试/UsageListener 计量）         │
│  GaiosBootstrap（装配工厂，App 与 web 共用入口）                    │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 两条 LLM 调用通道（现状的关键结构事实）

| | 通道 A：直连 | 通道 B：Agent |
|---|---|---|
| 入口 | AssistantService.ask | AgentService.askAgent |
| 路径 | ModelProvider(mock/http) → MeteredGateway | GaiosBootstrap.createAgent(4) → SimpleReactAgent → DeepSeekCore |
| model.mode=mock | 生效 | **不生效**（恒定真实 DeepSeek，已知行为） |
| 计量 | call_logs 按"次" | call_logs 真实 token（UsageListener） |
| 工具调用 | 无 | RegulationSearchTool 等，注册期过三阶段安检 |
| 定位 | 对比 demo 的 Baseline | 目标形态 |

### 2.3 数据表（V1__init.sql，MySQL 5.7，国产库预留纪律已生效）

platform_apps / assistant_sessions / call_logs / comparisons / hazard_records / knowledge_items / material_reviews / qa_records —— 8 表，`DEFAULT CURRENT_TIMESTAMP`、无反引号、方言函数下沉 Java 侧。

---

## 3. 解耦与模块化评估

### 3.1 评估记分卡

| 维度 | 评分 | 说明 |
|---|---|---|
| 仓库/构建边界 | ★★★★★ | 双 GitHub 仓库、Maven 坐标依赖、g-aios 版本化（0.1），依赖方向单向 |
| 接口解耦（G-AIOS 内） | ★★★★☆ | LLMCore/Tokenizer/Summarizer/SwapStore/SemanticScanner/Tool 全部接口先行、默认实现可换；扣一星：Message record 与 Context 的写入格式约定（`[工具 {name} 返回]`前缀）是隐性契约，靠注释维持 |
| 防腐层（web 适配 G-AIOS） | ★★★★☆ | RegulationSearchTool + KnowledgeSearchService 把业务库访问收在适配层，G-AIOS 对业务零感知 |
| 运行时隔离 | ★★★☆☆ | 进程内嵌库：G-AIOS 故障（如 E5 加载失败）可拖垮 web 启动——已由 GaiosConfig fail-fast + health 探针缓解，但本质是同生共死 |
| 配置解耦 | ★★★★☆ | app.properties 工作目录依赖已显式配置化；gaios.agent.* 四旋钮外置 |
| 可观测性 | ★★★★☆ | traceId 全链（含 worker 线程 MDC）、health 探针、call_logs 计量、log4j→logback 合流 |
| 可测试性 | ★★★★☆ | 双工程各 12 用例，mock LLMCore 驱动真实 Agent 的策略正确 |
| 数据层演进能力 | ★★★★☆ | Flyway 基线 + 国产库方言纪律（V1 转换清单已留档） |

### 3.2 做对了的（保持）

1. **库/壳分离 + 单页依赖**：web 对 G-AIOS 的接触面收敛在 GaiosConfig（装配）、AgentService（调用）、RegulationSearchTool（适配）三个文件，换内核版本只需改 pom。
2. **双通道并行而非合并**：没有为了"统一"把 Agent 硬塞进 ModelProvider 单次调用抽象——保住了对比 demo 的叙事价值和两条通道各自的简单性。
3. **生产化旋钮只加不改接口**：maxTurns 构造参数、setUsageListener 均为向后兼容扩展，G-AIOS 论文复现语义未破坏。
4. **审计层不挂主流程**：D7 spill 失败只 warn 不抛、UsageListener 异常旁路——横切关注点的正确姿态。

### 3.3 四个需要正视的问题（按优先级）

**P1-① G-AIOS 库 jar 内含演示代码与攻击样本（最实际的污染）**
App.main、CalibrationRunner、attacksamples（毒工具样本）与内核同 jar 发布，web 工程吃这个 jar 等于把演示入口和投毒样本打进业务交付物。
→ 建议：**G-AIOS 拆 Maven 多模块**：`gaios-core`（web 依赖它）+ `gaios-samples`（App/Runner/attacksamples/标定集）。工作量约半天，M2 开工前顺手做掉。短期不动的话，至少在 pom 里把 samples 类从 jar 排除。

**P1-② 双 LLM 通道在 M2 前的定位收敛**
M2 要引入视觉能力。若视觉 API 走 ModelProvider（chat/vision/asr/tts 已有 vision 槽位），Agent 通道将无法编排视觉工具；若走 G-AIOS 新增 VisionCore，则 ModelProvider.vision 成为死槽位。
→ 建议（详见 §4）：**VisionCore 接口进 G-AIOS，vl 实现复用 DeepSeekCore 的 HTTP/重试/计量设施**；ModelProvider 保留 mock/http 仅服务 Baseline 对比与 ASR/TTS，vision 槽位标注 deprecated-候选。

**P2-③ web 侧包结构开始随意**
`agent/RegulationSearchTool` 与 `agent/service/{AgentService,KnowledgeSearchService}` 两层混放，是逐次交付临时归位的历史痕迹。
→ 建议 M2 动手前一次性规整（纯移动、零逻辑改）：
```
com.reg.assistant
├─ agent          # Agent 通道编排：AgentService
├─ knowledge      # 知识检索：KnowledgeSearchService
├─ integration    # G-AIOS 适配：GaiosConfig/RegulationSearchTool/GaiosHealthIndicator
│                 #   （后续业务系统取数适配器也进这里）
├─ provider / service / controller / mapper / ...（不动）
```

**P2-④ 审计链未闭环**
D7.1 的 swap-logs JSONL 已能落盘，但：web 侧每次请求 createAgent 新 Context，swap 文件无人消费、无保留策略、SwapReplayRunner 未写。M3 卖点"报告依据可回溯"依赖这条链。
→ 建议：D7 剩余两步（ReplayRunner + App 回归）与 M3 数据聚合工具集同期做；web 侧 M2 只需补一件事——把 swap 文件目录配置化 + 按天清理策略。

### 3.4 明确不做的事（防止过度设计）

- **不做微服务拆分**：单进程嵌库对 demo 和首个试点是正确形态；独立安检服务化是汇报落地后的事（接口已纯函数化，届时包一层 REST 即可）。
- **G-AIOS 不引 Spring**、不上依赖注入框架：装配链 GaiosBootstrap 已够用。
- **暂不换 jtokkit**：CharEstimateTokenizer 与真值（usage）偏差已知且方向稳定，校准是优化项不是阻塞项。

---

## 4. M2 架构设计（图片隐患识别）

### 4.1 新增组件与归属决策

```
G-AIOS 侧（gaios-core）：
  llm.vision.VisionCore 接口        recognize(VisionRequest) → VisionResult
  llm.vision.impl.QwenVLCore        OpenAI 兼容协议，复用退避重试/UsageListener 模式
                                    （与 DeepSeekCore 同构，计量回调同一接口）
web 侧：
  integration.HazardImageFetcher    业务图片取数适配器（取数方式开工时确认：库表/接口/文件）
  hazard.HazardRecognizeService     编排：取图 → 视觉识别 → 结构化隐患草稿
                                    → hazard_records 落库（状态机：待确认→已确认）
  agent 侧新增业务工具：
    HazardDraftTool                 Agent 闭环内起草隐患单（复用 D6 场景 B 剧本）
    （query_dangers/query_regulations/draft_notice 原样复用）
```

**关键设计决策：视觉识别不进 Agent 循环。** 识别主链路走 HazardRecognizeService 确定性编排（成本可控、耗时可预算、结果结构化）；Agent 只在用户追问（"这个隐患违反哪条规范/起草整改通知"）时进入，复用已验证的 ReAct + 工具链 + D6 压缩 + D7 审计。视频现实路径=抽帧，非实时流。

### 4.2 计量与可观测

- 视觉 tokens 进 call_logs，api_type=vision，与 text/agent 行同口径；UsageListener 模式复用。
- hazard_records 状态流转留痕（人工确认是 G 端刚需，对应远期 L1/L2/L3 人工闸门的预演）。

### 4.3 M2 开工前置确认项（用户侧）

1. 业务图片取数方式：库表 / 接口 / 导出文件
2. 视觉 API 选型与充值（通义 VL / GLM-4V，OpenAI 兼容）
3. G-AIOS 多模块拆分（P1-①）是否本轮顺手做

---

## 5. M3 架构设计（综合分析报告，概要）

- **数据聚合工具集**：web 侧新增 3-4 个统计工具（注册进 ToolRegistry 过安检），Agent 多轮拉数——D6 压缩在此真正承压（M1 单题摸不到 3200 阈值，M3 是压缩链路的真实战场）。
- **长文生成**：分节生成 + 合并，避免单次超长输出；报告模板在 web 侧。
- **审计回放**：D7 SwapReplayRunner 重建"Agent 当时所见"，报告结论可回溯——政务场景差异化亮点。
- **页面渲染导出**：前端报告页 + 导出（技术选型 M3 再定）。

---

## 6. 长期架构方向（平台化）

按 AIOS 六模块生长顺序：工具管理(✅) → Context(D6✅/D7🟡) → **Access Manager（L1/L2/L3 人工确认网关，G 端差异化最强，下一个战略性投入）** → Memory/Storage 分层（密级标签） → Scheduler（远期）。

两个已识别的架构跃迁点（届时再设计，现在只留口子）：
1. **安检服务化**：security 管线包 REST 层成为独立服务（MCP-Guard 论文 proxy 形态），以"注册时回调检测 API"对接 NexBot——管线本身是纯函数判定，包装成本低。
2. **e5-base + Smile 微调**：91% LLM 仲裁率的降本路径，Java 栈内完成。

---

## 7. 风险登记

| 风险 | 等级 | 缓解 |
|---|---|---|
| G-AIOS 单点故障拖垮 web | 中 | GaiosConfig fail-fast + health 探针（已有）；远期服务化隔离 |
| 双通道语义漂移（计量口径、mock 行为不一致） | 中 | 本文档 §2.2 固化为契约；新能力只进 Agent 通道 |
| Context 隐性契约（工具结果前缀格式）被无意破坏 | 低 | 补一条契约注释 + RegulationSearchToolTest 已有防回归 |
| swap-logs 无保留策略撑爆磁盘 | 低 | M2 配目录化 + 按天清理 |
| MySQL 5.7 超出 Flyway 社区支持 | 低 | 已记录为信创迁移论据；V1 转换清单留档 |

---

## 8. 附：关键接口清单（契约面）

| 接口 | 位置 | 实现 | 替换点 |
|---|---|---|---|
| LLMCore | gaios.llm | DeepSeekCore | 本地模型（信创愿景） |
| VisionCore（M2 新增） | gaios.llm.vision | QwenVLCore | GLM-4V 等 |
| Tokenizer | gaios.context | CharEstimateTokenizer | jtokkit |
| Summarizer | gaios.context | TemplateSummarizer / LlmSummarizer | — |
| SwapStore | gaios.context.swap | NoopSwapStore / FileSwapStore | 数据库实现 |
| SemanticScanner | gaios.security.scanner | E5 / Llm | Smile 微调模型 |
| Tool | gaios.tool | 业务适配器（web 侧） | 每业务系统一批 |
| ModelProvider | web.provider | Mock / Http | 仅 Baseline 与 ASR/TTS |
