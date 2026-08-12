<p align="center">
  <a href="参数配置.md">中文</a>
  &nbsp;|&nbsp;
  <a href="参数配置_en.md">English</a>
</p>

# Parameter Configuration

How to skip the AVX2 CPU check at install time, and after install how to change the login account, configure Doris connection credentials and FE/BE addresses, tune Ingest pipeline task settings, configure Trace resource ignore filtering, and adjust telemetry retention. For directories and lifecycle, see [Docker Operations](Docker运维_en.md) and [Kubernetes Operations](K8s运维_en.md). For capacity planning and additional knobs, see [Performance Tuning](性能优化_en.md).

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

## 2. Configure Doris username and password

Credentials used by Web / Ingest for Doris JDBC and Stream Load (**not** the Web UI login above). Defaults match a stock Doris install: `root` with an empty password.

| Component | YAML key | Environment variable | Default |
|-----------|----------|----------------------|---------|
| Web | `apm.doris.username` | `DORIS_USER` | `root` |
| Web | `apm.doris.password` | `DORIS_PASSWORD` | empty |
| Ingest | `ingest.doris.username` | `DORIS_USER` | `root` |
| Ingest | `ingest.doris.password` | `DORIS_PASSWORD` | empty |

Both components share the same env vars; restart `ai-apm-web` and `ai-apm-ingest` after changing them.

> **Order:** Change or create the Doris account first (e.g. `SET PASSWORD FOR 'root' = PASSWORD('your-strong-password');`), then update the app env vars. Changing only the app side while Doris still uses an empty password will break connectivity.

### Docker

Prefer `docker-compose.override.yml` (set on both Web and Ingest):

```bash
cd /opt/databuff-ai-apm   # or: echo $APM_INSTALL_DIR

cat > docker-compose.override.yml <<'EOF'
services:
  ai-apm-web:
    environment:
      DORIS_USER: "root"
      DORIS_PASSWORD: "your-strong-password"
  ai-apm-ingest:
    environment:
      DORIS_USER: "root"
      DORIS_PASSWORD: "your-strong-password"
EOF

docker compose up -d ai-apm-web ai-apm-ingest
docker exec ai-apm-web printenv | grep '^DORIS_USER\|^DORIS_PASSWORD'
docker exec ai-apm-ingest printenv | grep '^DORIS_USER\|^DORIS_PASSWORD'
```

If the same override already has other settings, append under the matching `services` entries instead of replacing the whole file.

### Kubernetes

```bash
kubectl -n databuff edit configmap ai-apm-config
```

Under `data:`:

```yaml
  DORIS_USER: "root"
  DORIS_PASSWORD: "your-strong-password"
```

Then restart Web and Ingest:

```bash
kubectl -n databuff rollout restart deploy/ai-apm-web deploy/ai-apm-ingest
kubectl -n databuff rollout status deploy/ai-apm-web
kubectl -n databuff rollout status deploy/ai-apm-ingest
kubectl -n databuff exec deploy/ai-apm-web -- printenv | grep '^DORIS_USER\|^DORIS_PASSWORD'
```

> Same upgrade caveat as login credentials: re-applying the stock ConfigMap drops custom keys. The password lives in env vars—protect host access accordingly.

## 3. Configure Doris FE / BE addresses

Supports **comma-separated multi-FE / multi-BE** (`host` or `host:port` lists + health round-robin). Single-node defaults for one-line installs are unchanged.

### What each port is for

| Port (default) | Environment variable | Used by | Purpose |
|----------------|----------------------|---------|---------|
| **9030** | `DORIS_FE_QUERY_PORT` | Web, Ingest | FE **JDBC** (MySQL protocol reads) |
| **8030** | `DORIS_FE_HTTP_PORT` | Mainly Ingest | FE **HTTP**; used for Stream Load **only when** `DORIS_BE_HTTP_HOST` is **unset** — PUT FE `_stream_load`, then 307 to BE |
| **8040** | `DORIS_BE_HTTP_PORT` | **Ingest only** | BE **HTTP**; when `DORIS_BE_HTTP_HOST` is set, Stream Load goes **directly to BE** (recommended) |

One-line installs set `DORIS_BE_HTTP_HOST` by default, so writes use **BE:8040** and **8030 is rarely used**. Web queries use **9030** only — no Stream Load, no BE setting.

```text
Query:  Web/Ingest  --JDBC-->  FE:9030
Write:  Ingest  --Stream Load-->  BE:8040           (BE configured; default)
   or:  Ingest  --Stream Load-->  FE:8030 -307-> BE (BE unset)
```

### Parameters

