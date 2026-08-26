<p align="center">
  <a href="SkyWalking接入.md">中文</a>
  &nbsp;|&nbsp;
  <a href="SkyWalking接入_en.md">English</a>
</p>

# User Guide · SkyWalking Ingestion

DataBuff **Ingest** supports the **SkyWalking native gRPC protocol (v3)** directly. Java Agents or custom SDKs can submit traces, JVM metrics, and logs to DataBuff without going through a SkyWalking OAP or OpenTelemetry Collector.

## Supported Signals

| Signal | Protocol | Ingest Port |
|--------|----------|-------------|
| Trace (Segment) | SkyWalking gRPC `TraceSegmentReportService` | `11800` |
| JVM Metrics | SkyWalking gRPC `JVMMetricReportService` | `11800` |
| Logs | SkyWalking gRPC `LogReportService` | `11800` |

## Prerequisites

DataBuff is deployed and **Ingest** is reachable:

- [Docker Installation](../快速入门/docker安装部署_en.md)
- [Kubernetes Installation](../快速入门/k8s安装部署_en.md)

## Endpoints

Replace `<ingest-host>` with your Ingest hostname or IP.

| Protocol | Endpoint | Notes |
|----------|----------|-------|
| SkyWalking gRPC | `<ingest-host>:11800` | Java Agent `backend_service` / `agent.backend_service` |

Default Docker install maps `11800:11800` (see `deploy/docker/docker-compose.yml:81`).  
K8s default NodePort is `31180` (see `deploy/k8s/manifests/ingest.yaml:27`).

## Java Agent Configuration

### Minimal `agent.config`

```properties
# Point to DataBuff Ingest; replace <ingest-host> with actual address
agent.backend_service=<ingest-host>:11800

# Application name
agent.service_name=my-service
```

Save the above as `agent.config`, then start the JVM:

```bash
java -javaagent:/path/to/skywalking-agent.jar \
     -Dskywalking_config=/path/to/agent.config \
     -jar my-service.jar
```

Or override via system properties:

```bash
java -javaagent:/path/to/skywalking-agent.jar \
     -Dskywalking.agent.service_name=my-service \
     -Dskywalking.collector.backend_service=<ingest-host>:11800 \
     -jar my-service.jar
```

### Optional Configuration

```properties
# Sampling (default: once per 3 seconds; -1 means full sample)
agent.sample_n_per_3_secs=-1

# Ignored trace suffixes (comma-separated)
# agent.ignore_suffix=.do,.action

# Max operation name length
agent.operation_name_threshold=150

# Max correlation header size
agent.correlation_header_max_size=2048
```

## Migration Differences from SkyWalking OAP

### Protocol Compatibility

DataBuff Ingest implements three core SkyWalking v3 gRPC services, compatible with the official OAP:

- **`TraceSegmentReportService`** — accepts `SegmentObject` (collect / collectInSync)
- **`JVMMetricReportService`** — accepts `JVMMetricCollection`
- **`LogReportService`** — accepts `LogData`
- **`ManagementService`** — registered (NOOP), agent heartbeat is ignored

Source: `ai-apm-ingest/src/main/java/com/databuff/apm/ingest/receiver/SkyWalkingGrpcServer.java:46`

### data.source Field

DataBuff's `data.source` field identifies the protocol that produced the data:

| Source | data.source Value | Notes |
|--------|-------------------|-------|
| SkyWalking native | `SkyWalking` | Via `ingest.skywalking.grpc-port:11800` |
| OTLP | `Otel` | Via `ingest.otlp.grpc-port:4317` or HTTP 4318 |

Constants defined in `ai-apm-ingest/src/main/java/com/databuff/apm/ingest/trace/remote/TraceDataSources.java:18`.

### Feature Comparison

| Feature | SkyWalking OAP | DataBuff Ingest |
|---------|---------------|-----------------|
| Trace storage | H2 / ES / BanyanDB | Apache Doris |
| Metric aggregation | OAP internal streaming | Ingest pipeline → Doris |
| Alerting | OAP Alarm + Webhook | DataBuff Web alert engine |
| UI | SkyWalking Rocketbot | DataBuff Web |
| gRPC port | 11800 (default) | 11800 (configurable via `ingest.skywalking.grpc-port`) |
| Config file | `application.yml` | `ai-apm-ingest/src/main/resources/application.yml:36-38` |

The following OAP components are **not needed**:

- OAP server
- H2 / Elasticsearch / BanyanDB
- SkyWalking UI (Rocketbot)

## Coexistence with OTLP

DataBuff supports **SkyWalking native protocol** and **OTLP** simultaneously, without interference.

| Protocol | Port | Toggle |
|----------|------|--------|
| SkyWalking gRPC | `11800` | `ingest.skywalking.enabled=true` (enabled by default) |
| OTLP gRPC | `4317` | Always on |
| OTLP HTTP | `4318` | Always on |

Services within the same environment can mix protocols freely. Cross-protocol trace correlation requires manual agreement on TraceId propagation format (SW8 vs W3C traceparent).

## Demo App Verification

The DataBuff Demo app supports the SkyWalking protocol. Set environment variables to switch:

```bash
export SEED_PROTOCOL=skywalking
export SKYWALKING_GRPC_TARGET=<ingest-host>:11800
```

Source: `ai-apm-demo/src/main/java/com/databuff/apm/demo/DemoOrderSeeder.java:25`  
Local deployment reference: `deploy/local/docker-compose.yml:182-183`

## Configuration Reference

SkyWalking-related settings in `application.yml`:

```yaml
ingest:
  skywalking:
    enabled: true           # Enable SkyWalking gRPC receiver
    grpc-port: 11800        # SkyWalking gRPC listening port
```

## Verification

1. Confirm the Ingest port is reachable:

```bash
telnet <ingest-host> 11800
```

2. Start an application configured with the SkyWalking Agent.
3. Open the DataBuff Web UI and verify the service appears in Application Performance.
4. Check that the Trace detail shows `data.source` as `SkyWalking`.

## Related Documents

- [OpenTelemetry OTLP Ingestion](../opentelemetry-otlp-ingestion_en.md)
- [eBPF Ingestion](eBPF接入_en.md)
- [Nginx Ingestion](Nginx接入_en.md)
- [Application Performance](应用性能_en.md)
- [Telemetry Pipeline and Storage](../架构设计/遥测数据流_en.md)
