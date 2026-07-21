# DataBuff vs Pinpoint

> Objective comparison · [Switch to Chinese](./vs-pinpoint.md)

This comparison is based on same-environment hands-on testing: DataBuff v0.1.4 and Pinpoint 3.0.1 deployed locally via Docker, with the same Spring Boot demo application instrumented by both OTel Agent and Pinpoint Agent, screenshots taken page by page.

## Architecture Comparison

Pinpoint is an APM system open-sourced by NAVER (Korea). It uses a proprietary Java Agent with bytecode enhancement, reports data via Thrift/gRPC to Collector, stores in HBase, and provides topology/Trace/call stack visualization in Web UI.

DataBuff is an AI Native OpenTelemetry APM platform. It receives telemetry via standard OTLP protocol, stores in Doris columnar storage, and provides topology/Trace/metrics/logs/AI troubleshooting in an integrated Web UI.

| Dimension | Pinpoint | DataBuff |
|-----------|----------|----------|
| Positioning | Java APM (Agent bytecode) | AI Native OTel APM |
| Data Protocol | Thrift / gRPC (proprietary) | OTLP (OpenTelemetry standard) + SW gRPC |
| Agent | Java only (bytecode enhancement) | Multi-language OTel SDK + Java Agent |
| Storage | HBase | Doris |
| Components | HBase + Collector + Web | Ingest + Web + Doris |
| AI | ❌ | ✅ AI-driven troubleshooting, topology correlation, NL query |
| Topology | ✅ Static topology | ✅ Topology + metric drill-down |
| Trace | ✅ Call stack list | ✅ Trace list + flame graph + AI analysis |
| Metrics | ❌ Not built-in | ✅ Built-in JVM/app/DB metrics |
| Logs | ❌ Not built-in | ✅ OTLP logs + AI log analysis |
| Self-healing | ❌ | ✅ Install troubleshooting + runtime diagnostics |

![DataBuff Service Topology](/docs/images/screenshots/global-topology.jpg)

*DataBuff service topology page showing inter-service call relationships and health status*

## Data Collection

Pinpoint uses a proprietary Java Agent with bytecode enhancement. The Agent reports data to Collector via Thrift or gRPC protocol. It supports 40+ plugins (Tomcat, Spring Boot, Dubbo, gRPC, Kafka, JDBC, etc.) but **Java only**.

DataBuff is based on the OpenTelemetry standard, supporting Java, Python, Go, Node.js, .NET, and more languages via OTLP protocol. It also supports SkyWalking native gRPC protocol for existing SW Agent users without replacing the agent.

| Dimension | Pinpoint | DataBuff |
|-----------|----------|----------|
| Languages | Java only | Java / Python / Go / Node.js / .NET / etc. |
| Protocol | Thrift / gRPC (proprietary) | OTLP (open standard) |
| Agent hot upgrade | Requires JVM restart | Requires JVM restart (OTel Agent) |
| Bytecode enhancement | ✅ Mature and stable | ✅ OTel built-in Instrumentation |
| Pinpoint API manual instrumentation | ✅ Supported | ❌ Requires migration to OTel API |

![DataBuff Service List](/docs/images/screenshots/service-list.jpg)

*DataBuff service list page showing connected services and key metrics*

## Trace Comparison

Pinpoint's Trace view is primarily a call stack list showing execution time and call depth for each Span. Users can click nodes to drill down to more detailed call information.

DataBuff provides three perspectives: Trace list, flame graph, and AI analysis. The Trace list shows request paths and latency distribution; the flame graph visualizes Span time proportions; the AI diagnostics directly analyzes root causes of slow Traces.

Pinpoint lacks metric correlation—users need to deploy separate Prometheus/Grafana for CPU/memory metrics. DataBuff directly correlates service metrics, logs, and alerts within the Trace details page for full-path observability.

## Deployment Comparison

| Dimension | Pinpoint | DataBuff |
|-----------|----------|----------|
| Runtime | HBase + Java 8+ | Docker Compose / K8s |
| Hardware | 4C8G+ (HBase is heavy) | 4C8G+ |
| Start time | 5–10 min (HBase initialization) | 2–3 min |
| Storage dependency | External HBase | Built-in Doris |
| Components | HBase + Collector + Web | Ingest + Web + Doris |
| Configuration | Multiple `.properties` files | Single `application.yml` |

## Migration to DataBuff

For Pinpoint users migrating to DataBuff, the recommended path:

1. **Prerequisites**: Deploy DataBuff and verify Ingest `:4317` / `:4318` is reachable
2. **Canary validation** (recommended): Select 1–2 non-critical Java services, replace JVM args from Pinpoint Agent to OTel Java Agent pointing to DataBuff Ingest
3. **Batch rollout**: Replace service by service batch, verify Traces visible and error rate normal before expanding
4. **Read-only retention**: Keep Pinpoint Collector + Web read-only for comparison and rollback during migration

See [Migration: From Pinpoint to DataBuff](/docs/en/migration/from-pinpoint) for detailed steps and gates.

### JVM Argument Comparison

**Before (Pinpoint)**
```bash
-javaagent:/path/to/pinpoint-bootstrap.jar
-Dpinpoint.agentId=${HOSTNAME}
-Dpinpoint.applicationName=my-service
```

**After (OTel Java Agent pointing to DataBuff)**
```bash
-javaagent:/path/to/opentelemetry-javaagent.jar
-Dotel.service.name=my-service
-Dotel.exporter.otlp.endpoint=http://<ingest-host>:4317
-Dotel.exporter.otlp.protocol=grpc
```

## When to Choose Which

- **Pinpoint for**: Pure Java stack, heavy use of Pinpoint API manual instrumentation, no multi-language need, willing to maintain HBase cluster
- **DataBuff for**: Multi-language monitoring needed, want AI-driven troubleshooting, want built-in metrics/logs/topology full-path capability, want self-service features (install troubleshooting/auto-recovery), want reduced operational complexity
- **Migration recommendation**: DataBuff is the natural evolution for Pinpoint users—Pinpoint lacks AI, metric/log correlation, and self-healing capabilities that DataBuff provides through the open OTel standard

## See Also

- [Quick Start: Docker Installation](/docs/en/guide/docker-install)
- [Agent Integration: Java OTel](/docs/en/manual/agent-integration)
- [Migration: From Pinpoint to DataBuff](/docs/en/migration/from-pinpoint) (Coming Soon)
