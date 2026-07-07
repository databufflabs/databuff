<p align="center">
  <a href="升级与卸载.md">中文</a>
  &nbsp;|&nbsp;
  <a href="升级与卸载_en.md">English</a>
</p>

# Upgrade and Uninstall

Upgrade and remove DataBuff on Docker.

## Upgrade

On an existing deployment, use the **upgrade** command. Do not re-run `install.sh` — a fresh install wipes telemetry data under `data/`.

### Platform · online

When your machine can reach the CDN, upgrade to the latest release in one command:

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-update.sh | bash
```

### Platform · offline

When the CDN is unreachable, download the target offline bundle, extract it, and run:

```bash
tar -zxvf databuff-ai-apm-offline-<version>-<arch>.tar.gz
cd databuff-ai-apm-offline-<version>-<arch>
sudo ./update.sh
```

See [Offline Installation](离线安装_en.md) for download URLs.

### Demo · online

If you installed the demo app, run on a connected machine:

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-demo-update.sh | bash
```

### Demo · offline

From the extracted offline bundle directory (stops the old demo, then replaces it):

```bash
sudo ./install_demo.sh
```

## Uninstall

### Platform

```bash
cd /opt/databuff-ai-apm
./stop.sh
cd ..
sudo rm -rf /opt/databuff-ai-apm
```

Back up `data/` first if you need to keep telemetry data.

### Demo

The demo is installed separately from the platform. Removing it **does not** delete the platform or telemetry under `data/`.

```bash
cd /opt/databuff-ai-apm-demo
./stop.sh
cd ..
sudo rm -rf /opt/databuff-ai-apm-demo
```

If you set a custom path via `APM_INSTALL_DIR` at install time, use that directory instead of `/opt/databuff-ai-apm-demo`.

## See Also

- [Docker Installation](../快速入门/docker安装部署_en.md)
- [Docker Operations Reference](Docker运维_en.md)
- [Offline Installation](离线安装_en.md)
