<p align="center">
  <a href="升级与卸载.md">中文</a>
  &nbsp;|&nbsp;
  <a href="升级与卸载_en.md">English</a>
</p>

# Upgrade and Uninstall

Version upgrades, data retention, and full removal on Docker and Kubernetes.

## Docker: Upgrade

Re-install with a specific version (extracts a new bundle into `APM_INSTALL_DIR`, replacing scripts and compose by default):

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash -s -- --version 0.1.1
# or
APM_VERSION=0.1.1 curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash
```

Force re-download of image bundles:

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash -s -- --pull-images
# or
FORCE_PULL_IMAGES=1 curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash
```

Then run `./start.sh` in the install directory. Doris data under `data/` is kept by default; back up `data/` before major upgrades.

Download only without starting:

```bash
SKIP_START=1 curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash
```

## Docker: Uninstall

```bash
cd /opt/databuff-ai-apm   # or your APM_INSTALL_DIR
./stop.sh
cd ..
sudo rm -rf /opt/databuff-ai-apm
```

Back up `data/fe-meta` and `data/be-storage` first if you need to keep telemetry data.

## Kubernetes: Upgrade ingest / web

Without replacing Doris / ZooKeeper, re-import app images on each node and roll out:

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-download-apm-images.sh | bash
kubectl rollout restart deploy/ai-apm-ingest deploy/ai-apm-web -n databuff
```

Full stack upgrade: run the target version's `install.sh` (recreates resources per bundle logic).

## Kubernetes: Uninstall

Follow the bundle's uninstall/cleanup before `install.sh`, or manually:

```bash
kubectl delete namespace databuff
```

With Doris on `emptyDir`, deleting Pods removes stored data.

## See Also

- [Docker Operations Reference](Docker运维_en.md)
- [Kubernetes Operations Reference](K8s运维_en.md)
- [Offline Installation](离线安装_en.md)
