# DataBuff vs Pinpoint

同机实测对比 **DataBuff v0.1.4** 与 **Pinpoint 3.1.0**（192.168.50.140）。同机双跑：DataBuff 走 OTLP `:4318`（service-a / service-b）；Pinpoint 走官方 quickstart Java Agent（application=`pinpoint-quickapp`，Web `:18080` / Demo `:18085`）。标记：✅ 本环境可验证 · △ 有入口但本 lab 深度有限 · ❌ 无等价能力。

## 一、能力对照全表

**7 大 AI 能力**（v0.1.4：看得见 → 军团协同 → 会巡检 → 会诊断 → 会修复 → 会预测 → 会答疑）

| 能力项 | Pinpoint 3.1.0 | DataBuff v0.1.4 |
|--------|----------------|-----------------|
| ① 看得见 · 自然语言问系统 | ❌ | ✅ 中文问服务 / 拓扑 / 异常趋势，AI 直接读遥测作答 |
| ② 军团协同 · 多 Agent 协同 | ❌ | ✅ 多专家并行取证、串行保上下文；任务可编排复用 |
| ③ 会巡检 · 服务巡检 + 报告 | ❌ | ✅ 一句话巡检，输出带证据与处置建议的报告 |
| ④ 会诊断 · 瓶颈 / 根因取证 | ❌ | ✅ 结合 Trace / 指标 / 拓扑拼诊断证据 |
| ⑤ 会修复 · 运维专家处置 | ❌ | ✅ 策略允许 + 人工授权下执行修复 |
| ⑥ 会预测 · 容量 / 趋势 | ❌ | ✅ 容量与趋势分析 |
| ⑦ 会答疑 · 答疑专家 | ❌ | ✅ 检索产品文档与代码回答部署 / 接入问题 |
| 外部拓展 · MCP / Skill / 自定义专家 | ❌ | ✅ 外接 MCP、Skill，并可自定义数字专家 |

这是差距最大的一组：Pinpoint 无 AI 平台；DataBuff 把 APM 数据直接当作 AI 上下文。

**应用性能（APM）**

| 能力项 | Pinpoint 3.1.0 | DataBuff v0.1.4 |
|--------|----------------|-----------------|
| 1. 全局拓扑 | ✅ Server Map（含 USER→App、链路吞吐/耗时） | ✅ 全局拓扑 + 健康色标 + 节点下钻 |
| 2. 服务列表和黄金指标 | ✅ Apdex / Success·Failed / Response Summary | ✅ 服务列表 + 曲线；同 demo 可见 service-a / b |
| 3. 服务级拓扑 | ✅ Server Map 节点视角 | ✅ 服务级拓扑 |
| 4. 服务级调用分析（上下游指标 + 关联 Trace） | ❌ Scatter/Call Tree 偏单请求，无「上下游贡献」分析页 | ✅ 上下游调用结构与耗时 / 贡献；可直接落到 Trace |
| 5. 实例级黄金指标 | △ VIEW SERVERS 可见 Agent；Inspector 本 lab API 未就绪 | ✅ 实例级黄金指标曲线 / 列表 |
| 6. 实例级拓扑 | ❌ | ✅ 独立实例级拓扑 |
| 7. 实例级调用分析（上下游指标 + 关联 Trace） | ❌ | ✅ 按实例看上下游调用与耗时；可落到 Trace |
| 8. 接口级拓扑 | ❌ | ✅ 独立接口级拓扑 |
| 9. 接口级调用分析（上下游指标 + 关联 Trace） | △ URL Statistic 有入口；本 lab API 404 | ✅ 按接口看调用方 / 被调与耗时；可落到 Trace |
| 10. 服务流（服务级 / 接口级 Trace 链路分析） | ❌ Server Map 回答「连谁 / 多少次」 | ✅ 按入口展开下游响应贡献度；可关联 Trace |
| 11. 中间件 / 外部调用专页（库 / 缓存 / MQ / 外部服务） | △ 拓扑可出现中间件节点，无专页纵深 | ✅ 独立专页：数据库 / 缓存 / MQ / 外部服务 |
| 12. 错误分析（统计 + 接口级） | △ Error Analysis 有入口；本 lab API 404 | ✅ 独立错误分析统计 + 接口级错误下钻 |
| 13. Trace 列表 / 搜索 | ✅ Scatter 框选 → Transaction List | ✅ 图表 + 列表，多维过滤 |
| 14. Trace 详情 | ✅ Call Tree / Server Map / Flame Graph；方法级字节码栈深 | ✅ 调用次序瀑布图 + Span 属性 |
| 15. Trace Span 关联日志 | ❌ | ✅ 顶栏「日志分析」+ Span Logs / 「日志」Tab |
| 16. 日志列表 / 搜索 | ❌ 无 OTLP/业务日志平台 | ✅ |
| 17. 日志详情 | ❌ | ✅ |
| 18. 日志关联 Trace | ❌ | ✅ Log → Trace，并可落到具体 Span |
| 19. Profiling | △ 侧重 Java 调用栈 / Active Thread 等 | ❌ 暂不支持 |
| 20. 仪表盘（可定制 Dashboard） | ❌ 无等价可定制大盘 | ❌ 暂不支持 |
| 接入协议 / 语言 | 专有 Java Agent（字节码增强） | OTLP 多语言 + SkyWalking gRPC |

