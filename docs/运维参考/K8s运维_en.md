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
| Ingest (OTLP HTTP) | 4318 | **30418** |

Example: Web UI at `http://<node-ip>:32703`; OTLP HTTP at `http://<node-ip>:30418`.

Default login (same as Docker install):

- Username: `admin`
- Password: `Databuff@123`

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

## Notes

- Doris uses `emptyDir` in demo manifests — **re-run init SQL after Pod recreation**.
- Default resource limits match the Docker stack; see `manifests/doris.yaml`, `ingest.yaml`, `web.yaml`.

## See Also

- [Upgrade and Uninstall](升级与卸载_en.md)
- [Docker Operations Reference](Docker运维_en.md)
