# DataBuff vs SkyWalking

同机实测对比 **DataBuff v0.1.4** 与 **SkyWalking 10.4.0**（192.168.50.140）。同一 Demo（service-a / service-b）：DataBuff 走 OTLP `:4318`，SkyWalking 走 Agent gRPC `:31180`。标记：✅ 本环境可验证 · △ 有入口但深度有限 · ❌ 无等价能力。

## 一、能力对照全表

**7 大 AI 能力**（v0.1.4：看得见 → 军团协同 → 会巡检 → 会诊断 → 会修复 → 会预测 → 会答疑）

| 能力项 | SkyWalking 10.4.0 | DataBuff v0.1.4 |
|--------|-------------------|-----------------|
| ① 看得见 · 自然语言问系统 | ❌ | ✅ 中文问服务 / 拓扑 / 异常趋势，AI 直接读遥测作答 |
| ② 军团协同 · 多 Agent 协同 | ❌ | ✅ 多专家并行取证、串行保上下文；任务可编排复用 |
| ③ 会巡检 · 服务巡检 + 报告 | ❌ | ✅ 一句话巡检，输出带证据与处置建议的报告 |
| ④ 会诊断 · 瓶颈 / 根因取证 | ❌ | ✅ 结合 Trace / 指标 / 拓扑拼诊断证据（非黑盒一句「根因」） |
| ⑤ 会修复 · 运维专家处置 | ❌ | ✅ 策略允许 + 人工授权下执行修复；危险命令 denylist |
| ⑥ 会预测 · 容量 / 趋势 | ❌ | ✅ 容量与趋势分析，从事后排障拉到事前预判 |
| ⑦ 会答疑 · 答疑专家 | ❌ | ✅ 检索产品文档与代码，回答部署 / 接入 / 配置问题 |
| 外部拓展 · MCP / Skill / 自定义专家 | ❌ | ✅ 外接 MCP、Skill，并可自定义数字专家扩展排障能力 |

这是差距最大的一组：SkyWalking 无等价 AI 平台；DataBuff 把 7 大能力组织成可配置首页入口，APM 数据直接作 AI 上下文，并支持外接 MCP / Skill 与自定义数字专家。

**应用性能（APM）**

| 能力项 | SkyWalking 10.4.0 | DataBuff v0.1.4 |
|--------|-------------------|-----------------|
| 1. 全局拓扑 | ✅ Topology（含中间件节点） | ✅ 全局拓扑 + 健康色标 + 节点下钻 |
| 2. 服务列表和黄金指标 | ✅ Apdex / 成功率 / 延迟 / Load | ✅ 服务列表 + 曲线；同 demo 可见 service-a / b |
| 3. 服务级拓扑 | ✅ 服务拓扑 | ✅ 服务级拓扑 |
| 4. 服务级调用分析（上下游指标 + 关联 Trace） | ❌ | ✅ 上下游调用结构与耗时 / 贡献；可直接落到 Trace |
| 5. 实例级黄金指标 | ✅ Instance 指标（负载 / 延迟 / 成功率等） | ✅ 实例级黄金指标曲线 / 列表 |
| 6. 实例级拓扑 | ❌ | ✅ 独立实例级拓扑 |
| 7. 实例级调用分析（上下游指标 + 关联 Trace） | ❌ | ✅ 按实例看上下游调用与耗时；可直接落到 Trace |
| 8. 接口级拓扑 | ❌ | ✅ 独立接口级拓扑 |
| 9. 接口级调用分析（上下游指标 + 关联 Trace） | ❌ | ✅ 按接口看调用方 / 被调与耗时；可直接落到 Trace |
| 10. 服务流（服务级 / 接口级 Trace 链路分析） | ❌ 拓扑只回答「连谁」，无服务流 | ✅ 按入口展开下游响应贡献度；支持服务级 / 接口级 Trace 链路视角 |
| 11. 中间件 / 外部调用专页（库 / 缓存 / MQ / 外部服务） | ❌ 拓扑可见节点，无专页纵深 | ✅ 独立专页：数据库 / 缓存 / MQ / 外部服务 |
| 12. 错误分析（统计 + 接口级） | ❌ | ✅ 独立错误分析统计 + 接口级错误下钻 |
| 13. Trace 列表 / 搜索 | ✅ 服务 / 端点 / 状态 / 耗时过滤 | ✅ 图表 + 列表，多维过滤 |
| 14. Trace 详情 | ✅ Span 时间轴 / Tags | ✅ 调用次序瀑布图 + Span 属性 |
| 15. Trace Span 关联日志 | ❌ | ✅ 顶栏「日志分析」+ Span Logs / 「日志」Tab |
| 16. 日志列表 / 搜索 | ✅ | ✅ |
| 17. 日志详情 | ✅ | ✅ |
| 18. 日志关联 Trace | ✅ Log → Trace | ✅ Log → Trace，并可落到具体 Span |
| 19. Profiling（Tracing / AsyncProfiler / eBPF） | ✅ 三类均支持 | ❌ 暂不支持 |
| 20. 仪表盘（可定制 Dashboard） | ✅ 内置 Dashboard；支持服务及各类中间件大盘（如 DB / 缓存 / MQ 等） | ❌ 暂不支持 |

