# DataBuff vs SigNoz

> 对比文档 · [English](./vs-signoz_en.md)

同机实测对比 **DataBuff v0.1.4** 与 **SigNoz v0.133.0**（192.168.50.140）。同一 Demo（service-a / service-b）：DataBuff 走 OTLP `:4318`，SigNoz 走 OTLP `:24318`。标记：✅ 本环境可验证 · △ 有入口但深度有限 · ❌ 无等价能力。

博客成稿（HTML + 全量截图）：[DataBuff vs SigNoz：同环境实测对比](https://databuff.ai/blog/zh/databuff-vs-signoz)

## 一、能力对照全表

**7 大 AI 能力**（v0.1.4：看得见 → 军团协同 → 会巡检 → 会诊断 → 会修复 → 会预测 → 会答疑）

| 能力项 | SigNoz v0.133.0 | DataBuff v0.1.4 |
|--------|-----------------|-----------------|
| ① 看得见 · 自然语言问系统 | ❌ | ✅ 中文问服务 / 拓扑 / 异常趋势，AI 直接读遥测作答 |
| ② 军团协同 · 多 Agent 协同 | ❌ | ✅ 多专家并行取证、串行保上下文；任务可编排复用 |
| ③ 会巡检 · 服务巡检 + 报告 | ❌ | ✅ 一句话巡检，输出带证据与处置建议的报告 |
| ④ 会诊断 · 瓶颈 / 根因取证 | ❌ | ✅ 结合 Trace / 指标 / 拓扑拼诊断证据（非黑盒一句「根因」） |
| ⑤ 会修复 · 运维专家处置 | ❌ | ✅ 策略允许 + 人工授权下执行修复；危险命令 denylist |
| ⑥ 会预测 · 容量 / 趋势 | ❌ | ✅ 容量与趋势分析，从事后排障拉到事前预判 |
| ⑦ 会答疑 · 答疑专家 | ❌ | ✅ 检索产品文档与代码，回答部署 / 接入 / 配置问题 |
| 外部拓展 · MCP / Skill / 自定义专家 | ❌ | ✅ 外接 MCP、Skill，并可自定义数字专家扩展排障能力 |

这是差距最大的一组：SigNoz 首页是 Traces / Metrics / Logs Explorer，无等价 AI 平台；DataBuff 把 7 大能力做成可配置入口，APM 数据直接作 AI 上下文。

**应用性能（APM）**

| 能力项 | SigNoz v0.133.0 | DataBuff v0.1.4 |
|--------|-----------------|-----------------|
| 1. 全局拓扑 | ✅ Service Map（含中间件节点） | ✅ 全局拓扑 + 健康色标 + 节点下钻 |
| 2. 服务列表和黄金指标 | ✅ Services（P99 / Error / OPS 等） | ✅ 服务列表 + 曲线；同 demo 可见 service-a / b |
| 3. 服务级拓扑 | △ 依赖 Service Map，无独立服务级拓扑页 | ✅ 服务级拓扑 |
| 4. 服务级调用分析（上下游指标 + 关联 Trace） | ❌ | ✅ 上下游调用结构与耗时 / 贡献；可直接落到 Trace |
| 5. 实例级黄金指标 | ❌ | ✅ 实例级黄金指标曲线 / 列表 |
| 6. 实例级拓扑 | ❌ | ✅ 独立实例级拓扑 |
| 7. 实例级调用分析（上下游指标 + 关联 Trace） | ❌ | ✅ 按实例看上下游调用与耗时；可直接落到 Trace |
| 8. 接口级拓扑 | ❌ | ✅ 独立接口级拓扑 |
| 9. 接口级调用分析（上下游指标 + 关联 Trace） | ❌ 多靠 Traces 筛选 | ✅ 按接口看调用方 / 被调与耗时；可直接落到 Trace |
| 10. 服务流（服务级 / 接口级 Trace 链路分析） | ❌ Service Map 只回答「连谁」 | ✅ 按入口展开下游响应贡献度 |
| 11. 中间件 / 外部调用专页（库 / 缓存 / MQ / 外部服务） | ❌ Map 可见节点，无专页纵深 | ✅ 独立专页：数据库 / 缓存 / MQ / 外部服务 |
| 12. 错误分析（统计 + 接口级） | ❌ 多靠 Traces 筛选 | ✅ 独立错误分析统计 + 接口级错误下钻 |
| 13. Trace 列表 / 搜索 | ✅ Traces Explorer | ✅ 图表 + 列表，多维过滤 |
| 14. Trace 详情 | ✅ Span 时间轴 / 属性 | ✅ 调用次序瀑布图 + Span 属性 |
| 15. Trace Span 关联日志 | ✅ Trace 详情可关联日志 | ✅ 顶栏「日志分析」+ Span Logs / 「日志」Tab |
| 16. 日志列表 / 搜索 | ✅ Logs Explorer | ✅ 日志分析 |
| 17. 日志详情 | ✅ | ✅ |
| 18. 日志关联 Trace | ✅ Log → Trace | ✅ Log → Trace，并可落到具体 Span |
| 19. 仪表盘（可定制 Dashboard） | ✅ Dashboards V2（含 Perses / PromQL 生态） | ❌ 暂不支持 |

基础面（Service Map / Services / Trace / Log / Span↔日志）两侧都有；DataBuff 领先在调用分析、服务流、中间件专页与错误分析，以及 Log→Trace 落到具体 Span。SigNoz 明显更强的是可定制仪表盘；Logs Explorer 也成熟可用。

**告警**

| 能力项 | SigNoz v0.133.0 | DataBuff v0.1.4 |
|--------|-----------------|-----------------|
| 规则怎么配 | ✅ Alert Rules UI 可建规则 | ✅ 告警中心内配置 |
| 阈值告警 | ✅ 支持 | ✅ 平台内管理 |
| 同环比告警 | ❌ | ✅ |
| 告警事件列表 | ✅ Triggered Alerts 可查；本环境已触发 | ✅ 本环境非空 |
| 告警落到服务 / 中间件 | △ 可配通知；回 APM 专页要自串 | ✅ 列表挂服务 / 中间件，可回 APM |

两侧都有告警产品入口；本环境已配 SigNoz 阈值规则并触发。差异在同环比告警与告警回 APM 上下文。

**适用场景速查**

| 场景 | 更适合 | 说明 |
|------|--------|------|
| 同为 OTel，想先看 AI / APM 专页 | DataBuff（并跑） | 改 OTLP 地址即可 |
| 需要 7 大 AI 能力 | DataBuff | SigNoz 无等价 AI 平台 |
| 要外接 MCP / Skill 或自定义数字专家 | DataBuff | SigNoz 无此层 |
| 要按入口看「谁拖慢了响应」 | DataBuff | 服务流 + 响应贡献度 |
| 要从服务 / 实例 / 接口调用分析落到 Trace | DataBuff | SigNoz 无此路径 |
| 要查慢 SQL / 缓存 / MQ 专页 | DataBuff | SigNoz 多为 Map 节点级 |
| 要可定制仪表盘 / PromQL 大盘 | SigNoz | Dashboards V2；DataBuff 暂不支持 |
| 只要成熟 Trace / Logs Explorer | 两者皆可 / 偏 SigNoz | 不必为换品牌迁移 |

**客观边界：** 已深度绑定 SigNoz Dashboard / PromQL 工作流时，继续用 SigNoz 完全合理。DataBuff 适合「同一 OTel 数据 + AI + APM 纵深」的并跑或渐进切换。

## 二、截图证据（解释上表）

下列截图均来自 **192.168.50.140**。图注对应上表能力项。

**7 大 AI 能力**（SigNoz 无等价界面，以 DataBuff 举证）

![DataBuff 7 大 AI 能力首页](../images/vs-sn-databuff-ai-home.png)

![DataBuff AI 对话](../images/vs-sn-databuff-ai-chat.png)

![DataBuff 数字专家](../images/vs-sn-databuff-ai-experts.png)

**服务与拓扑**

![SigNoz Services](../images/vs-sn-signoz-services.png)

![DataBuff 服务列表](../images/vs-sn-databuff-services.png)

![SigNoz Service Map](../images/vs-sn-signoz-service-map.png)

![DataBuff 全局拓扑](../images/vs-sn-databuff-topology.png)

**事实对齐：** SigNoz Service Map 同样有中间件节点。差距在 DataBuff 的专页纵深与调用分析 / 服务流。

**服务级 / 接口级调用分析 + 服务流**（对应上表 4 / 9 / 10）

![DataBuff 服务级调用分析](../images/vs-sn-databuff-service-call-analysis.png)

![DataBuff 接口级调用分析](../images/vs-sn-databuff-api-call-analysis.png)

![DataBuff 服务流](../images/vs-sn-databuff-service-flow.png)

**Trace**

![SigNoz Traces Explorer](../images/vs-sn-signoz-traces.png)

![DataBuff 链路追踪](../images/vs-sn-databuff-trace-list.png)

![SigNoz Trace 详情](../images/vs-sn-signoz-trace-detail.png)

![DataBuff Trace 详情（可关联日志）](../images/vs-sn-databuff-trace-detail.png)

**Log**

![SigNoz Logs Explorer](../images/vs-sn-signoz-logs.png)

![DataBuff 日志分析](../images/vs-sn-databuff-logs.png)

**仪表盘**（对应上表 19；SigNoz 优势）

![SigNoz Dashboards V2](../images/vs-sn-signoz-dashboards.png)

**DataBuff 专页纵深**（对应上表 11 / 12）

![数据库](../images/vs-sn-databuff-database.png)

![缓存](../images/vs-sn-databuff-cache.png)

![消息队列](../images/vs-sn-databuff-mq.png)

![外部服务](../images/vs-sn-databuff-external.png)

![接口分析](../images/vs-sn-databuff-api.png)

![错误分析](../images/vs-sn-databuff-errors.png)

**告警**

![SigNoz Triggered Alerts](../images/vs-sn-signoz-alerts.png)

![DataBuff 告警中心](../images/vs-sn-databuff-alerts.png)

## 延伸阅读

- [SkyWalking 接入](/docs/zh/manual/skywalking-ingestion)（同为 OTel 选型时可对照）
- [迁移指南：从 SigNoz 到 DataBuff](/docs/zh/migration/from-signoz)（即将发布）

欢迎 Star：https://github.com/databufflabs/databuff
