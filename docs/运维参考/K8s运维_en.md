<p align="center">
  <a href="K8s运维.md">中文</a>
  &nbsp;|&nbsp;
  <a href="K8s运维_en.md">English</a>
</p>

# Kubernetes Operations Reference

Install, start/stop, access, and image management for the K8s deployment bundle. For the quick install path, see [Kubernetes Installation](../快速入门/k8s安装部署_en.md).

## Bundle Layout

After extracting the one-line install package:

```
databuff-ai-apm-k8s-<version>/
├── install.sh          # Fresh install (after uninstall)
├── start.sh            # Start in order (does not remove resources)
├── stop.sh             # Scale to 0; keeps Service / ConfigMap
├── download-images.sh
├── download-apm-images.sh
├── manifests/          # ZooKeeper, Doris, ingest, web
├── sql/
└── scripts/lib.sh
```

Default namespace: **`databuff`**. `install.sh` / `start.sh` use plain `kubectl` (no Helm): ZooKeeper + Doris → wait and run `databuff.sql` (skipped if tables exist) → ingest + web.

## Start and Stop

```bash
cd databuff-ai-apm-k8s-<version>
./install.sh    # Fresh install (cleans same-named resources first)
./start.sh      # Start only
./stop.sh       # Scale ingest / web / doris / zookeeper to 0
```

## Access

| Service | In-cluster port | NodePort (node access) |
|---------|-----------------|-------------------------|
| Web | 27403 | **32703** |
| Ingest (OTLP gRPC) | 4317 | **30417** |
| Ingest (OTLP HTTP) | 4318 | **30418** |

Example: Web UI at `http://<node-ip>:32703`; external OTLP HTTP at `http://<node-ip>:30418` (gRPC at `30417`). In-cluster Service `ai-apm-ingest`: `http://ai-apm-ingest:4318`.

Default login (same as Docker; when unchanged):

- Username: `admin`
- Password: `Databuff@123`

`start.sh` / `install.sh` print the **effective** credentials from the Web Pod env. To change login credentials, Doris connection credentials, or Ingest task settings, see [Parameter Configuration](参数配置_en.md).

## Offline Images

When nodes cannot pull from a registry (amd64/arm64 auto-detected):

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-download-images.sh | bash
# or from the bundle
./download-images.sh
```

k3s / containerd:

```bash
export IMAGE_LOAD_CMD=ctr
curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-download-images.sh | bash
```

Upgrade **ingest / web** images only (not Doris / ZooKeeper):

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-download-apm-images.sh | bash
# or
./download-apm-images.sh
```

## Ingest Scaling (Optional)

A single replica runs in standalone mode. With multiple replicas and cluster coordination enabled, ingest uses ZooKeeper for membership:

```bash
kubectl scale deploy/ai-apm-ingest -n databuff --replicas=4
kubectl rollout status deploy/ai-apm-ingest -n databuff
```

## Health Checks and Logs

| Service | Probe |
|---------|-------|
| Web | `kubectl exec -n databuff deploy/ai-apm-web -- wget -qO- http://127.0.0.1:27403/health` |
| Ingest | `kubectl exec -n databuff deploy/ai-apm-ingest -- wget -qO- http://127.0.0.1:4318/health` |

```bash
kubectl logs -n databuff deploy/ai-apm-ingest -f
kubectl logs -n databuff deploy/ai-apm-web -f
```

## Common Issues

| Symptom | Action |
|---------|--------|
| Empty service list | Point Agent to `http://ai-apm-ingest:4318` (NodePort outside cluster); see [OTLP Ingestion](../opentelemetry-otlp-ingestion_en.md) |
| No alerts after creating rules | Ensure services have metrics; evaluation runs every minute; verify rule scope |
| Pod fails to start | `kubectl describe pod -n databuff <pod>` for Events; check node memory |
| FE log `CgroupInfo.getMountPoint()` / `anyController is null` | JDK cgroup v2 probe failure; see [FAQ](常见问题_en.md#doris-fe-fails-to-start-cgroupinfogetmountpoint-npe) |

## Notes

- Doris uses `emptyDir` in demo manifests — **re-run init SQL after Pod recreation**.
- Default resource limits match the Docker stack; see `manifests/doris.yaml`, `ingest.yaml`, `web.yaml`.

## See Also

- [FAQ](常见问题_en.md)
- [Upgrade and Uninstall](升级与卸载_en.md)
- [Docker Operations Reference](Docker运维_en.md)
