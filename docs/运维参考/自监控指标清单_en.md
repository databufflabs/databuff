<p align="center">
  <a href="自监控指标清单.md">中文</a>
  &nbsp;|&nbsp;
  <a href="自监控指标清单_en.md">English</a>
</p>

# Platform Self-Monitoring Metric Catalog

Authoritative catalog of DataBuff **platform self-ops** metrics (ingest / web / Doris) for operators and the **product Q&A expert**. Business APM metrics (`metric_service*`, etc.) are out of scope.

- **Table**: `metric_platform`
- **UI**: Deploy → Install → Status (overview / ingest / cluster / query / Doris)
- **UI metric help**: Panel 「?」 drawers align with **§3–§9 meaning / how to read / env knobs** in the [Chinese catalog](自监控指标清单.md) (source: `metricHelp.ts`)
- **Query APIs**: `POST /webapi/platform/metrics/{query,summary,tagValues}`
- **Naming**: `PlatformMetricNames.java`
- **Q&A tool**: `querySelfMonitorMetrics` — same Portal service; platform self-ops only
- **How to change env vars**: [Parameter Configuration](参数配置_en.md); capacity / tuning order: [Performance](性能优化_en.md). Restart the affected component after changes.

> Full metric tables, interpretation tips, and env-var matrices live in the Chinese document: **[自监控指标清单.md](自监控指标清单.md)**. This English page is a parallel quick reference.

**Write time**: rows use the **previous closed minute** as `ts` / `metric_time`. Flush ~2s after each minute boundary; gauge samplers default to second 50.

## 1. Data model (short)

| Column | Meaning |
|--------|---------|
| `component` | `ingest` or `web` |
| `instance` | Process hostname/pod; for `web.doris.*` = **Doris Host/IP** |
| `metric` | Fully qualified name with component prefix |
| `dim` | Sparse: write signal (`trace`/`metric`/`log`), Doris CPU mode, etc. |
| `value` modes | `cnt` / `avg` (ms) / `gauge` / `auto` |

## 2. Symptom → metrics

| Symptom | Prefer | value |
|---------|--------|-------|
| Ingest failures | `ingest.otel.*.fail`, `ingest.sw.*.fail` vs `*.req` | `cnt` |
| Backlog | `ingest.pipeline.*.cost_ms` / `.drop` / `.queue`, `ingest.write.queue`, `ingest.trace.assembly.pending` | `avg` / `cnt` / `gauge` |
| Stream Load fail | `ingest.write.fail` / `.drop` (groupBy `dim` = signal) | `cnt` |
| Slow/error queries | `web.query.<domain>.fail` / `.cost_ms` | `cnt` / `avg` |
| Doris down | `web.doris.up`, `*.be.alive`, `*.fe.alive` | `gauge` |
| Cluster drops | `ingest.cluster.drop.*`, `ingest.cluster.forward.*.fail` | `cnt` |
| OOM risk | `*.system.memory.heap.*`, `*.system.gc.*`, `*.system.cpu.usage` | `gauge` / `cnt` |

## 3. Metric meaning + how to tune (summary)

Each UI help drawer covers **logic**, **how to read**, and **related env vars**. Same content is expanded per section in the CN doc.

### Inbound (`ingest.{otel\|sw}.{trace\|metric\|log}.*`)

| Metric suffix | Meaning |
|---------------|---------|
| `.req` | Item throughput (spans / metric rows / log lines), **not** Export RPC count |
| `.fail` | Parse/process failures |
| `.bytes` | Payload bytes |
| `.cost_ms` | Receive/parse latency before pipeline (excludes Stream Load) |

**Tune**: `INGEST_HTTP_PORT` / `INGEST_GRPC_PORT` / `INGEST_SKYWALKING_PORT`; log body cap `INGEST_DORIS_LOG_BODY_MAX_LENGTH`.

### Pipeline (`ingest.pipeline.<kind>.*`)

| Suffix | Meaning |
|--------|---------|
| `.req` | Events processed |
| `.cost_ms` | Queue + process latency |
| `.drop` | Ring buffer full → **data loss** |
| `.queue` / `.queue.cap` | Occupancy vs capacity |

**Tune**: `INGEST_<KIND>_TASKS`, `INGEST_<KIND>_BUFFER_SIZE` (`TRACE`/`METRIC`/`AGGREGATE`). Prefer raising buffer/tasks before flush knobs. Ignore drops: `INGEST_TRACE_IGNORE_*`. Assembly: `INGEST_TRACE_ASSEMBLY_CHECK_INTERVAL_MS`.

### Stream Load write (`ingest.write.*`, groupBy `dim` = signal)