| Purpose | Component | YAML key | Environment variable | Default |
|---------|-----------|----------|----------------------|---------|
| FE host (multi OK) | Web / Ingest | `apm.doris.fe-host` / `ingest.doris.fe-host` | `DORIS_FE_HOST` | `127.0.0.1` |
| FE query port (JDBC) | Web / Ingest | `*.fe-query-port` | `DORIS_FE_QUERY_PORT` | `9030` |
| FE HTTP port (only when Stream Load goes via FE, i.e. BE unset) | Ingest (mainly) | `*.fe-http-port` | `DORIS_FE_HTTP_PORT` | `8030` |
| BE HTTP host (multi OK, Stream Load) | **Ingest only** | `ingest.doris.be-http-host` | `DORIS_BE_HTTP_HOST` | empty (see below) |
| BE HTTP port (default when host has no port) | **Ingest only** | `ingest.doris.be-http-port` | `DORIS_BE_HTTP_PORT` | `8040` |

One-line Docker / K8s defaults:

| Environment variable | Docker Compose default | Notes |
|----------------------|------------------------|-------|
| `DORIS_FE_HOST` | `ai-apm-doris-fe` | Host for JDBC and (optional) FE HTTP |
| `DORIS_BE_HTTP_HOST` | `ai-apm-doris-be` | Ingest direct Stream Load to BE |

### Address and port format

Both FE and BE `*_HOST` values support these three forms (comma-separated; host-only and host:port may be mixed):

| Form | FE example | BE example |
|------|------------|------------|
| Single host | `DORIS_FE_HOST=fe1` | `DORIS_BE_HTTP_HOST=be1` |
| Multi-host (shared port) | `DORIS_FE_HOST=fe1,fe2` | `DORIS_BE_HTTP_HOST=be1,be2` |
| Multi-host (per-entry port) | `DORIS_FE_HOST=fe1:19030,fe2:19031` | `DORIS_BE_HTTP_HOST=be1:8040,be2:8041` |

**When an entry omits the port**, use the matching PORT env var above; if unset, defaults are 9030 / 8030 / 8040.

Notes:

- `fe1,fe2`: JDBC uses **9030 (query)**; FE Stream Load (if used) uses **8030 (http)** — each default applies on its path.
- `fe1:p1,fe2:p2`: the explicit port applies to **both** JDBC and FE HTTP for that entry (one host field, one port). If query and http ports differ, prefer `fe1,fe2` + separate PORT vars.
- `be1,be2` + `DORIS_BE_HTTP_PORT=8040` matches the pre-multi-node host/port split; `be1:8040,be2:8040` also works.
- Multi-FE JDBC uses `jdbc:mysql:loadbalance://...`.

### With vs without BE host

| Scenario | Behavior |
|----------|----------|
| **`DORIS_BE_HTTP_HOST` set** (recommended; Docker default) | Stream Load goes **directly to BE:8040**; multi-BE health round-robin, failover, `/api/health` probes. **Does not use** FE:8030 |
| **Unset** (empty) | Stream Load hits **FE:8030** `_stream_load`, then 307 to BE; less reliable with split FE/BE — keep BE set for one-line installs |

### Multi-host guidance

1. **Writes (recommended):** `DORIS_BE_HTTP_HOST=be1,be2,be3` + `DORIS_BE_HTTP_PORT=8040` (or `be1:8040,be2:8040`).
2. **Queries:** `DORIS_FE_HOST=fe1,fe2` + `DORIS_FE_QUERY_PORT`; you usually do not need to change `DORIS_FE_HTTP_PORT` for writes.
3. Table replicas (`replication_num ≥ 2`) are cluster-side redundancy and complement the app address list.

### Docker example

For an external Doris or custom addresses, use `docker-compose.override.yml`:

```bash
cd /opt/databuff-ai-apm

cat > docker-compose.override.yml <<'EOF'
services:
  ai-apm-web:
    environment:
      DORIS_FE_HOST: "192.168.1.10,192.168.1.11"
      DORIS_FE_QUERY_PORT: "9030"
      DORIS_FE_HTTP_PORT: "8030"
  ai-apm-ingest:
    environment:
      DORIS_FE_HOST: "192.168.1.10,192.168.1.11"
      DORIS_FE_QUERY_PORT: "9030"
      DORIS_FE_HTTP_PORT: "8030"
      # Compatible form: hosts + shared port (same as pre-multi-node)
      DORIS_BE_HTTP_HOST: "192.168.1.20,192.168.1.21"
      DORIS_BE_HTTP_PORT: "8040"
      # Or ultra-style: DORIS_BE_HTTP_HOST: "192.168.1.20:8040,192.168.1.21:8040"
EOF

docker compose up -d ai-apm-web ai-apm-ingest
```

### Kubernetes

```bash
kubectl -n databuff edit configmap ai-apm-config
```

Under `data:`, for example:

```yaml
  DORIS_FE_HOST: "192.168.1.10,192.168.1.11"
  DORIS_FE_QUERY_PORT: "9030"
  DORIS_FE_HTTP_PORT: "8030"
  DORIS_BE_HTTP_HOST: "192.168.1.20,192.168.1.21"
  DORIS_BE_HTTP_PORT: "8040"
```