Pinpoint 明显更强在 **Java 方法级 Call Tree** 与 **Server Map + Scatter + Apdex 一体视图**。DataBuff 领先在 **多语言 OTel**、**服务/实例/接口调用分析与服务流**、**中间件专页**、**日志↔Trace** 与 **AI**。Inspector / URL Statistic / Error Analysis 本 lab 最小 compose 入口在、API 未就绪，按 △ 计。

**告警**

| 能力项 | Pinpoint 3.1.0 | DataBuff v0.1.4 |
|--------|----------------|-----------------|
| 规则怎么配 | △ Administration 下有 Alarm / Webhook 等入口 | ✅ 告警中心内配置，产品化入口 |
| 阈值告警 | △ 依赖管理端配置 | ✅ 阈值规则可在平台内管理 |
| 智能告警 | ❌ | ✅ 智能告警入口，与 APM 指标联动 |
| 告警事件列表 | △ | ✅ 告警列表（等级 / 服务 / 时间）；本环境非空 |
| 告警落到服务 / 中间件 | △ 多靠通知通道 | ✅ 列表直接挂服务 / 中间件，可回 APM 下钻 |

**适用场景速查**

| 场景 | 更适合 | 说明 |
|------|--------|------|
| 纯 Java，要方法级 Call Tree / Flame Graph | Pinpoint | 字节码增强栈深，本环境 Call Tree 可证 |
| 要 Server Map + Scatter + Apdex 一体排查 | Pinpoint | 拓扑与散点同页，框选进 Transaction |
| 已深度绑定 Pinpoint 插件 / 手动埋点 API | Pinpoint | 换 Agent 成本高，继续用合理 |
| 需要 7 大 AI 能力 | DataBuff | Pinpoint 无等价 AI 平台 |
| 多语言 / 已有 OTel 或 SW Agent | DataBuff | OTLP + SW gRPC；Pinpoint 以 Java 为主 |
| 要服务流 / 调用分析落到 Trace | DataBuff | Pinpoint 无等价贡献度分析页 |
| 要慢 SQL / 缓存 / MQ 专页 + 日志关联 | DataBuff | Pinpoint 无日志平台与专页纵深 |
| 只要看 Java 调用链，不要 AI | 两者皆可 | 不必为换品牌硬迁 |

**客观边界：** Pinpoint 在 Java 深度调用栈与经典 Server Map 体验上仍然扎实。DataBuff 适合「多语言 OTel + AI + APM 纵深」；从 Pinpoint 迁出意味着换 Agent（专有 → OTel），迁移成本高于「只改 SW 上报地址」。

## 二、截图证据（解释上表）

下列截图均来自 **192.168.50.140**。

**7 大 AI 能力**（Pinpoint 无等价界面，以 DataBuff 举证）

![DataBuff 7 大 AI 能力首页](../images/vs-pp-databuff-ai-home.png)

![DataBuff AI 对话](../images/vs-pp-databuff-ai-chat.png)

![DataBuff 数字专家](../images/vs-pp-databuff-ai-experts.png)

**拓扑 / Server Map / 实例**

![Pinpoint Server Map](../images/vs-pp-pinpoint-server-map.png)

![DataBuff 全局拓扑](../images/vs-pp-databuff-topology.png)

![Pinpoint VIEW SERVERS](../images/vs-pp-pinpoint-app-overview.png)

![DataBuff 服务列表](../images/vs-pp-databuff-services.png)

**Trace：Call Tree vs 瀑布图**

![Pinpoint Call Tree](../images/vs-pp-pinpoint-call-tree.png)

![DataBuff Trace 详情](../images/vs-pp-databuff-trace-detail.png)

![DataBuff 链路追踪](../images/vs-pp-databuff-trace-list.png)

**服务级 / 接口级调用分析 + 服务流**（Pinpoint 无等价页）

![DataBuff 服务级调用分析](../images/vs-pp-databuff-service-call-analysis.png)

![DataBuff 接口级调用分析](../images/vs-pp-databuff-api-call-analysis.png)

![DataBuff 服务流](../images/vs-pp-databuff-service-flow.png)

**日志**（Pinpoint 无日志平台）

![DataBuff 日志分析](../images/vs-pp-databuff-logs.png)

**DataBuff 专页纵深**

![数据库](../images/vs-pp-databuff-database.png)

![缓存](../images/vs-pp-databuff-cache.png)

![消息队列](../images/vs-pp-databuff-mq.png)

![外部服务](../images/vs-pp-databuff-external.png)

![接口分析](../images/vs-pp-databuff-api.png)

![错误分析](../images/vs-pp-databuff-errors.png)

**告警**

![Pinpoint Administration](../images/vs-pp-pinpoint-administration.png)

![DataBuff 告警中心](../images/vs-pp-databuff-alerts.png)

## 延伸阅读

- [快速入门：Docker 安装部署](/docs/zh/guide/docker-install)
- [Agent 集成：Java OTel](/docs/zh/manual/agent-integration)
- [迁移指南：从 Pinpoint 到 DataBuff](/docs/zh/migration/from-pinpoint)（即将发布）

欢迎 Star：https://github.com/databufflabs/databuff