基础面（拓扑 / 服务列表 / Trace / 日志）两侧都有；DataBuff 领先在**服务级·实例级·接口级调用分析（含关联 Trace）**、**实例 / 接口级拓扑**、**服务流**、**专页与错误分析纵深**、**Span↔日志双向**。Profiling 与**可定制仪表盘（含各类中间件大盘）**是 SkyWalking 明显更强、DataBuff 暂未覆盖的两块。

**告警**

| 能力项 | SkyWalking 10.4.0 | DataBuff v0.1.4 |
|--------|-------------------|-----------------|
| 规则怎么配 | △ 改 OAP `alarm-settings.yml`（或动态配置）；UI 不建规则 | ✅ 告警中心内配置，产品化入口 |
| 阈值告警 | △ 支持，但靠后端 YAML / MQE 表达式维护 | ✅ 阈值规则可在平台内管理 |
| 智能告警 | ❌ 无等价「智能告警」产品能力 | ✅ 智能告警入口，与 APM 指标联动 |
| 告警事件列表 | △ UI 可查已触发告警；本环境空态 | ✅ 告警列表（等级 / 服务 / 时间等）；本环境非空 |
| 告警落到服务 / 中间件 | △ 触发后多靠 hooks 通知，回 APM 上下文要自串 | ✅ 列表直接挂服务 / 中间件，可回 APM 下钻 |

差异主要在两头：**怎么配**（后端文件 vs 告警中心）和**配完能干什么**（查事件 / hooks vs 列表 + 智能告警 + 回服务上下文）。

**适用场景速查**

| 场景 | 更适合 | 说明 |
|------|--------|------|
| 已有大量 SW Agent，想先看 AI / 专页 | DataBuff（并跑） | 改上报地址即可 |
| 需要 7 大 AI 能力（问数 / 巡检 / 诊断 / 修复 / 答疑等） | DataBuff | SkyWalking 无等价 AI 平台 |
| 要外接 MCP / Skill 或自定义数字专家 | DataBuff | AI 平台可外部拓展；SW 无此层 |
| 要按入口服务看「谁拖慢了响应」 | DataBuff | 服务流 + 响应贡献度；SW 无等价页 |
| 要从服务 / 实例 / 接口调用分析落到 Trace | DataBuff | 服务级 + 实例级 + 接口级调用分析均可关联 Trace；SW 无此路径 |
| 要查慢 SQL / 缓存 / MQ 专页 | DataBuff | SW 多为拓扑节点级 |
| 要做 Tracing / AsyncProfiler / eBPF Profiling | SkyWalking | 官方三类 Profiling；DataBuff 暂不支持 |
| 要可定制仪表盘 / 中间件大盘 | SkyWalking | 服务 + 各类中间件 Dashboard；DataBuff 暂不支持 |
| 只要轻量 Trace、不要 AI | 两者皆可 | 不必为换品牌迁移 |

**客观边界：** 已深度绑定 SW 插件、或强依赖 Profiling / 可定制仪表盘时，继续用 SkyWalking 完全合理。DataBuff 适合「Agent 可保留 + AI + APM 纵深」的并跑或渐进切换；Profiling 与仪表盘目前还不是对位能力。

