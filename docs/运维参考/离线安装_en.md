<p align="center">
  <a href="离线安装.md">中文</a>
  &nbsp;|&nbsp;
  <a href="离线安装_en.md">English</a>
</p>

# Offline Installation

When the image registry is unreachable, download the offline bundle for your architecture and install on the target machine — **no network required**.

## Prerequisites

- Docker
- Docker Compose
- root privileges (default install path `/opt/databuff-ai-apm`)

## Download the Bundle

Download the all-in-one offline bundle matching your CPU architecture.

**Recommended**: pick a version and copy the download link on the [install page](https://databuff.ai/#install) under **Docker → Offline Install**.

| Architecture | Bundle filename |
|--------------|-----------------|
| amd64 | `databuff-ai-apm-offline-<version>-amd64.tar.gz` |
| arm64 | `databuff-ai-apm-offline-<version>-arm64.tar.gz` |

Download URLs (replace `<version>` with the release, e.g. `0.1.1`):

- amd64: `https://openocta.com/pkg/databuff/<version>/offline/databuff-ai-apm-offline-<version>-amd64.tar.gz`
- arm64: `https://openocta.com/pkg/databuff/<version>/offline/databuff-ai-apm-offline-<version>-arm64.tar.gz`

Example CLI download (amd64 / `0.1.1`):

```bash
curl -fLO https://openocta.com/pkg/databuff/0.1.1/offline/databuff-ai-apm-offline-0.1.1-amd64.tar.gz
```

To resolve the latest release automatically, `https://openocta.com/pkg/databuff/VERSION` returns plain text (e.g. `0.1.1`) — **not** the bundle. Query the version first, then download:

```bash
VERSION=$(curl -fsSL https://openocta.com/pkg/databuff/VERSION)
ARCH=amd64   # or arm64
curl -fLO "https://openocta.com/pkg/databuff/${VERSION}/offline/databuff-ai-apm-offline-${VERSION}-${ARCH}.tar.gz"
```

Each bundle includes deployment scripts, `ai-apm-stack` app images, and `doris-stack` infra images.

## Install

Example for `0.1.1` / `amd64` (download on a connected machine, then copy the bundle to the target):

```bash
tar -zxvf databuff-ai-apm-offline-0.1.1-amd64.tar.gz
cd databuff-ai-apm-offline-0.1.1-amd64

# Install platform
sudo ./install.sh
```

`install.sh` will:

1. Extract files to `APM_INSTALL_DIR` (default `/opt/databuff-ai-apm`)
2. `docker load` offline images (skips if present unless `FORCE_LOAD_IMAGES=1`)
3. Run `start.sh` (set `SKIP_START=1` to install without starting)

The terminal prints Web UI URL, default credentials, and OTLP endpoints when done.

### Install Demo (Optional)

```bash
sudo ./install_demo.sh
```

## Environment Variables

| Variable | Description |
|----------|-------------|
| `APM_INSTALL_DIR` | Install path, default `/opt/databuff-ai-apm` |
| `SKIP_START` | `1` = install only, do not start |
| `FORCE_LOAD_IMAGES` | `1` = force `docker load` |

## vs Online Install

Online install uses `ai-apm-install.sh` to fetch the deploy bundle and per-arch image packages from CDN. Offline install **does not require curl, CDN, or a registry** — suitable for USB transfer or air-gapped sites.

Online one-liner (when network is available):

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash
```

## Recovery

| Issue | Suggestion |
|-------|------------|
| `docker load` fails | Check disk space; retry with `FORCE_LOAD_IMAGES=1` |
| Start timeout | See logs in [Docker Operations Reference](Docker运维_en.md) |
| Change version | Stop services, extract the target offline bundle, run `install.sh` |

## See Also

- [Docker Installation](../快速入门/docker安装部署_en.md)
- [Docker Operations Reference](Docker运维_en.md)
- [Upgrade and Uninstall](升级与卸载_en.md)
