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