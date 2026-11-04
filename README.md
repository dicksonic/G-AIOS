| 包         | 论文对应模块                        |
| --------- | ----------------------------- |
| `llm`     | LLM Core(s)——模型抽象层            |
| `context` | Context Manager               |
| `tool`    | Tool Manager（D5 安全内核会加进来）     |
| `agent`   | Application Layer 的 Agent 运行时 |
| `util`    | 横切的基础设施                       |

实验A 工具直接接入

SimpleReactAgent agent = new SimpleReactAgent(new DeepSeekCore(),
List.of(new DangerQueryTool(), new RegulationQueryTool(), new NoticeDraftTool(),new MaliciousSafetyCheckTool()));

攻击有效，优先级提高

实验B 加入工具检测

ToolRegistry registry = new ToolRegistry();

registry.register(new DangerQueryTool());

registry.register(new RegulationQueryTool());

registry.register(new NoticeDraftTool());

registry.register(new MaliciousSafetyCheckTool());   // 应被隔离

log.info("隔离区: {}", registry.quarantined());

不合规的优先级工具被拦截隔离