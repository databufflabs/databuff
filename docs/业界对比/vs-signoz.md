# DataBuff vs SigNoz

> 对比文档 · [切换到英文](./vs-signoz_en.md)

基于同一环境实测：两台独立 `ai-apm-demo` 容器分别向 DataBuff（v0.1.4，Doris 存储）与 SigNoz（v0.133.0，ClickHouse 25.12.5 存储）发送相同 OTLP HTTP 数据（service-a → service-b 调用链，含 MySQL / Redis / Kafka / ES / 外部 HTTP），逐页截图对比。服务器 192.168.50.140（8 核 / 32GB）。两家均为开源产品。

## 能力边界

| 维度 | SigNoz | DataBuff |
|------|--------|----------|
| **定位** | OTel 后端（Traces + Metrics + Logs） | AI Native OTel APM（Trace + Metrics + Log + AI） |
| **AI 能力** | ❌ 无内置 AI | ✅ AI 对话排障、智能问数、智能巡检、数字专家 |
| **APM 模块** | Services / Traces / Service Map / Logs / Dashboards | 12+ 专页：拓扑/服务/数据库/缓存/MQ/外部/接口/错误/Trace/日志/AI 平台等 |
| **中间件专页** | ❌ 无专页（Service Map 可展示中间件节点） | ✅ 数据库 / 缓存 / MQ / 外部服务独立页 |
| **内置存储** | ClickHouse 25.12.5 | Doris 4.1.1（列式存储） |
| **部署方式** | Docker Compose / Foundry / K8s | Docker Compose / K8s |
| **协议支持** | OTLP gRPC + HTTP | OTel + SkyWalking gRPC + Jaeger Thrift |
| **告警引擎** | ✅ 内置 Alert manager | ✅ 内置告警规则 |
| **仪表盘** | ✅ Dashboards V2（Perses） | ✅ 内置仪表盘 + 自定义 |
| **开源** | ✅ | ✅ |

## 客观差异

### 1. AI 能力：DataBuff 有，SigNoz 无

这是差距最大的维度。DataBuff 内置**完整 AI 平台**，APM 数据直接作为 AI 上下文，可用中文对话完成「拓扑 → 指标 → 链路」全流程排障。SigNoz v0.133.0 首页仅提供 Traces / Metrics / Logs Explorer 入口，无等价 AI 入口。

![DataBuff AI 对话首页](../images/databuff-ai-chat.png)

DataBuff AI 平台预设「查服务列表 / 查拓扑 / 查趋势 / 查异常」等快捷问题，降低上手门槛。

![DataBuff AI 专家面板](../images/databuff-ai-experts.png)

AI 平台配套的**数字专家**体系，让 APM 排障可编排、可复用——这是 SigNoz 完全不具备的产品层。

### 2. APM 模块广度

DataBuff「应用性能」下有 12+ 独立菜单，每种中间件类型、每种分析场景都有专属页面；SigNoz 同类信息压缩在 Services / Traces / Service Map 中。

| 模块 | DataBuff | SigNoz |
|------|----------|--------|
| 全局拓扑 | ✅ 含虚拟服务节点（MySQL / Redis / Kafka / ES / 外部） | △ Service Map 可展示中间件节点，缺少专页下钻 |
| 服务列表/详情 | ✅ 上下游 + 实例 + 接口下钻 | △ Services 表格式列表 |
| 数据库 | ✅ 独立页 + 慢 SQL 下钻 | ❌ 无专页 |
| 缓存 | ✅ 独立专页 | ❌ 无专页 |
| 消息队列 | ✅ 独立专页 | ❌ 无专页 |
| 外部服务 | ✅ 独立专页 | ❌ 无专页 |
| 接口分析 | ✅ 按接口聚合 P99 / 错误率 | △ 需在 Traces 筛选 |
| 错误分析 | ✅ 错误聚类 | △ 需在 Traces 筛选 |
| 日志 | △ 日志面板（持续完善） | ✅ Logs Explorer |

### 3. 全局拓扑 vs Service Map

![DataBuff 全局拓扑](../images/databuff-topology.png)

DataBuff 全局拓扑自动识别 `[mysql]` `[redis]` `[kafka]` `[elasticsearch]` `[remote]` 等虚拟服务节点，一张图看清完整调用链，并可下钻到中间件专页。

