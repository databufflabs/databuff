# Roadmap

## 已交付 · v0.1

```mermaid
flowchart LR
  APM["APM 底座<br/>功能完善 · 告警完善 · 架构极简"]
  AI["AI 平台<br/>多智能体 · 问数 · 巡检"]
  Deploy["一键部署<br/>Docker · K8s"]
  APM --- AI --- Deploy
```

**国产开源 AI 原生 OpenTelemetry APM**，核心闭环已跑通。

---

## 下一阶段 · 核心规划

按优先级推进以下五项，打通「采集 → 观测 → 智能」完整链路：

```mermaid
flowchart LR
  OneAgent["① One-Agent 开源"]
  OTelLogs["② OTel 日志"]
  eBPF["③ eBPF 无侵入采集"]
  AgentObs["④ Agent 观测"]
  AgentMem["⑤ AI Agent 记忆"]
  OneAgent --> OTelLogs --> eBPF
  eBPF --> AgentObs --> AgentMem
```

| # | 方向 | 目标 |
|---|------|------|
| **①** | **One-Agent 开源** | 统一采集 Agent 开源发布，Trace / 指标 / 日志 / 主机指标一站式上报，开箱对接 DataBuff Ingest |
| **②** | **OpenTelemetry 日志** | 接入 OTLP Logs，支持 OpenTelemetry 生态的日志采集与 Trace 关联，补齐可观测性三支柱 |
| **③** | **eBPF 无侵入 APM** | 基于 eBPF 的内核级采集，零代码改动覆盖主机、容器、K8s 场景，补充 Agent 难以触达的运行时 |
| **④** | **Agent 观测** | 对 AI Agent 本身做可观测——调用链、Token 消耗、工具调用、延迟与错误，让 Agent 运行可追踪、可诊断 |
| **⑤** | **AI Agent 记忆** | 为平台内多智能体引入持久化记忆，跨会话保留上下文与诊断结论，让 AI 越用越懂你的系统 |

---

## 近期规划

| 方向 | 目标 |
|------|------|
| **AI 更深** | 流式对话、更多数字专家、因果根因分析 |
| **APM 更全** | 日志关联、RUM、更多中间件覆盖 |
| **告警更强** | 组合规则、事件处理流程、通知集成 |
| **部署更稳** | 多节点集群、持久化方案、Helm Chart |

---

## 长期愿景

```mermaid
flowchart TD
  Today["今天<br/>AI 帮你查数据、做分析"]
  Next["近期<br/>AI 帮你定位根因"]
  Future["未来<br/>AI 自主巡检 → 诊断 → 处置"]
```

**从「AI 辅助看」到「AI 自主管」** —— 这是 DataBuff 的终极方向。
