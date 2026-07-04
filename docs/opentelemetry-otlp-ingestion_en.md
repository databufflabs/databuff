<p align="center">
  <a href="opentelemetry-otlp-ingestion.md">中文</a>
  &nbsp;|&nbsp;
  <a href="opentelemetry-otlp-ingestion_en.md">English</a>
</p>

# OpenTelemetry OTLP Ingestion

DataBuff is an open-source, AI-native OpenTelemetry APM backend. The **Ingest** service accepts telemetry over **OTLP** (OpenTelemetry Protocol) and stores it for tracing, service metrics, topology, and AI-assisted troubleshooting in the Web UI.

## Supported signals

| Signal | OTLP gRPC (4317) | OTLP HTTP (4318) |
|--------|------------------|------------------|
| Traces | Yes | Yes (`/v1/traces`) |
| Metrics | Yes | Yes (`/v1/metrics`) |
| Logs | Yes | Yes (`/v1/logs`) |

## Prerequisites

Install DataBuff (Docker or Kubernetes). After installation, note the host where **Ingest** is reachable and the OTLP ports (default **4317** gRPC, **4318** HTTP).

- [Docker installation](快速入门/docker安装部署_en.md)
- [Kubernetes installation](快速入门/k8s安装部署_en.md)
- [Live demo](https://demo.databuff.ai)

## Endpoints

Replace `<ingest-host>` with your Ingest hostname or IP (same host as the install script reports).

| Protocol | Endpoint | Notes |
|----------|----------|-------|
| OTLP/gRPC | `<ingest-host>:4317` | Recommended for agents and Collector |
| OTLP/HTTP | `http://<ingest-host>:4318` | Use `/v1/traces` and `/v1/metrics` paths when configuring per-signal exporters |

Default Docker install exposes both ports on the host running `docker compose`.

## Application SDK (environment variables)

Most OpenTelemetry SDKs accept:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT="http://<ingest-host>:4318"
export OTEL_EXPORTER_OTLP_PROTOCOL="http/protobuf"
# or for gRPC:
# export OTEL_EXPORTER_OTLP_ENDPOINT="http://<ingest-host>:4317"
# export OTEL_EXPORTER_OTLP_PROTOCOL="grpc"
export OTEL_SERVICE_NAME="my-service"
```

Send traces and metrics from your instrumented application; open the Web UI (`http://<host>:27403`) to view service topology and traces.

## OpenTelemetry Collector

Forward OTLP from a Collector to DataBuff Ingest:

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

  # or OTLP/HTTP:
  # otlphttp:
  #   endpoint: "http://<ingest-host>:4318"

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

For logs, set in your SDK:

```bash
export OTEL_LOGS_EXPORTER=otlp
```

For TLS-terminated or remote deployments, point exporters at the reachable OTLP URL and configure TLS on your reverse proxy.

## Verify ingestion

1. Install the optional [Demo app](快速入门/docker安装部署_en.md#3-install-the-demo-optional) — it reports sample traces to Ingest.
2. In the Web UI, open **Application Performance** and confirm services and traces appear within a few minutes.

For how data is stored and queried, see [Telemetry Pipeline and Storage](架构设计/遥测数据流_en.md).

## Open source

- Repository: [github.com/databufflabs/databuff](https://github.com/databufflabs/databuff)
- License: [AGPL-3.0](https://github.com/databufflabs/databuff/blob/main/LICENSE)