## 二、截图证据（解释上表）

下列截图均来自 **192.168.50.140**（AI 首页图同 v0.1.4 产品入口实拍）。图注标明对应能力项；重点展示 DataBuff 多出来的 7 大 AI 能力 / 专页 / 告警。Profiling 为 SkyWalking 优势项（本篇未凑截图）；可定制仪表盘见下方 SkyWalking 举证，DataBuff 暂不支持。

**7 大 AI 能力**（对应上表；SkyWalking 无等价界面，以 DataBuff 举证）

![DataBuff 7 大 AI 能力首页](../images/vs-sw-databuff-ai-home.png)

![DataBuff AI 对话](../images/vs-sw-databuff-ai-chat.png)

![DataBuff 数字专家](../images/vs-sw-databuff-ai-experts.png)

**服务与拓扑**

![SkyWalking 服务列表](../images/vs-sw-skywalking-services.png)

![DataBuff 服务列表](../images/vs-sw-databuff-services.png)

![SkyWalking Topology](../images/vs-sw-skywalking-topology.png)

![DataBuff 全局拓扑](../images/vs-sw-databuff-topology.png)

**服务级 / 实例级 / 接口级调用分析 + 服务流**（对应上表 4 / 7 / 9 / 10）

SkyWalking 有全局 / 服务拓扑与实例指标，但**没有**服务级 / 实例级 / 接口级「调用分析」（含上下游指标与关联 Trace），也**没有**实例 / 接口级拓扑与服务流上的 Trace 链路分析。DataBuff 在 4、7、9 可落到 Trace；服务流（10）以入口 `service-a` 展开下游**响应贡献度**（如 service-b 约 58%）——从「看见连谁」到「谁拖慢、再点进 Trace」。

![DataBuff 服务级调用分析](../images/vs-sw-databuff-service-call-analysis.png)

![DataBuff 接口级调用分析](../images/vs-sw-databuff-api-call-analysis.png)

![DataBuff 服务流](../images/vs-sw-databuff-service-flow.png)

**Trace**

![SkyWalking Trace 列表](../images/vs-sw-skywalking-trace-list.png)

![DataBuff 链路追踪](../images/vs-sw-databuff-trace-list.png)

![SkyWalking Trace 详情](../images/vs-sw-skywalking-trace-detail.png)

![DataBuff Trace 详情（可关联日志）](../images/vs-sw-databuff-trace-detail.png)

**Log / Metric**（重点看关联粒度）

![SkyWalking Log→Trace](../images/vs-sw-skywalking-logs.png)

![DataBuff Log→Trace（可落到 Span）](../images/vs-sw-databuff-logs.png)

![SkyWalking Metrics](../images/vs-sw-skywalking-metrics.png)

**仪表盘**（对应上表 20；SkyWalking 优势，DataBuff 暂不支持）

![SkyWalking Dashboard List](../images/vs-sw-skywalking-dashboard-list.png)

**DataBuff 专页纵深**（对应上表数据库 / 缓存 / MQ / 外部 / 接口 / 错误；SkyWalking 无等价专页，不硬凑空对照）

![数据库](../images/vs-sw-databuff-database.png)

![缓存](../images/vs-sw-databuff-cache.png)

![消息队列](../images/vs-sw-databuff-mq.png)

![外部服务](../images/vs-sw-databuff-external.png)

![接口分析](../images/vs-sw-databuff-api.png)

![错误分析](../images/vs-sw-databuff-errors.png)

这些专页是「拓扑上能看见中间件」之后的纵深——相对 SkyWalking 最值得并跑验证的应用性能差异。

**告警**

![SkyWalking Alerting](../images/vs-sw-skywalking-alerting.png)

![DataBuff 告警中心](../images/vs-sw-databuff-alerts.png)

## 延伸阅读

- [SkyWalking 接入](/docs/zh/manual/skywalking-ingestion)
- [迁移指南：从 SkyWalking 到 DataBuff](/docs/zh/migration/from-skywalking)（即将发布）

欢迎 Star：https://github.com/databufflabs/databuff
