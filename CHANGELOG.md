# Changelog

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
