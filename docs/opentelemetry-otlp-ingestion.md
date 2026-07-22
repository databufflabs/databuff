<p align="center">
  <a href="opentelemetry-otlp-ingestion.md">中文</a>
  &nbsp;|&nbsp;
  <a href="opentelemetry-otlp-ingestion_en.md">English</a>
</p>

# OpenTelemetry OTLP 接入

DataBuff 是开源、AI 原生的 OpenTelemetry APM 后端。**Ingest** 服务通过 **OTLP**（OpenTelemetry Protocol）接收遥测数据，供 Web 端做链路追踪、服务指标、拓扑与 AI 排障。

## 支持的信号

| 信号 | OTLP gRPC (4317) | OTLP HTTP (4318) |
|------|------------------|------------------|
| Trace | 支持 | 支持（`/v1/traces`） |
| Metrics | 支持 | 支持（`/v1/metrics`） |
| Logs | 支持 | 支持（`/v1/logs`） |

## 压缩（Compression）

OTLP exporter / otel-collector **默认开启 `gzip` 压缩**。DataBuff Ingest 已支持下列算法，**无需再配置 `compression: none`**。

| 协议 | 支持的压缩 |
|------|------------|
| OTLP/gRPC | `none`、`gzip`（默认）、`snappy`、`zstd` |
| OTLP/HTTP | `none`、`gzip`（默认）、`zstd`、`zlib`、`deflate`、`snappy`、`x-snappy-framed`、`lz4` |

Collector 示例（可省略 `compression`，使用默认 gzip）：

```yaml
exporters:
  otlp:
    endpoint: "<ingest-host>:4317"
    tls:
      insecure: true
    # compression: gzip   # 默认；也可改为 snappy / zstd / none
  otlphttp:
    endpoint: "http://<ingest-host>:4318"
    # compression: gzip
```

## 前置条件

先完成 Docker 或 Kubernetes 安装，确认 **Ingest** 可达及 OTLP 端口（默认 **4317** gRPC、**4318** HTTP）。

- [Docker 安装](快速入门/docker安装部署.md)
- [Kubernetes 安装](快速入门/k8s安装部署.md)
- [在线演示](https://demo.databuff.ai)

## 接入地址

将 `<ingest-host>` 替换为 Ingest 所在主机名或 IP（与安装脚本输出一致）。

| 协议 | 地址 | 说明 |
|------|------|------|
| OTLP/gRPC | `<ingest-host>:4317` | 推荐 Agent / Collector 使用 |
| OTLP/HTTP | `http://<ingest-host>:4318` | 按信号配置时可使用 `/v1/traces`、`/v1/metrics` |

Docker 默认安装会在宿主机暴露上述端口。

## 应用 SDK（环境变量）

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT="http://<ingest-host>:4318"
export OTEL_EXPORTER_OTLP_PROTOCOL="http/protobuf"
# gRPC 示例：
# export OTEL_EXPORTER_OTLP_ENDPOINT="http://<ingest-host>:4317"
# export OTEL_EXPORTER_OTLP_PROTOCOL="grpc"
export OTEL_SERVICE_NAME="my-service"
```

## OpenTelemetry Collector

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

exporters:
  otlp:
    endpoint: "<ingest-host>:4317"
    tls:
      insecure: true

service:
  pipelines:
    traces:
      receivers: [otlp]
      exporters: [otlp]
    metrics:
      receivers: [otlp]
      exporters: [otlp]
    logs:
      receivers: [otlp]
      exporters: [otlp]
```

上报 Logs 时，SDK 可设置：

```bash
export OTEL_LOGS_EXPORTER=otlp
```

## 验证

1. 可选安装 [Demo 应用](快速入门/docker安装部署.md#3-安装-demo-可选)，持续上报 Trace。
2. 打开 Web UI（`http://<host>:27403`），在应用性能中查看服务与链路。

深入了解数据如何写入与查询，见 [遥测数据流与存储](架构设计/遥测数据流.md)。

## 开源信息

- 仓库：[github.com/databufflabs/databuff](https://github.com/databufflabs/databuff)
- 许可证：[AGPL-3.0](https://github.com/databufflabs/databuff/blob/main/LICENSE)