| Metric | Meaning |
|--------|---------|
| `.req` / `.fail` / `.drop` | Success / fail / drop batches |
| `.bytes` / `.cost_ms` | NDJSON bytes / Stream Load RTT |
| `.queue` | Pending **rows** (thread buffer + ready) |
| `.queue.cap` | Ready capacity in **batches** (units differ from `.queue`) |

**Tune**:

| Env | Default | Notes |
|-----|---------|-------|
| `INGEST_DORIS_FLUSH_BATCH_BYTES` | 50MiB | Larger → fewer loads, friendlier tablet versions |
| `INGEST_DORIS_FLUSH_INTERVAL_MS` | 30000 | Time-based hand-off |
| `INGEST_DORIS_MAX_READY_BATCHES` | 32 | Full → `write.drop` |
| `INGEST_DORIS_STREAM_LOAD_MAX_FAILURES` | 3 | Then drop batch |
| `INGEST_DORIS_TRACE_FLUSH_CONCURRENCY` | 1 | Keep **1** on single BE |
| `INGEST_DORIS_FLUSH_TIMEOUT_MS` | 60000 | Raise for large batches |
| `DORIS_BE_HTTP_HOST` / `PORT` | empty / 8040 | Direct BE Stream Load |

### Cluster (`ingest.cluster.*`)

Member/leader/effective gauges; `forward.*` / `forward.in.*` / `drop.*` for cross-instance traffic.

**Tune**: `INGEST_CLUSTER_ENABLED`, `ZK_CONNECT_STRING`, `INGEST_CLUSTER_GRPC_PORT`.

### Web query (`web.query.<domain>.*`)

Domains: `trace` / `metric` / `log` / `alarm` / `ai` / `portal` / `other` (URI classification). `.fail` = HTTP ≥ 400; `.cost_ms` includes Doris + assembly.

### Process (`{ingest\|web}.system.*`)

CPU %, heap used/max, thread count, GC count/time.

**Tune**: `JAVA_TOOL_OPTIONS` (ingest ≈ `-Xmx4g`, web ≈ `-Xmx1536m`).

### Doris (`web.doris.*`, instance = Doris Host)

Probe: `up` / `be.alive` / `fe.alive` / `fe.is_master`. Prometheus-mapped counters are **scrape deltas**; gauges are instantaneous. CPU modes are normalized to %. Official names: [Doris metrics](https://doris.apache.org/docs/admin-manual/maint-monitor/metrics/).

**Connect**: `DORIS_FE_HOST` / `DORIS_FE_HTTP_PORT` / `DORIS_FE_QUERY_PORT`. Compaction / stream_load pressure → same `INGEST_DORIS_*` knobs as write section.

Full official-name mapping table: [CN §9](/zh/operations/self-monitoring-metrics#_9-doris-自监控-由-web-采样).

## 4. Overview KPI cards

| Card | Metrics | Meaning |
|------|---------|---------|
| Inbound TPS | `ingest.otel.*.req`, `ingest.sw.*.req` | Sum(req) / window seconds |
| Write failures | `ingest.write.fail` | Failed Stream Load batches in window |
| Doris disk | `web.doris.be.used_pct` | BE used % |
| Query failures | `web.query.*.fail` | HTTP ≥ 400 by domain |

## 5. Env quick map

| Domain | Env vars | Typical action |
|--------|----------|----------------|
| Ports | `INGEST_*_PORT` | Fix host mapping |
| Pipeline drop | `INGEST_*_TASKS` / `*_BUFFER_SIZE` | Raise first |
| Write health | `INGEST_DORIS_FLUSH_*`, `MAX_READY_BATCHES`, `TRACE_FLUSH_CONCURRENCY` | Larger batches, low concurrency |
| Cluster | `INGEST_CLUSTER_*`, `ZK_CONNECT_STRING` | Multi-instance only |
| JVM | `JAVA_TOOL_OPTIONS` | Raise `-Xmx` near heap max |
| Doris | `DORIS_FE_*`, `DORIS_BE_HTTP_*` | When `up=0` or load fails |

## 6. Tool usage (query + fix)

1. Pick names + `value` / `groupBy` from this catalog (CN doc has full tables).
2. `getCurrentTimeRange` → `fromTime` / `toTime`.
3. Call **`querySelfMonitorMetrics`** with `mode=series|summary|list`.
4. Do **not** use `queryMetricData` for platform self-monitoring.
5. When the user asks to **fix**: apply the related env vars (see parameter / performance docs), prefer `docker-compose.override.yml`, restart the DataBuff service, then re-query — do not refuse with a read-only boundary.

## 7. Code / UI

| Area | Path |
|------|------|
| Naming | `PlatformMetricNames.java` |
| UI help | `ai-apm-frontend/src/views/selfMonitor/metricHelp.ts` |
| Pages | `ai-apm-frontend/src/views/selfMonitor/` |
| Schema | `V007__metric_platform.sql` |
