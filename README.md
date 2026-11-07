# G-AIOS
一个玩具级的政务智能体运行时：Agent 调度 + 工具安全准入，复现 AIOS / MCPTox / MCP-Guard 三篇论文的核心思想。

## 架构
（分层图：llm / context / tool / agent / security）

## 与论文的对应
| 论文模块 | 本项目实现 |
| AIOS Context Manager | Context 类（截断+快照） |
| MCPTox P1 工具投毒 | security/MaliciousSafetyCheckTool 等 |
| MCP-Guard Stage I/II | StaticScanner / SemanticScanner |

## 安全实验记录
- 实验A：无防护，Agent 第1轮被 safety_precheck 劫持 ← 复现 MCPTox
- 实验B：Stage I 拦截带特征毒工具
- 实验C：无特征毒工具绕过正则，被 Stage II 语义检测拦截 ← MCP-Guard 的意义

## Quick Start / Roadmap


# G-AIOS 项目交接文档（路线图 / 进度 / 决策 / 注意事项）

> 快照日期：2026-11-07。本文档自包含，换会话、换工具、换人都能按此接手。
> 仓库布局：G-AIOS（论文复现运行时，GitHub private）+ SmartSiteAI（web 产品壳，GitHub private，本地 H:\codes\SmartSiteAI）。

---

## 一、项目全景：两条线

### 线 1：G-AIOS 运行时（H:\codes\G-AIOS）
个人学习→生产化项目：纯 Java 政务智能体运行时，复现三篇论文：
- **AIOS**（LLM Agent OS）：ReAct Agent、Context 管理
- **MCPTox**（工具投毒）：三攻击范式 P1 显式劫持 / P2 隐式触发 / P3 参数篡改
- **MCP-Guard**（三阶段检测）：Stage I 正则 → Stage II E5 差分 → Stage III LLM 仲裁

**标定战绩**：22 样本（11 毒 + 11 正常）综合准确率 22/22（UNKNOWN=0，fail-closed 改造后首个无水满分）。
**判定公式**：diff = cos(候选, 毒锚点max) − cos(候选, 正常锚点max)；清晰区 |diff|>0.03 E5 直判，灰色区 LLM 仲裁。
**安全决策（2026-11-07 拍板）**：注册期 fail-closed（判定 UNKNOWN 拒绝注册进隔离区），运行期 fail-open 保可用。三态 ScanVerdict：SAFE/MALICIOUS/UNKNOWN。

**长期愿景**：G 端 Agent 安全运行时垂直平台（对标 Lakera/Portkey 路径），生长顺序按 AIOS 六模块：工具管理(✓) → Context(D6 ✓) → Access Manager(L1/L2/L3 人工闸门) → Memory/Storage 分层 → Scheduler(远期)。护城河 = 22 样本中文政务标定集 + 检测管线。

**公司格局（汇报叙事）**：公司 NexBot 平台（Python 生态，类 LangGraph）五层安全纵深齐全，汇报**禁止说"平台无安全"**；精确缺口 = 工具描述文本注册时无语义安检（平台组李总口头确认"估计不会的"）。话术定稿："分级管谁能用、Schema 管参数合不合法、沙箱管在哪执行——唯独没有环节管工具说明文本里写了什么，G-AIOS 三阶段管线认领这一格"。金句："V2.3 接 Dify 插件市场后描述投毒面正式打开，现在正是补安检的最后窗口期"。双预案：A) 未覆盖→补层叙事；B) 已覆盖→Pivot 为安全测试基准（22 样本=验收回归测试集）。

### 线 2：产品 AI 助手 demo（2026 年度目标，依托 G-AIOS 运行时）
三能力：**①智能问答 ②现场图片视频隐患识别并接入业务 ③AI 自动化综合分析报告**（无评分场景）。
已拍板：G-AIOS 作纯库 + web 单独工程（Spring Boot 3.4.5 + Java 21 + MyBatis，Maven 本地仓库依赖 g-aios:0.1）；视觉走充值 API（通义 VL/GLM-4V 量级，OpenAI 兼容协议，10 元级成本敏感）；业务图片数据 M2 开工时再确认取数方式。