Then restart Web and Ingest:

```bash
kubectl -n databuff rollout restart deploy/ai-apm-web deploy/ai-apm-ingest
```

## 4. Adjust Ingest task parameters

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

When buffers fill, events are dropped (`overflow`). If the UI misses data but ingest logs look healthy, raise `*_BUFFER_SIZE` or `*_TASKS` first. For Doris flush and related knobs, see [Performance Tuning — Ingest pipeline](性能优化_en.md#_2-ingest-pipeline-tuning).

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

## 5. Trace resource ignore filtering

Drop matching spans (skip enrich / assemble / write and metrics) to filter noise such as health checks, Prometheus scrapes, and `SELECT 1`. Matching uses the span `resource` and, for HTTP, `metaHttpUrl` (either hit drops the span). Exact rules use full-string equality; regex uses Java `Matcher#matches()` (full-string match).

| YAML key | Environment variable | Default | Meaning |
|----------|----------------------|---------|---------|
| `ingest.trace.ignore-resources` | `INGEST_TRACE_IGNORE_RESOURCES` | empty | Exact-match list; comma-separated in env |
| `ingest.trace.ignore-resource-regex` | `INGEST_TRACE_IGNORE_RESOURCE_REGEX` | empty | Full-string regex list; comma-separated in env |

### Option 1: Edit `application.yml`

For local development or when you mount / bake config into the image. Edit ingest `application.yml`:

```yaml
ingest:
  trace:
    ignore-resources:
      - PING
      - /actuator/prometheus
    ignore-resource-regex:
      - ^/actuator(/.*)?$
      - ^SELECT 1$
```

### Option 2: Docker Compose environment variables

Preferred for one-line installs — use `docker-compose.override.yml` (survives upgrades):

```bash
cd /opt/databuff-ai-apm   # or: echo $APM_INSTALL_DIR

cat > docker-compose.override.yml <<'EOF'
services:
  ai-apm-ingest:
    environment:
      INGEST_TRACE_IGNORE_RESOURCES: "PING,/actuator/prometheus"
      INGEST_TRACE_IGNORE_RESOURCE_REGEX: "^/actuator(/.*)?$,^SELECT 1$"
EOF

docker compose up -d ai-apm-ingest
```

If the same override already sets other ingest knobs, append under `environment` instead of replacing the whole file. If a regex or path contains commas, prefer Option 1 (YAML lists) to avoid delimiter ambiguity.

### Kubernetes

```bash
kubectl -n databuff edit configmap ai-apm-config
```

Under `data:`:

```yaml
  INGEST_TRACE_IGNORE_RESOURCES: "PING,/actuator/prometheus"
  INGEST_TRACE_IGNORE_RESOURCE_REGEX: "^/actuator(/.*)?$,^SELECT 1$"
```

Restart ingest:

```bash
kubectl -n databuff rollout restart deploy/ai-apm-ingest
kubectl -n databuff rollout status deploy/ai-apm-ingest
```

### Verify

After restart, ingest logs should contain `Span resource ignore filter enabled` when rules are loaded.

## 6. Adjust storage retention

To keep data for 14 days, connect to Doris and run the following SQL (no FE / BE restart required):

```sql
USE databuff;
ALTER TABLE trace_dc_span SET ("dynamic_partition.start" = "-14");
ALTER TABLE log_dc_record SET ("dynamic_partition.start" = "-14");
ALTER TABLE metric_service SET ("dynamic_partition.start" = "-14");
-- Repeat for other metric_* tables as needed
```

## 7. Skip AVX2 CPU check (install time)

Doris BE on **x86_64 / amd64** requires AVX2. Online/offline installers check the CPU and **exit 1** if the `avx2` flag is missing. For PoC or legacy VMs only, export this env var before install:

| Environment variable | Value | Meaning |
|----------------------|-------|---------|
| `DATABUFF_SKIP_AVX2_CHECK` | `1` or `true` | Skip the installer's AVX2 check (warning only, does not abort) |

**Online install:**

```bash
export DATABUFF_SKIP_AVX2_CHECK=1
curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash
```

**Offline install:**

```bash
export DATABUFF_SKIP_AVX2_CHECK=1
cd /path/to/databuff-docker-offline-*-amd64
./install-offline.sh
```

> This only bypasses the **installer** gate; it does not remove Doris's AVX2 dependency. Prefer AVX2-capable x86_64 or arm64 for production. Full risks and legacy-script workarounds: [Performance Tuning — Bypass AVX2 check](性能优化_en.md#_7-bypass-avx2-check-on-x86-64-without-avx2).

## Related docs

- [Docker Operations](Docker运维_en.md)
- [Kubernetes Operations](K8s运维_en.md)
- [Performance Tuning and Capacity](性能优化_en.md)
- [Upgrade and Uninstall](升级与卸载_en.md)
