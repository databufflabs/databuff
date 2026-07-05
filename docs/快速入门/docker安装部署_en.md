<p align="center">
  <a href="docker安装部署.md">中文</a>
  &nbsp;|&nbsp;
  <a href="docker安装部署_en.md">English</a>
</p>

# Docker Installation

Get DataBuff running in 5 minutes — platform, storage, and ingest in one command.

## 1. Prerequisites

- Docker
- Docker Compose

## 2. Install the Platform

### Online Install

Run the command when your machine can reach the image registry; the script pulls images and deploys automatically:

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash
```

After installation, the terminal prints the Web UI URL, credentials, and OTLP endpoint. Defaults:

| Purpose | Address |
|---------|---------|
| Web UI | `http://<host-ip>:27403` |
| Default login | `admin` / `Databuff@123` |
| OTLP gRPC | `<host-ip>:4317` |
| OTLP HTTP | `http://<host-ip>:4318` |

To connect your own apps, see [OpenTelemetry OTLP Ingestion](../opentelemetry-otlp-ingestion_en.md).

Install a specific version:

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash -s -- --version 0.1.1
# or
APM_VERSION=0.1.1 curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash
```

### Offline Install

When the registry is unreachable, download the bundle for your architecture and install on the target machine. You can also pick a version on the [install page](https://databuff.ai/#install) under **Docker → Offline Install**.

```bash
tar -zxvf databuff-ai-apm-offline-<version>-<arch>.tar.gz
cd databuff-ai-apm-offline-<version>-<arch>

# Install platform
sudo ./install.sh
```

See [Offline Installation](../运维参考/离线安装_en.md) for details.

![Installation success](../images/docker-install-success.png)

Common commands:

```bash
cd /opt/databuff-ai-apm
./start.sh
./stop.sh
```

> **Operations**: [Docker Operations Reference](../运维参考/Docker运维_en.md) — install directory, start/stop, ports, health checks, logs, upgrade/uninstall. For air-gapped sites see [Offline Installation](../运维参考/离线安装_en.md).

## 3. Install the Demo (Optional)

Let the demo app continuously report traces so you can see call chains and topology in the platform.

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-demo-install.sh | bash
```

## 4. Post-Install Verification

1. Open the Web UI and sign in with the default account
2. **APM → Service List** — confirm demo services have data
3. (Optional) **Configuration → Model Settings** — enter your API key to enable AI:

![Configure API key](../images/set-api-key.png)

Example AI query: "Query service list for the last hour"
