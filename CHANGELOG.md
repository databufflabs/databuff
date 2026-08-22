# Changelog

## [0.1.8] - 2026-08-22

### Features

- **GenAI / LLM traces**: decode `\uXXXX` escapes on spans whose attributes start with `gen_ai.` / `gen.ai.` / `llm.` so prompt and completion text display correctly
- **Product version**: show Maven build-info version on the login page and sidebar user menu

### Bug Fixes

- Pin log-analysis detail to ingest-generated `log_id` (schema V008)
- Fix `ResourceIgnoreFilter`
- Normalize SkyWalking SQL at ingest
- Fix Top 5 service instance grouping
- Fix chart bar filter toggle and reset on Trace / Logs
- Fix fat-jar MCP SPI lookup so remote SSE / Streamable HTTP tools actually run, and cover them through LLM chat
- Require the no-auth MCP case to hit a remote 401 so a dead client is not treated as a pass

### Deploy & Build

- Docker / K8s / offline install scripts versioned to `0.1.8`
- Schema migration V008: add `log_id` on `log_dc_record` for exact log-detail lookup

### Full changelog

Commits since `0.1.7`:

- `590e55b` fix log id bug
- `78c6b76` fix: pin log-analysis detail to ingest-generated log_id (#73)
- `cc44950` fix ResourceIgnoreFilter
- `f20b622` fix skywalking sql standard
- `8fe53d5` 支持gen.ai
- `ea785fd` fix group by top 5 service instance
- `5ef5483` update test
- `468bda3` Require the no-auth MCP case to hit a remote 401 so a dead client is not treated as a pass.
- `1792c1a` Fix fat-jar MCP SPI lookup so remote SSE/Streamable HTTP tools actually run, and cover them through LLM chat
- `7d671e9` Show product version from Maven build-info on login and sidebar user menu
- `9aeeff3` update docs
- `e883512` Fix chart bar filter toggle and reset on Trace/Logs

## [0.1.7] - 2026-08-04

### Features

- **QA Doris read query**: `POST /webapi/api/v1/ai/doris/query` and built-in tool `platform.queryDoris` so the product QA expert can verify platform config and telemetry via the web JDBC pool (read-only, row-capped)
- **QA skill / management APIs**: product QA skill documents `queryDoris` plus frontend management API login/credential discovery for config diagnosis

### Bug Fixes

- Fix portal `resourceInfo` / `resourceRelation` for non-HTTP `componentTypes`
- Honor `lt` / `lte` / `gte` comparators and warning thresholds in monitor rule evaluation
- Apply remote MCP headers after transport; add Streamable HTTP integration coverage
- Fix AI chat Mermaid rendering (per-node with syntax-repair fallback) and tighten expert Mermaid prompt hints
- Fix `getCurrentTimeRange` end-time handling; remove unused LLM `max_token` default

### Documentation

- Update download URL and related install docs

### Deploy & Build

- Docker / K8s / offline install scripts versioned to `0.1.7`

### Full changelog

Commits since `0.1.6`:

- `2890b41` Add read-only Doris query API and queryDoris tool so the QA expert can verify platform config and data without ad-hoc DB connections
- `08fc79e` fix(portal): support non-HTTP componentTypes in resourceInfo and resourceRelation
- `eeb246e` update docs
- `8cde082` fix(monitor): honor lt/lte/gte comparators and warning thresholds in rule evaluation
- `0ff8ae2` fix(mcp): apply remote MCP headers after transport and add Streamable HTTP IT
- `1d5f0a3` update getCurrentTimeRange for end
- `853552b` update download url
- `f67a2b0` fix(ai-chat): render mermaid per-node with syntax-repair fallback and tighten expert mermaid prompt hints
- `7b347bc` remove llm max_token default value

## [0.1.6] - 2026-07-30

## [0.1.5] - 2026-07-27

### Features

- **OTLP compression**: ingest supports gzip / snappy / zstd (and HTTP zlib / deflate / lz4) on OTLP trace and metric payloads
- **Span ignore lists**: drop spans by resource exact or regex ignore rules before fill and metrics
- **Mermaid in AI chat**: experts can render topology and flow diagrams inline; prompts steer experts toward Mermaid for structure
- **Log detail drawer**: right-side drawer loads full log fields on demand from the log list
- **Size-based Doris flush**: thread-local buffers and size-triggered handoff reduce lock contention on high-throughput ingest

### Improvements

- **Doris write tuning**: default flush batch raised to 50 MiB; telemetry dynamic-partition buckets reduced from 16 to 3 (V005)
- **HTTP span URL normalization**: unify resource-detail HTTP span queries on path-only `url`; normalize `meta.http.url` at ingest
- **LLM config resilience**: reload LLM settings from Doris on every probe so keys survive full-server restarts
- **JVM safety**: add `-XX:+ExitOnOutOfMemoryError` to ingest / web runtime images
- **AI session stability**: pending tool recovery for all session-scoped experts; Doris hydrate no longer tears down live expert runtimes; Mermaid rendering stabilized
- **AI test parallelism**: AI suites and cases run in parallel with same-session parallel fan-in expectations

### Bug Fixes

- Fix service instance metadata mapping edge cases
- Fix Anthropic tool-call formatter and align tool-call content with input via middleware
- Fix trace query error messaging when Doris tablet version graph is broken

### Documentation

- Migration guides and competitor comparison docs (Jaeger, SigNoz, SkyWalking, Pinpoint, OpenObserve)
- Product intro and README updates; blog content

### Deploy & Build

- Docker / K8s / offline install scripts versioned to `0.1.5`
- Schema migration V005: reduce dynamic-partition telemetry table buckets to 3

### Full changelog

Commits since `0.1.4`:

- `35d3106` update docs
- `3904f63` fix(ai): stabilize Mermaid chat rendering and prompt experts to use it for topology
- `1429037` fix(ai): stop Doris hydrate from tearing down live expert session runtimes
- `d29dc35` update doris flush max size to 50M
- `6cabe39` update table buckets to 3
- `edcd4b6` test(ai): run AI suites and cases in parallel
- `ad5b2da` fix service instance meta bug
- `46d7ec0` feat(ingest): size-based Doris flush with thread-local buffers
- `54fe499` add -XX:+ExitOnOutOfMemoryError
- `90c33a2` feat(ingest): drop spans by resource ignore lists
- `ca7f53a` feat(ingest): support OTLP gzip/snappy/zstd compression
- `aa7045f` fix: reload LLM config from Doris on every probe
- `b907835` Add log detail drawer with on-demand full fields
- `14c8d3c` fix(ai): align tool-call content with input via middleware
- `f0f768b` support Mermaid
- `53063b2` fix(ai): enable pending tool recovery for session-scoped experts
- `da56fa6` fix(resource-detail): unify HTTP span queries on path-only url
- `f114392` update to v0.1.5

## [0.1.4] - 2026-07-15

### Features

- **SkyWalking native ingest**: normalize SkyWalking/OTel MQ producers into virtual services with messaging tags and producer outbound routing (#34)
- **OTel span/resource attributes merge**: merge span and resource attributes into a single map in `buildDcSpan` for unified attribute access
- **Doris runtime failover E2E**: new comprehensive test suite (`deploy/test/doris-runtime-failover-e2e.sh` + Python ops chat) — release gate B
- **Persistence startup hydrator**: re-hydrate persistence after Doris recovery from troubleshooting mode

### Improvements

- **Doris VARCHAR limits**: expand long-text VARCHAR column limits and add ingest-side truncation to prevent schema violations (#38)
- **Doris history partitions**: create historical partitions in V003 migration so metric backfill no longer fails on missing partitions
- **Doris availability monitoring**: refactor `DorisAvailabilityMonitor` for faster fail-detection and recovery cycle
- **Docker compose**: drop `mem_limit` for docker-compose 1.22 / Compose file v3 compatibility; remove CPU limits from compose files
- **Legacy compose**: add `docker-compose.legacy.yml` for older Docker Compose versions
- **Runtime script**: add `runtime.sh` with `ensure_vm_max_map_count` helper for Docker deployments
- **RPC call detail**: hide always-empty Thread Name columns in RPC call detail table
- **Doris failover E2E**: enhance with configurable `STOP_AFTER` and improved assertions

### Bug Fixes

- Fix Doris JDBC gate to fail-close before web port opens (prevents 5s API hang when Doris is unreachable)
- Fix OTel metric row mapping edge cases in `OtlpMetricRowMapper`
- Fix `OptimizedMetricAccumulator` accumulation logic
- Fix demo compose file compatibility

### Documentation

- Python OTLP integration quick-start guide (CN + EN)
- Webhook notification configuration guide (CN + EN)
- Docker ops guide updates for release gates (CN + EN)
- Updated `README_en.md`

### Deploy & Build

- Docker images versioned to `0.1.4`
- K8s install/download scripts updated for `0.1.4`
- Offline install/update/demo scripts updated
- `build-docker.sh` packages SQL migrations and `upgrade-manifest.json`

### Full changelog

Commits since `0.1.3`:

- `2926894` update version to 0.1.4
- `41f1bd6` fix: drop compose mem_limit for docker-compose 1.22 / Compose file v3 compatibility
- `dbde89f` fix: expand Doris long-text VARCHAR limits and truncate on ingest (#38)
- `5b75ab8` Merge otel span/resource attributes into a single map in buildDcSpan
- `cfb0590` 去掉docker cpu limit
- `76ac2ca` normalize skywalking/otel mq producers into virtual services like redis with messaging tags and producer outbound
- `2529aee` Merge pull request #34 from kiddo90-N/master
- `e4d4f9c` docs: add python otlp integration quick-start guides
- `ee4c128` docs: integrate webhook configuration guide and update notification status
- `773574c` Update README_en.md
- `6046bee` docs: add English configuration guide for webhook notification channel
- `c3ad7c9` fix: create Doris history partitions in V003 so metric backfill no longer fails
- `ad3a73b` Merge pull request #25 from mvanhorn/fix/rpc-hide-empty-threadname-columns
- `7aa39e1` fix: hide always-empty Thread Name columns in RPC call detail table
- `1ec7794` update version to v0.1.4

**Contributors**: Brio Griondy Dahlinar, Matt Van Horn, databufflabs, ligang
