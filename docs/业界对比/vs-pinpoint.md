# DataBuff vs Pinpoint

> 对比文档 · [切换到英文](./vs-pinpoint_en.md)

本文基于同环境实测对比：本地部署 DataBuff v0.1.4 与 Pinpoint 3.0.1（Docker），同一 Spring Boot Demo 应用分别接入 OTel Agent 和 Pinpoint Agent，逐页截图比对。

## 架构对比

Pinpoint 是韩国 NAVER 开源的 APM 系统，核心思路是 Java Agent 字节码增强，通过专有 Thrift/gRPC 协议上报到 Collector，数据存储在 HBase。Web UI 展示拓扑、Trace 列表与调用栈。

DataBuff 是 AI Native OpenTelemetry APM 平台，基于 OTLP 标准协议接收遥测数据，内置 Doris 列式存储，Web UI 集成拓扑/Trace/指标/日志/AI 排障。

| 维度 | Pinpoint | DataBuff |
|------|----------|----------|
| 定位 | Java APM（Agent 字节码增强） | AI Native OTel APM |
| 数据协议 | Thrift / gRPC（专有） | OTLP（OpenTelemetry 标准） + SW gRPC |
| Agent | 仅 Java（字节码增强） | 多语言 OTel SDK + Java Agent |
| 存储 | HBase | Doris |
| 部署组件数 | HBase + Collector + Web（3 组件） | Ingest + Web + Doris（3 组件） |
| AI 能力 | ❌ | ✅ AI 驱动排障、拓扑关联、自然语言查询 |
| 拓扑视图 | ✅ 静态拓扑 | ✅ 拓扑 + 指标联动下钻 |
| Trace 查询 | ✅ 调用栈列表 | ✅ Trace 列表 + 火焰图 + AI 分析 |
| 指标 | ❌ 不内置 | ✅ 内置 JVM/应用/DB 指标 |
| 日志 | ❌ 不内置 | ✅ OTLP 日志 + AI 日志分析 |
| 自运维 | ❌ | ✅ 安装排障 + 运行时排查 |

![DataBuff 服务拓扑](/docs/images/screenshots/global-topology.jpg)

*DataBuff 服务拓扑页，展示服务间调用关系与健康状态*

## 数据采集方式

Pinpoint 使用专有 Java Agent 通过字节码增强技术注入采集代码。Agent 通过 Thrift 或 gRPC 协议将数据上报到 Collector。采集的框架广泛（Tomcat、Spring Boot、Dubbo、gRPC、Kafka、JDBC 等 40+ 插件），但**仅限 Java**。

DataBuff 基于 OpenTelemetry 标准，支持 Java、Python、Go、Node.js、.NET 等多语言，通过 OTLP 协议上报。也支持 SkyWalking 原生 gRPC 协议接入，已有 SW Agent 的用户无需更换 Agent。

| 维度 | Pinpoint | DataBuff |
|------|----------|----------|
| 支持语言 | 仅 Java | Java / Python / Go / Node.js / .NET / 等 |
| 采集协议 | Thrift / gRPC（专有） | OTLP（开放标准） |
| Agent 热升级 | 需重启 JVM | 需重启 JVM（OTel Agent） |
| 字节码增强 | ✅ 成熟稳定 | ✅ OTel 内置 Instrumentation |
| Pinpoint API 手动埋点 | ✅ 支持 | ❌ 需迁移至 OTel API |

![DataBuff 服务列表](/docs/images/screenshots/service-list.jpg)

*DataBuff 服务列表页，展示接入的服务概览与关键指标*

## Trace 对比

Pinpoint 的 Trace 查询以调用栈列表为主，展示每个 Span 的执行时间、调用深度。用户可通过点击节点下钻到更详细的调用信息。

DataBuff 提供 Trace 列表、火焰图、AI 分析三重视角。Trace 列表展示请求路径与耗时分布；火焰图可视化 Span 时间占比；AI 排障直接分析慢 Trace 根因。

Pinpoint 不提供指标关联——需要单独部署 Prometheus/Grafana 来查看 CPU/内存等指标。DataBuff 在 Trace 详情页直接关联服务指标、日志与告警，实现全链路关联。

## 部署对比

| 维度 | Pinpoint | DataBuff |
|------|----------|----------|
| 基础环境 | HBase + Java 8+ | Docker Compose / K8s |
| 机器需求 | 4C8G+（HBase 较重） | 4C8G+ |
| 启动时间 | 5–10 分钟（HBase 初始化） | 2–3 分钟 |
| 存储依赖 | 外部 HBase | 内置 Doris |
| 组件数 | HBase + Collector + Web | Ingest + Web + Doris |
| 配置文件 | `.properties` 多文件 | `application.yml` 单文件 |

## 迁移到 DataBuff

对于正在使用 Pinpoint 的用户，迁移到 DataBuff 的推荐路径如下：

1. **前提条件**：部署 DataBuff 并确认 Ingest `:4317` / `:4318` 可达
2. **金丝雀验证**（建议）：选择 1–2 个非核心 Java 服务，将 JVM 参数从 Pinpoint Agent 替换为 OTel Java Agent，指向 DataBuff Ingest
3. **分批扩量**：按服务批次替换，每批验证 Trace 可查、错误率正常后再扩下一批
4. **保留只读**：迁移期间保留 Pinpoint Collector + Web 只读，用于对照和回滚

详细迁移步骤和门禁见[迁移指南：从 Pinpoint 到 DataBuff](/docs/zh/migration/from-pinpoint)。

### JVM 参数对照

**切换前（Pinpoint）**
```bash
-javaagent:/path/to/pinpoint-bootstrap.jar
-Dpinpoint.agentId=${HOSTNAME}
-Dpinpoint.applicationName=my-service
```

**切换后（OTel Java Agent 指向 DataBuff）**
```bash
-javaagent:/path/to/opentelemetry-javaagent.jar
-Dotel.service.name=my-service
-Dotel.exporter.otlp.endpoint=http://<ingest-host>:4317
-Dotel.exporter.otlp.protocol=grpc
```

## 适用场景

- **Pinpoint 适合**：纯 Java 技术栈、已深度使用 Pinpoint API 手动埋点、对多语言监控无需求、愿意维护 HBase 集群的团队
- **DataBuff 适合**：需要多语言监控、需要 AI 驱动排障、希望内置指标/日志/拓扑全链路能力、需要自运维功能（安装排障/自动修复）、希望降低运维复杂度的团队
- **迁移建议**：DataBuff 是 Pinpoint 的演进方向——Pinpoint 当前没有 AI 能力、指标/日志关联、自运维等核心能力，而 DataBuff 通过 OTel 标准协议实现了更开放的生态

## 延伸阅读

- [快速入门：Docker 安装部署](/docs/zh/guide/docker-install)
- [Agent 集成：Java OTel](/docs/zh/manual/agent-integration)
- [迁移指南：从 Pinpoint 到 DataBuff](/docs/zh/migration/from-pinpoint)（即将发布）