**高标准转向（2026-11-06 拍板，覆盖玩具 demo 定位）**：按可投产标准交付，部署时机后定。阶段1（M1）web 壳加固 + G-AIOS 只加向后兼容小旋钮；阶段2（M2 前后）内核加固（超时/重试/连接池✓[已提前于 M1-3a 完成]、启动配置 fail-fast、E5 并发安全、协作式取消）。

---

## 二、路线图与当前进度

### 总路线（2026-11 起约 8 周）
```
M1 智能问答（2 周）── M2 图片隐患识别（3 周）── M3 综合分析报告（3 周）
```
- M2：VisionCore 接口 + 通义 VL 实现 + 业务图片获取 + 识别回写业务流程（隐患草稿+人工确认）+ Agent 闭环（视觉识别只是新增一个 Agent 工具）
- M3：数据聚合工具集 + 长文生成 + 渲染导出 + D7 审计回放作"依据可回溯"亮点（D7 在 M3 自然续上）

### M1 分包与状态（截至 2026-11-07）
| 步骤 | 内容 | 状态 |
|---|---|---|
| M1-0 | pom/yml：Java 21 + g-aios 依赖 + 日志桥接 + models-dir | ✅ 验收 |
| M1-1 | GaiosConfig Bean（G-AIOS 启动装配一次共享） | ✅ 验收 |
| M1-2 | RegulationSearchTool（knowledge_items 检索工具过安检注册） | ✅ 验收（6/6 单测） |
| M1-2.5a | logback 按天滚动 + MDC traceId 过滤器 | ✅ 验收（curl 实证 X-Trace-Id） |
| M1-2.5b | Flyway + V1__init.sql + 国产库预留改造 | ✅ 验收（MySQL 5.7 空库演练） |
| M1-2.5c | actuator health + gaios 自定义探针 | ✅ 验收（全 UP） |
| M1-2.5d | 单测体系（surefire） | ✅ 验收 |
| M1-3a | G-AIOS 旋钮：maxTurns 构造参数 + createAgent(int) + DeepSeekCore 重试退避/UsageListener + 安检三态 fail-closed | ✅ 收口（12/12 测试绿，标定 22/22，web 吃新 jar 验证） |
| **M1-3b** | **web AgentService：每请求 createAgent(4)、失败降级普通模式、UsageListener 真实 token 进 call_logs、轮数/总时预算可配** | **🔄 代码已交付待验证**（更新包 4 件：AgentService.java / AgentServiceTest.java 6 用例 / GaiosConfig fail-fast 替换 / yml 片段 gaios.agent.*） |
| M1-3c | /api/assistant/askAgent 端点 + 前端同题对比开关 | ⬜ 下一步 |
| M1-4 | 前端长等待 UX + markdown XSS 消毒 + meta referrer no-referrer | ⬜ |
| M1-5 | E5 向量检索替换关键词 LIKE（复用 components.e5()） | ⬜ |

### M1-3b 验收步骤（接手人照做）
1. 更新包按目录覆盖 → **全局搜 `REMOVED`** 确认无打码残留 → Rebuild
2. `mvn test` → 预期 AgentServiceTest 6/6 + RegulationSearchToolTest 6/6 全绿
3. 启动应用确认 `[GaiosConfig] web 工具注册完成，隔离区: []`
4. 真实 token 计量验证留到 M1-3c 端点通了以后：curl 后查 call_logs 表 agent 行 usage_amount 非 0

### D6/D7（运行时侧，已完成/冻结）
- **D6 Context 升级** ✅：结构化摘要 + 滑动窗口（交付 zip + 场景 A/B 对比图在文件区）
- **D7 换出机制** ❄️ 冻结在第 2 步：SwapEvent/SwapStore/NoopSwapStore + FileSwapStore 已贴出未确认编译；第 3 步（Context 接入）未给码。**M3 时续**

