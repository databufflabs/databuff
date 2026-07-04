<p align="center">
  <a href="离线安装.md">中文</a>
  &nbsp;|&nbsp;
  <a href="离线安装_en.md">English</a>
</p>

# Offline Installation

Deploy the full Docker stack in air-gapped or restricted networks using the all-in-one offline bundle.

## Obtain the Bundle

Download the bundle for your architecture from [GitHub Releases](https://github.com/databufflabs/databuff/releases) or an internal mirror:

- `databuff-ai-apm-offline-<version>-amd64.tar.gz`
- `databuff-ai-apm-offline-<version>-arm64.tar.gz`

Each bundle includes deployment scripts, `ai-apm-stack` app images, and `doris-stack` infra images. Verify checksum files before use.

## Install

```bash
tar -xzf databuff-ai-apm-offline-0.1.1-amd64.tar.gz
cd databuff-ai-apm-offline-0.1.1-amd64
sudo ./install.sh
```

`install.sh` will:

1. Extract files to `APM_INSTALL_DIR` (default `/opt/databuff-ai-apm`)
2. `docker load` offline images (skips if present unless `FORCE_LOAD_IMAGES=1`)
3. Run `start.sh` (set `SKIP_START=1` to install without starting)

The terminal prints Web UI URL, default credentials, and OTLP endpoints when done.

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
