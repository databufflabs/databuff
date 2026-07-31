<p align="center">
  <a href="参数配置.md">中文</a>
  &nbsp;|&nbsp;
  <a href="参数配置_en.md">English</a>
</p>

# Parameter Configuration

How to change the login account after install, tune Ingest pipeline task settings, and adjust telemetry retention. For directories and lifecycle, see [Docker Operations](Docker运维_en.md) and [Kubernetes Operations](K8s运维_en.md). For capacity planning and additional knobs, see [Performance Tuning](性能优化_en.md).

## 1. Change login username and password

The open-source Web uses a built-in seed account loaded at startup (in memory). **There is no UI to change credentials.** Override via environment variables and restart `ai-apm-web`.

| YAML key | Environment variable | Default |
|----------|----------------------|---------|
| `apm.security.seed-username` | `APM_SECURITY_SEED_USERNAME` | `admin` |
| `apm.security.seed-password` | `APM_SECURITY_SEED_PASSWORD` | `Databuff@123` |

> Sign in again with the new account afterward; old sessions/tokens will not work. The password is stored in env vars—protect host access accordingly.
> After `start.sh` / install completes, the summary prints the **effective** credentials from the running Web container (not a hard-coded default).

### Docker

**Preferred:** `docker-compose.override.yml` (merged automatically; upgrades do not overwrite it):

```bash
cd /opt/databuff-ai-apm   # or: echo $APM_INSTALL_DIR

cat > docker-compose.override.yml <<'EOF'
services:
  ai-apm-web:
    environment:
      APM_SECURITY_SEED_USERNAME: "youruser"
      APM_SECURITY_SEED_PASSWORD: "your-strong-password"
EOF

docker compose up -d ai-apm-web
docker exec ai-apm-web printenv | grep '^APM_SECURITY_SEED_'
```

You can edit `docker-compose.yml` under `ai-apm-web.environment` instead, but **upgrades replace** `docker-compose.yml`—re-apply changes or use override.

### Kubernetes

Add keys to the ConfigMap and restart Web:

```bash
kubectl -n databuff edit configmap ai-apm-config
```

Under `data:`:

```yaml
  APM_SECURITY_SEED_USERNAME: "youruser"
  APM_SECURITY_SEED_PASSWORD: "your-strong-password"
```

Then:

```bash
kubectl -n databuff rollout restart deploy/ai-apm-web
kubectl -n databuff rollout status deploy/ai-apm-web
kubectl -n databuff exec deploy/ai-apm-web -- printenv | grep '^APM_SECURITY_SEED_'
```

Editing the Deployment `env` (`kubectl -n databuff edit deploy/ai-apm-web`) works the same way.

> **Upgrade note:** Re-running `install.sh` / `start.sh` applies the stock `configmap.yaml` and may wipe hand-added keys. Re-apply them after upgrade, or keep a local patch checklist.

## 2. Adjust Ingest task parameters

Ingest uses worker pools and ring buffers for Trace / Metric / aggregation. Defaults live in `ai-apm-ingest` `application.yml` and can be overridden by env vars.

### Common task and buffer settings

| YAML key | Environment variable | Default | Meaning |
|----------|----------------------|---------|---------|
| `ingest.pipeline.trace-tasks` | `INGEST_TRACE_TASKS` | `8` | Trace parse/assemble parallelism |
| `ingest.pipeline.metric-tasks` | `INGEST_METRIC_TASKS` | `4` | Metric routing parallelism |
| `ingest.pipeline.aggregate-tasks` | `INGEST_AGGREGATE_TASKS` | `4` | Minute aggregation workers |
| `ingest.pipeline.trace-buffer-size` | `INGEST_TRACE_BUFFER_SIZE` | `8192` | Ring slots per trace worker (≥16) |
| `ingest.pipeline.metric-buffer-size` | `INGEST_METRIC_BUFFER_SIZE` | `1024` | Ring slots per metric worker |
| `ingest.pipeline.aggregate-buffer-size` | `INGEST_AGGREGATE_BUFFER_SIZE` | `1024` | Ring slots per aggregate worker |

When buffers fill, events are dropped (`overflow`). If the UI misses data but ingest logs look healthy, raise `*_BUFFER_SIZE` or `*_TASKS` first. For Doris flush and related knobs, see [Performance Tuning — Ingest pipeline](性能优化_en.md#2-ingest-pipeline-tuning).

### Docker

Prefer `docker-compose.override.yml`:

```bash
cd /opt/databuff-ai-apm

cat > docker-compose.override.yml <<'EOF'
services:
  ai-apm-ingest:
    environment:
      INGEST_TRACE_TASKS: "16"
      INGEST_TRACE_BUFFER_SIZE: "16384"
      INGEST_METRIC_TASKS: "8"
EOF

docker compose up -d ai-apm-ingest
docker exec ai-apm-ingest printenv | grep '^INGEST_'
```

If the same override already sets Web credentials, append under the matching `services` entries instead of replacing the whole file.

### Kubernetes

```bash
kubectl -n databuff edit configmap ai-apm-config
```

Under `data:`:

```yaml
  INGEST_TRACE_TASKS: "16"
  INGEST_TRACE_BUFFER_SIZE: "16384"
  INGEST_METRIC_TASKS: "8"
```

Restart ingest:

```bash
kubectl -n databuff rollout restart deploy/ai-apm-ingest
kubectl -n databuff rollout status deploy/ai-apm-ingest
kubectl -n databuff exec deploy/ai-apm-ingest -- printenv | grep '^INGEST_'
```

> Same upgrade caveat as login credentials: re-applying the stock ConfigMap drops custom keys.

### Tuning tips

- Change one or two knobs at a time so you can roll back easily.
- Fix drops first (larger buffers / tasks), then tune against CPU and Doris Stream Load pressure.
- Higher `*_TASKS` uses more CPU and memory—see the baselines in [Performance Tuning](性能优化_en.md).

## 3. Adjust storage retention

To keep data for 14 days, connect to Doris and run the following SQL (no FE / BE restart required):

```sql
USE databuff;
ALTER TABLE trace_dc_span SET ("dynamic_partition.start" = "-14");
ALTER TABLE log_dc_record SET ("dynamic_partition.start" = "-14");
ALTER TABLE metric_service SET ("dynamic_partition.start" = "-14");
-- Repeat for other metric_* tables as needed
```

## Related docs

- [Docker Operations](Docker运维_en.md)
- [Kubernetes Operations](K8s运维_en.md)
- [Performance Tuning and Capacity](性能优化_en.md)
- [Upgrade and Uninstall](升级与卸载_en.md)
