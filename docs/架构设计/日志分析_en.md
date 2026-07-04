<p align="center">
  <a href="日志分析.md">中文</a>
  &nbsp;|&nbsp;
  <a href="日志分析_en.md">English</a>
</p>

# Log Analytics · Design (v0.2.0+)

> Version: 2026-07-03 · Status: design finalized  
> Backend: `POST /log/search`, Doris table `log_dc_record` (see [OTLP Ingestion](../opentelemetry-otlp-ingestion_en.md))

## Background

v0.2.0 delivers **OTLP Logs → Doris `log_dc_record` → `/log/search`**, with log tabs embedded in trace detail, service detail, and alert events.

A dedicated **Log Analytics** menu under Application Performance is planned as a global log search entry — APM-context exploration tied to traces and services, not a standalone ELK/Loki replacement.

## Storage Model

| Field area | Purpose |
|------------|---------|
| `trace_id` / `span_id` | Correlate with traces |
| `service_id` / `hostname` | Filter by service and host |
| `severity` / `body` | Level and message |
| `attributes_json` | Structured OTel attributes |

Ingest writes via OTLP `/v1/logs` (gRPC 4317 or HTTP 4318). See [Telemetry Pipeline](遥测数据流_en.md) for the full pipeline.

## API (MVP)

- `POST /log/search` — keyword, service, host, trace/span, time range, offset pagination
- `POST /log/conditions` — facet enumerations (evolving)

## UI Direction

Reference patterns from SigNoz and Datadog: global time range, histogram, left facets (service / host / level), main log table with expand row and **jump to trace** when `trace_id` is present.

**Out of scope for early releases:** Live tail, LogQL/KQL-style query languages, log clustering.

## Related

- [Telemetry Pipeline and Storage](遥测数据流_en.md)
- [Architecture · Application Performance](应用性能_en.md)
