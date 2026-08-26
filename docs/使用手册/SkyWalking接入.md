<p align="center">
  <a href="SkyWalking接入.md">中文</a>
  &nbsp;|&nbsp;
  <a href="SkyWalking接入_en.md">English</a>
</p>

# 使用手册 · SkyWalking 接入

DataBuff **Ingest** 服务支持直接接收 **SkyWalking 原生 gRPC 协议**（v3），Java Agent 或自研 SDK 可将 Trace、JVM 指标、Log 上报至 DataBuff，无需经过 SkyWalking OAP 或 OpenTelemetry Collector。

## 支持的信号

| 信号 | 协议 | Ingest 端口 |
|------|------|-------------|
| Trace（Segment） | SkyWalking gRPC `TraceSegmentReportService` | `11800` |
| JVM 指标 | SkyWalking gRPC `JVMMetricReportService` | `11800` |
| 日志 | SkyWalking gRPC `LogReportService` | `11800` |

## 前置条件

DataBuff 已部署，并确认 **Ingest** 可达：

- [Docker 安装](../快速入门/docker安装部署.md)
- [Kubernetes 安装](../快速入门/k8s安装部署.md)

## 接入地址

将 `<ingest-host>` 替换为 Ingest 所在主机名或 IP。

| 协议 | 地址 | 说明 |
|------|------|------|
| SkyWalking gRPC | `<ingest-host>:11800` | Java Agent 的 `backbone_service` / `agent.backend_service` |

Docker 默认安装映射 `11800:11800`（见 `deploy/docker/docker-compose.yml:81`）。  
K8s 默认 NodePort 为 `31180`（见 `deploy/k8s/manifests/ingest.yaml:27`）。

## Java Agent 配置

### 最小 `agent.config`

```properties
# 指向 DataBuff Ingest，替换 <ingest-host> 为实际地址
agent.backend_service=<ingest-host>:11800

# 应用名称
agent.service_name=my-service
```

将以上内容保存为 `agent.config`，启动 JVM 时添加：

```bash
java -javaagent:/path/to/skywalking-agent.jar \
     -Dskywalking_config=/path/to/agent.config \
     -jar my-service.jar
```

或通过系统属性覆盖：

```bash
java -javaagent:/path/to/skywalking-agent.jar \
     -Dskywalking.agent.service_name=my-service \
     -Dskywalking.collector.backend_service=<ingest-host>:11800 \
     -jar my-service.jar
```

### 可选配置（按需调整）

```properties
# 采样（默认：每 3 秒采样一次，-1 表示全采样）
agent.sample_n_per_3_secs=-1

# 忽略特定端点的 Trace（逗号分隔）
# agent.ignore_suffix=.do,.action

# 操作名最大长度
agent.operation_name_threshold=150

# 跨进程传播头部（默认：sw8）
agent.correlation_header_max_size=2048
```

## 与 SkyWalking OAP 的迁移差异

### 协议兼容

DataBuff Ingest 实现了 SkyWalking v3 gRPC 协议的三个核心服务，与官方 OAP 兼容：

- **`TraceSegmentReportService`** — 接收 `SegmentObject`（collect / collectInSync）
- **`JVMMetricReportService`** — 接收 `JVMMetricCollection`
- **`LogReportService`** — 接收 `LogData`
- **`ManagementService`** — 已注册（NOOP），Agent 心跳不做额外处理

源码位置：`ai-apm-ingest/src/main/java/com/databuff/apm/ingest/receiver/SkyWalkingGrpcServer.java:46`

### data.source 字段

DataBuff 的 `data.source` 字段标识数据的来源协议。上报数据在 Web UI 中会用该字段标记来源：

| 来源 | data.source 值 | 说明 |
|------|---------------|------|
| SkyWalking 原生协议 | `SkyWalking` | 通过 `ingest.skywalking.grpc-port:11800` 接入 |
| OTLP | `Otel` | 通过 `ingest.otlp.grpc-port:4317` 或 HTTP 4318 接入 |

常量定义见 `ai-apm-ingest/src/main/java/com/databuff/apm/ingest/trace/remote/TraceDataSources.java:18`。

### 功能差异

| 功能 | SkyWalking OAP | DataBuff Ingest |
|------|---------------|-----------------|
| Trace 存储 | H2 / ES / BanyanDB | Apache Doris |
| 指标计算 | OAP 内部 Streaming | Ingest 管道聚合后写入 Doris |
| 告警 | OAP Alarm + Webhook | DataBuff Web 告警引擎 |
| UI | SkyWalking Rocketbot | DataBuff Web |
| gRPC 端口 | 11800（默认） | 11800（可配置 `ingest.skywalking.grpc-port`） |
| 配置方式 | `application.yml` | `ai-apm-ingest/src/main/resources/application.yml:36-38` |

**不需要**以下 OAP 组件：

- OAP 服务端
- H2 / Elasticsearch / BanyanDB
- SkyWalking UI（Rocketbot）

## OTLP 并存说明

DataBuff 支持 **SkyWalking 原生协议**与 **OTLP** 同时接入，互不干扰。

| 协议 | 端口 | 启用开关 |
|------|------|----------|
| SkyWalking gRPC | `11800` | `ingest.skywalking.enabled=true`（默认开启） |
| OTLP gRPC | `4317` | 固定开启 |
| OTLP HTTP | `4318` | 固定开启 |

同一应用可选择任一协议上报，也支持环境内部分应用用 OTLP、部分用 SkyWalking。同一个 Trace 跨协议的 Span 互相关联需要自行协商 TraceId 传播格式（SW8 vs W3C traceparent）。

## Demo 应用验证

DataBuff Demo 应用支持以 SkyWalking 协议上报。设置环境变量即可切换：

```bash
export SEED_PROTOCOL=skywalking
export SKYWALKING_GRPC_TARGET=<ingest-host>:11800
```

源码：`ai-apm-demo/src/main/java/com/databuff/apm/demo/DemoOrderSeeder.java:25`  
本地部署参考：`deploy/local/docker-compose.yml:182-183`

## 配置参考

Ingest 中 SkyWalking 相关配置项（`application.yml`）：

```yaml
ingest:
  skywalking:
    enabled: true           # 是否启用 SkyWalking gRPC 接收
    grpc-port: 11800        # SkyWalking gRPC 监听端口
```

## 验证步骤

1. 确认 Ingest 端口可达：

```bash
telnet <ingest-host> 11800
```

2. 启动已配置 SkyWalking Agent 的应用。
3. 在 DataBuff Web 中查看服务是否出现在应用性能页面。
4. 确认 Trace detail 中 `data.source` 值为 `SkyWalking`。

## 相关文档

- [OpenTelemetry OTLP 接入](../opentelemetry-otlp-ingestion.md)
- [eBPF 接入](eBPF接入.md)
- [Nginx 接入](Nginx接入.md)
- [应用性能](应用性能.md)
- [Telemetry Pipeline and Storage](../架构设计/遥测数据流.md)
