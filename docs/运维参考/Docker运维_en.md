<p align="center">
  <a href="Docker运维.md">中文</a>
  &nbsp;|&nbsp;
  <a href="Docker运维_en.md">English</a>
</p>

# Docker Operations Reference

Directory layout, start/stop, ports, health checks, and troubleshooting after a one-line install. For the quick install path, see [Docker Installation](../快速入门/docker安装部署_en.md).

## Install Directory

Default path: `/opt/databuff-ai-apm`. Override with `APM_INSTALL_DIR` (supported by the install script and offline `install.sh`).

```
/opt/databuff-ai-apm/
├── docker-compose.yml    # Doris FE/BE + ingest + web
├── start.sh / stop.sh    # Recommended start/stop
├── env.sh                # Image versions
├── data/                 # Doris persistence (fe-meta, be-storage)
├── scripts/              # init-doris, pull-images, runtime helpers
└── sql/                  # databuff.sql (imported on first start)
```

## Start, Stop, and Restart

**Recommended** from the install directory:

```bash
cd /opt/databuff-ai-apm
./start.sh    # First run initializes Doris and imports schema
./stop.sh     # Stops all containers
```

Restart a single service:

```bash
docker compose restart ai-apm-web
docker compose restart ai-apm-ingest
```

`start.sh` only starts services: checks `vm.max_map_count`, waits for Doris, then starts ingest and web and probes health endpoints. Image loading is handled by `install.sh` / `update.sh` or by running `scripts/pull-images.sh` manually.

## Services and Ports

| Container | Component | Host ports | Notes |
|-----------|-----------|------------|-------|
| `ai-apm-web` | Web platform | **27403** | UI and APIs |
| `ai-apm-ingest` | Ingest | **4317** / **4318** / **11800** | OTLP gRPC / HTTP; SkyWalking Agent gRPC |
| `ai-apm-doris-fe` | Doris FE | 8030 / 9030 | HTTP / MySQL protocol |
| `ai-apm-doris-be` | Doris BE | 8040 | BE HTTP |

## Health Checks and Default Credentials

| Service | Probe URL |
|---------|-----------|
| Ingest | `http://127.0.0.1:4318/health` |
| Web | `http://127.0.0.1:27403/health` |

After install or `start.sh`, the terminal prints the Web URL and default login:

- Username: `admin`
- Password: `Databuff@123`

## Logs

```bash
cd /opt/databuff-ai-apm
docker compose logs -f ai-apm-ingest ai-apm-web
docker compose logs ai-apm-doris-fe ai-apm-doris-be
```

If readiness times out, `start.sh` suggests checking ingest / web logs.

## Common Issues

| Symptom | Action |
|---------|--------|
| Doris fails / BE OOM | Ensure enough host memory; FE `-Xmx` is patched to 1200m in compose |
| Low `vm.max_map_count` | `start.sh` tries 2000000; persist via `sysctl` on Linux |
| Port in use | Change `ports` in `docker-compose.yml` or free 27403 / 4317 / 4318 / 11800 |
| Empty service list | Point Agent/SDK to `4317`/`4318`; see [OTLP Ingestion](../opentelemetry-otlp-ingestion_en.md) |
| No alerts after creating rules | Ensure services have metrics; evaluation runs every minute; verify rule scope matches services |
| Reset schema | Run `./reset-table.sh` in the install dir (drops business tables — use with care) |

Data lives under `data/`. Stopping services does not remove data; see [Upgrade and Uninstall](升级与卸载_en.md) for full removal.

## See Also

- [Upgrade and Uninstall](升级与卸载_en.md)
- [Offline Installation](离线安装_en.md)
- [Telemetry Pipeline](../架构设计/遥测数据流_en.md)
