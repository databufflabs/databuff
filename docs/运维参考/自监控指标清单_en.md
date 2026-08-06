<p align="center">
  <a href="自监控指标清单.md">中文</a>
  &nbsp;|&nbsp;
  <a href="自监控指标清单_en.md">English</a>
</p>

# Platform Self-Monitoring Metric Catalog

Authoritative catalog of DataBuff **platform self-ops** metrics (ingest / web / Doris) for operators and the **product Q&A expert**. Business APM metrics (`metric_service*`, etc.) are out of scope.

- **Table**: `metric_platform`
- **UI**: Deploy → Install → Status (overview / ingest / cluster / query / Doris)
- **Unified query APIs** (shared by UI and the Q&A tool):
  - `POST /webapi/platform/metrics/query` — time series
  - `POST /webapi/platform/metrics/summary` — window aggregate
  - `POST /webapi/platform/metrics/tagValues` — distinct tag values (including metric names)
- **Naming**: `PlatformMetricNames.java`
- **Q&A tool**: `querySelfMonitorMetrics` (`platform.querySelfMonitorMetrics`) — calls the same Portal service; for DataBuff platform self-ops (not business APM)

See the Chinese document for the full metric tables, troubleshooting matrix, and query parameter examples: [自监控指标清单.md](自监控指标清单.md).

**Write time**: rows use the **previous closed minute** as `ts` / `metric_time`. Flush runs ~2s after each minute boundary; Doris/cluster gauge samplers default to second 50 so they land in that closed bucket.

## Quick reference

| Symptom | Prefer | value |
|---------|--------|-------|
| Ingest failures | `ingest.otel.*.fail`, `ingest.sw.*.fail` | `cnt` |
| Backlog | `ingest.pipeline.*.cost_ms` / `.drop` / `.queue`, `ingest.write.queue`, `ingest.trace.assembly.pending` | `avg` / `cnt` / `gauge` |
| Stream Load fail | `ingest.write.fail` / `.drop` (groupBy `dim`) | `cnt` |
| Slow/error queries | `web.query.<domain>.fail` / `.cost_ms` | `cnt` / `avg` |
| Doris down | `web.doris.up`, `*.be.alive`, `*.fe.alive` (groupBy `instance` = Doris Host) | `gauge` |
| Cluster drops | `ingest.cluster.drop.*`, `ingest.cluster.forward.*.fail` | `cnt` |

## Tool usage

1. Pick metric names and `value` / `groupBy` from this catalog (CN doc has the full list).
2. Obtain `fromTime` / `toTime` via `getCurrentTimeRange`.
3. Call **`querySelfMonitorMetrics`** with `mode=series|summary|list` — same semantics as the three Portal endpoints above.
4. Do **not** use `queryMetricData` for platform self-monitoring.
