# DataBuff vs SkyWalking

> 对比文档 · [English](./vs-skywalking_en.md)

同机实测对比 **DataBuff v0.1.4** 与 **SkyWalking 10.4.0**（192.168.50.140）。同一 Demo（service-a / service-b）：DataBuff 走 OTLP `:4318`，SkyWalking 走 Agent gRPC `:31180`。标记：✅ 本环境可验证 · △ 有入口但深度有限 · ❌ 无等价能力。

博客成稿（HTML + 全量截图）：[DataBuff vs SkyWalking：同环境实测对比](https://databuff.ai/blog/databuff-vs-skywalking)

## 7 大 AI 能力

| 能力项 | SkyWalking 10.4.0 | DataBuff v0.1.4 |
|--------|-------------------|-----------------|
| ① 看得见 · 自然语言问系统 | ❌ | ✅ 中文问服务 / 拓扑 / 异常趋势，AI 直接读遥测作答 |
| ② 军团协同 · 多 Agent 协同 | ❌ | ✅ 多专家并行取证、串行保上下文；任务可编排复用 |
| ③ 会巡检 · 服务巡检 + 报告 | ❌ | ✅ 一句话巡检，输出带证据与处置建议的报告 |
| ④ 会诊断 · 瓶颈 / 根因取证 | ❌ | ✅ 结合 Trace / 指标 / 拓扑拼诊断证据 |
| ⑤ 会修复 · 运维专家处置 | ❌ | ✅ 策略允许 + 人工授权下执行修复；危险命令 denylist |
| ⑥ 会预测 · 容量 / 趋势 | ❌ | ✅ 容量与趋势分析 |
| ⑦ 会答疑 · 答疑专家 | ❌ | ✅ 检索产品文档与代码，回答部署 / 接入 / 配置问题 |
| 外部拓展 · MCP / Skill / 自定义专家 | ❌ | ✅ 外接 MCP、Skill，并可自定义数字专家 |

这是差距最大的一组：SkyWalking 无等价 AI 平台；DataBuff 把 7 大能力做成可配置首页入口，APM 数据直接作 AI 上下文。

![DataBuff 7 大 AI 能力首页](../images/vs-skywalking/databuff-ai-home.png)

![DataBuff AI 对话](../images/vs-skywalking/databuff-ai-chat.png)

![DataBuff 数字专家](../images/vs-skywalking/databuff-ai-experts.png)

## 应用性能（APM）

| # | 能力项 | SkyWalking 10.4.0 | DataBuff v0.1.4 |
|---|--------|-------------------|-----------------|
| 1 | 全局拓扑 | ✅ Topology（含中间件节点） | ✅ 全局拓扑 + 健康色标 + 节点下钻 |
| 2 | 服务列表和黄金指标 | ✅ Apdex / 成功率 / 延迟 / Load | ✅ 服务列表 + 曲线 |
| 3 | 服务级拓扑 | ✅ | ✅ |
| 4 | 服务级调用分析（上下游 + 关联 Trace） | ❌ | ✅ |
| 5 | 实例级黄金指标 | ✅ | ✅ |
| 6 | 实例级拓扑 | ❌ | ✅ |
| 7 | 实例级调用分析（上下游 + 关联 Trace） | ❌ | ✅ |
| 8 | 接口级拓扑 | ❌ | ✅ |
| 9 | 接口级调用分析（上下游 + 关联 Trace） | ❌ | ✅ |
| 10 | 服务流（服务级 / 接口级 Trace 链路分析） | ❌ | ✅ 按入口展开下游响应贡献度 |
| 11 | 中间件 / 外部调用专页（库 / 缓存 / MQ / 外部） | ❌ 拓扑可见节点，无专页纵深 | ✅ 独立专页 |
| 12 | 错误分析（统计 + 接口级） | ❌ | ✅ |
| 13 | Trace 列表 / 搜索 | ✅ | ✅ |
| 14 | Trace 详情 | ✅ | ✅ |
| 15 | Trace Span 关联日志 | ❌ | ✅ |
| 16 | 日志列表 / 搜索 | ✅ | ✅ |
| 17 | 日志详情 | ✅ | ✅ |
| 18 | 日志关联 Trace | ✅ Log → Trace | ✅ Log → Trace，并可落到具体 Span |
| 19 | Profiling（Tracing / AsyncProfiler / eBPF） | ✅ 三类均支持 | ❌ 暂不支持 |
| 20 | 仪表盘（可定制 Dashboard） | ✅ 含服务及中间件大盘 | ❌ 暂不支持 |

基础面两侧都有；DataBuff 领先在调用分析（服务 / 实例 / 接口）、实例与接口级拓扑、服务流、专页与错误分析、Span↔日志双向。Profiling 与可定制仪表盘是 SkyWalking 明显更强、DataBuff 暂未覆盖的两块。

### 服务与拓扑

![SkyWalking 服务列表](../images/vs-skywalking/skywalking-services.png)

![DataBuff 服务列表](../images/vs-skywalking/databuff-services.png)

![SkyWalking Topology](../images/vs-skywalking/skywalking-topology.png)

![DataBuff 全局拓扑](../images/vs-skywalking/databuff-topology.png)

### 调用分析与服务流

![DataBuff 服务级调用分析](../images/vs-skywalking/databuff-service-call-analysis.png)

![DataBuff 接口级调用分析](../images/vs-skywalking/databuff-api-call-analysis.png)

![DataBuff 服务流](../images/vs-skywalking/databuff-service-flow.png)

### Trace / Log

![SkyWalking Trace 列表](../images/vs-skywalking/skywalking-trace-list.png)

![DataBuff 链路追踪](../images/vs-skywalking/databuff-trace-list.png)

![SkyWalking Trace 详情](../images/vs-skywalking/skywalking-trace-detail.png)

![DataBuff Trace 详情（可关联日志）](../images/vs-skywalking/databuff-trace-detail.png)

![SkyWalking Log→Trace](../images/vs-skywalking/skywalking-logs.png)

![DataBuff Log→Trace（可落到 Span）](../images/vs-skywalking/databuff-logs.png)

### 仪表盘（SkyWalking 优势）

![SkyWalking Dashboard List](../images/vs-skywalking/skywalking-dashboard-list.png)

### DataBuff 专页纵深

![数据库](../images/vs-skywalking/databuff-database.png)

![缓存](../images/vs-skywalking/databuff-cache.png)

![消息队列](../images/vs-skywalking/databuff-mq.png)

![外部服务](../images/vs-skywalking/databuff-external.png)

![接口分析](../images/vs-skywalking/databuff-api.png)

![错误分析](../images/vs-skywalking/databuff-errors.png)

## 告警

| 能力项 | SkyWalking 10.4.0 | DataBuff v0.1.4 |
|--------|-------------------|-----------------|
| 规则怎么配 | △ 改 OAP `alarm-settings.yml`（或动态配置）；UI 不建规则 | ✅ 告警中心内配置 |
| 阈值告警 | △ 支持，靠后端 YAML / MQE 维护 | ✅ 平台内管理 |
| 智能告警 | ❌ | ✅ |
| 告警事件列表 | △ UI 可查；本环境空态 | ✅ 本环境非空 |
| 告警落到服务 / 中间件 | △ 多靠 hooks，回 APM 要自串 | ✅ 列表挂服务 / 中间件，可回 APM |

![SkyWalking Alerting](../images/vs-skywalking/skywalking-alerting.png)

![DataBuff 告警中心](../images/vs-skywalking/databuff-alerts.png)

## 适用场景速查

| 场景 | 更适合 | 说明 |
|------|--------|------|
| 已有大量 SW Agent，想先看 AI / 专页 | DataBuff（并跑） | 改上报地址即可 |
| 需要 7 大 AI 能力 | DataBuff | SkyWalking 无等价 AI 平台 |
| 要外接 MCP / Skill 或自定义数字专家 | DataBuff | SW 无此层 |
| 要按入口看「谁拖慢了响应」 | DataBuff | 服务流 + 响应贡献度 |
| 要从服务 / 实例 / 接口调用分析落到 Trace | DataBuff | SW 无此路径 |
| 要查慢 SQL / 缓存 / MQ 专页 | DataBuff | SW 多为拓扑节点级 |
| 要做 Tracing / AsyncProfiler / eBPF Profiling | SkyWalking | DataBuff 暂不支持 |
| 要可定制仪表盘 / 中间件大盘 | SkyWalking | DataBuff 暂不支持 |
| 只要轻量 Trace、不要 AI | 两者皆可 | 不必为换品牌迁移 |

**客观边界：** 已深度绑定 SW 插件、或强依赖 Profiling / 可定制仪表盘时，继续用 SkyWalking 完全合理。DataBuff 适合「Agent 可保留 + AI + APM 纵深」的并跑或渐进切换。

## 延伸阅读

- [SkyWalking 接入](/docs/zh/manual/skywalking-ingestion)
- [迁移指南：从 SkyWalking 到 DataBuff](/docs/zh/migration/from-skywalking)（即将发布）

欢迎 Star：https://github.com/databufflabs/databuff