![SigNoz Service Map](../images/signoz-service-map.png)

SigNoz Service Map **同样展示** `mysql` / `redis` / `kafka` / `elasticsearch` 等中间件节点（实测截图可见，mysql 可标红）。差距在于：DataBuff 提供虚拟服务专页（慢 SQL、缓存、MQ、外部 HTTP）与更完整的拓扑交互；SigNoz 停留在图级依赖关系，缺少等价专页下钻。

### 4. 服务视图

![DataBuff 服务列表](../images/databuff-services.png)

DataBuff 服务列表可点击下钻到服务详情（含实例、接口分析、服务流），一站式查看。

![SigNoz Services](../images/signoz-services.png)

SigNoz Services 停留在 Application 级 P99 / Error Rate / OPS 表格，缺少服务详情聚合页。

### 5. 中间件专页（DataBuff 独有）

同一 Demo 中的 MySQL / Redis / Kafka / 外部 HTTP 调用，DataBuff 自动拆分为独立观测对象。

![DataBuff 数据库](../images/databuff-database.png)
![DataBuff 缓存](../images/databuff-cache.png)
![DataBuff MQ](../images/databuff-mq.png)
![DataBuff 外部服务](../images/databuff-external.png)

SigNoz 接收相同 Trace，Service Map 上可见中间件节点，但**没有这些专页**。

### 6. 接口分析与错误分析

![DataBuff 接口分析](../images/databuff-api-analysis.png)

DataBuff 按接口聚合 P99 延迟 / 请求量 / 错误率，便于快速定位瓶颈接口。

![DataBuff 错误分析](../images/databuff-errors.png)

DataBuff 错误分析按异常类型自动聚类，无需手写 ClickHouse SQL。

### 7. Trace 查询

![DataBuff Traces](../images/databuff-traces.png)
![SigNoz Traces](../images/signoz-traces.png)

两家 Traces Explorer 功能对等，均支持按服务名/操作/时间范围筛选；实测列表中可见 service-a / service-b 真实 spans。

### 8. 日志

![SigNoz Logs](../images/signoz-logs.png)

本 demo 向 SigNoz 发送了应用日志，Logs Explorer 可检索到 checkout / inventory 等真实条目。DataBuff 日志面板仍在完善，日志维度不作为本次主对比点。

### 9. 告警

![SigNoz Alerts](../images/signoz-alerts.png)

两家均内置告警引擎入口。本 demo 环境未预置生产级 Alert Rules，不宜夸大「开箱即用告警」。

## 适用场景

| 场景 | 推荐 | 说明 |
|------|------|------|
| 需要 AI 驱动排障 | **DataBuff** | AI Native APM，中文对话即可完成全链路排障 |
| 纯 OTel Trace / Logs 存储查询 | **SigNoz** | 轻量 OTel 后端，Traces / Logs Explorer 体验成熟 |
| 多中间件性能排查 | **DataBuff** | 数据库/缓存/MQ/外部服务独立专页，开箱即用 |
| 已有 SkyWalking Agent | **DataBuff** | 原生兼容 SW gRPC 协议，Agent 无需更换 |
| 需要对接 Prometheus 生态 | **SigNoz** | Dashboards V2 支持 Perses/PromQL 查询 |
| 倾向开源方案 | 两者均可 | 两家均为开源，按功能深度选型即可 |

## 不适用场景

- **SigNoz**：不适用于需要 APM 纵深分析（数据库 / 缓存 / MQ 详情）、AI 自动化排障的场景；排障更多依赖手动 ClickHouse SQL 或 PromQL。
- **DataBuff**：不适用于仅需轻量 Trace/Logs 存储查询、且不需要 AI / 中间件专页的团队；当前日志面板尚在完善中。

## 试试看

给 DataBuff 一个 Star，或直接试用：

- GitHub：https://github.com/databufflabs/databuff
- 在线 Demo：https://demo.databuff.ai（账号 admin / Databuff@123）

## 延伸阅读

- [对比总览](./总览.md)
- [迁移指南：从 SigNoz 到 DataBuff](/docs/zh/migration/from-signoz)（即将发布）