---

## 三、进行中的未结案事项（重要！）

### 1. 控制台中文乱码 / `Picked up JAVA_TOOL_OPTIONS: -Dfile.encoding=GBK`（排查到一半）
**现象**：IDEA 里跑 Maven 首行必打 `Picked up JAVA_TOOL_OPTIONS: -Dfile.encoding=GBK`，导致 JVM 默认字符集被掰回 GBK，logback 输出 GBK 字节被 IDEA 控制台按 UTF-8 解码 → 中文乱码。
**已排除（全部干净）**：系统/用户/进程三层环境变量、注册表 HKCU/HKLM、外部 cmd（mvn -v 无此行）、两个 mvn.cmd（bundled + C:\code\env\maven）、G-AIOS pom、G-AIOS 的 .idea、两个 IDEA 窗口的进程环境（Get-ChildItem Env 无 GBK）。
**结论方向**：变量是 IDEA"点按钮"启动构建 JVM 瞬间注入的，与项目无关（两个工程都中招）。
**接手后三步**：
1. G-AIOS 工程全量扫（.ipr 老格式盲区）：`Get-ChildItem H:\codes\G-AIOS -Recurse -Include *.xml,*.ipr,*.iml,*.iws -File | Select-String "JAVA_TOOL_OPTIONS" -List | Select -ExpandProperty Path`
2. 应用级配置扫：`findstr /S /I /C:"JAVA_TOOL_OPTIONS" "%APPDATA%\JetBrains\*.xml"`
3. 复现现场：确认点的是哪个入口（Maven 工具窗口/绿箭头/运行配置）+ Runner 面板整屏截图
   **止血方案**：Maven → Runner → VM options 加 `-Dfile.encoding=UTF-8`（命令行优先级高于 JAVA_TOOL_OPTIONS，乱码立消但源头未除）。

### 2. G-AIOS git 待办
- 第三笔 `fix(llm)` commit 未打（REMOVED 修复），前两笔 feat(llm)/feat(security) 已打；commit 保持标注论文章节；论文复现里程碑打 tag（如 d7.1），master 向生产演进

---

## 四、欠账清单

| 欠账 | 说明 | 处理时机 |
|---|---|---|
| E5 margin=-0.038 两组样本交叉 | 零样本锚点方案天花板，不影响 22/22 | M1-5 |
| settings.xml activeProfiles 嵌套警告 | 无害 | 顺手 |
| 根目录 db/schema.sql 待删 | Flyway V1 已接管 | 顺手 |
| 原 smartsiteai 库表结构与 V1 漂移 | baseline-on-migrate 已基线化；将来重建以 V1 为准 | 重建时 |
| usageDaily 接口返回未贴 | M1-2.5 遗留验证 | 顺手 |
| agent 超时 cancel 后 worker 空转剩余轮数 | 最多再烧 3 轮 token；协作式取消需内核支持 | 阶段2 内核 |
| DeepSeekCore 模型名未暴露 | call_logs model 列暂写常量 "deepseek" | 阶段2 内核 |
| SimpleReactAgent 哨兵串耦合 | AgentService 按"超过最大轮数"前缀判失败；应改结构化 AgentResult | 阶段2 内核 |
| 远期微调 | e5-base + Smile 逻辑回归（Java 栈），论文 96% 路径 | 远期 |

---

## 五、工程纪律（本项目铁律）

1. **每步可跑才往下**；先手写再引库；commit 标注对应论文章节
2. **魔法数字注释必须带"取值依据 + 失效条件"**
3. 错误路径打印原文（防 NPE 掩盖 API 错误）；受检异常只在调用方能合理处理时使用
4. **密钥只进 app.properties 且 gitignore**（用户放 properties/ 子目录，.gitignore 需加 `properties/app.properties`）
5. **凡贴码含 JSON 字段名，应用后全局搜 `REMOVED` 再编译**（2026-11-07 事故：传输层把 JSON 键名打码成字面量 REMOVED，造成全员 422）
6. 测试类必须在 src/test/java（test 作用域依赖对 main 不可见）
7. 国产库预留 SQL 纪律：禁 `DEFAULT (now())` 一律 `DEFAULT CURRENT_TIMESTAMP`；禁 `DATE_FORMAT` 等方言函数（下沉 Java 聚合）；不用反引号；方言脚本按 db/mysql、db/dm8 目录隔离预案
8. 用户本地 **MySQL 是 5.7**（Flyway 警告超出社区支持范围=信创迁移论据）；CHECK 约束在 5.7 静默忽略
9. 双仓库分离：G-AIOS 论文复现公开路线 vs web 业务 demo 私有；代码同步走贴文件/zip

---

## 六、环境要点

- **JDK 双轨**：个人项目 JDK 21（C:\code\env\jdk21），公司 JDK8 不动（C:\code\env\jdk）；临时切换 `$env:JAVA_HOME`。外部终端默认公司 JDK8，跑 G-AIOS/SmartSiteAI 构建前必须 `set JAVA_HOME=C:\code\env\jdk21`
- **工作目录依赖**：G-AIOS 的 E5 models 路径（H:\codes\G-AIOS\models）与 app.properties 经 GaiosConfig 显式配置化（gaios.models-dir）；web 工程启动日志应见 `[AppConfig] 配置来源：工作目录 H:\codes\SmartSiteAI\properties\app.properties`
- **web 工程**：H:\codes\SmartSiteAI，端口 8087，Spring Boot 3.4.5 + Java 21 + MyBatis + MySQL 5.7
- **Maven**：IDEA bundled Maven 3.9.11；公司 Maven 3.6.3（C:\code\env\maven，JDK8 专用）；settings.xml 在 H:\codes\CompantyAIResearch\AgentDemo\SpringAgent\settings.xml，本地仓库 H:\repository\springagent
- **启动耗时参考**：~46s（DJL 原生库 ~16s + E5 加载 ~7s），demo 可接受
- **agent 链路行为事实**：恒定走真实 DeepSeek，不受 model.mode=mock 影响（mock 纯演示配 gaios.agent.enabled=false）
- **排障档案（证书三连）**：卡巴斯基 MITM → 根 CA 导入 jdk21 cacerts；schannel 吊销检查 → curl `--ssl-no-revoke` / git `http.schannelCheckRevoke false`；代理流量被拦 → clash 加白名单。git 仅对 github.com 走代理 127.0.0.1:7897

---

## 七、AI 协作恢复指南（新会话怎么接）

1. **入口口令**：直接说"继续 G-AIOS M1-3c"（或当前步骤号），AI 侧有项目记忆会自动对齐
2. **需要贴给 AI 的文件**（它读不了私有仓库）：
    - G-AIOS 侧：GaiosBootstrap / SimpleReactAgent / DeepSeekCore（当前版，注意别贴旧的）
    - web 侧：GaiosConfig / AssistantService / AgentService / 相关 mapper / 报错日志
    - 注意：temp 区留存的 ToolRegistry.java 等可能是旧版，贴码以本地当前版为准
3. **AI 产出纪律**：贴码或 zip 更新包；收到后先全局搜 REMOVED 再编译；Rebuild（不是 Run，增量编译有坑）
4. **验证习惯**：curl.exe（PowerShell 里 curl 是别名不认 -i）；启动日志、测试绿数、health 探针三件套

---

## 八、下一步行动（按优先级）

1. 结案乱码排查（见第三节三步 + 止血方案）
2. M1-3b 验收（四步，见第二节）
3. G-AIOS 第三笔 fix(llm) commit
4. **M1-3c**：/api/assistant/askAgent 端点 + 前端同题对比开关 + 真机验证 call_logs agent 行 usage 非 0
5. M1-4 前端加固 → M1-5 E5 向量检索 → M1 收口
