<p align="center">
  <a href="docker安装部署.md">中文</a>
  &nbsp;|&nbsp;
  <a href="docker安装部署_en.md">English</a>
</p>

# Docker 安装部署

5 分钟跑起 DataBuff：平台、存储、Ingest 一条命令完成。

## 1. 准备环境

- Docker
- Docker Compose

## 2. 安装平台

### 在线安装

联网环境下执行命令，脚本会自动拉取镜像并完成部署：

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash
```

安装完成后，终端会输出 Web UI、账号和 OTLP 地址。默认值如下：

| 用途 | 地址 |
|------|------|
| Web UI | `http://<本机IP>:27403` |
| 默认账号 | `admin` / `Databuff@123` |
| OTLP gRPC | `<本机IP>:4317` |
| OTLP HTTP | `http://<本机IP>:4318` |

自有应用接入见 [OpenTelemetry OTLP 接入](../opentelemetry-otlp-ingestion.md)。

指定版本安装：

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash -s -- --version 0.1.1
# 或
APM_VERSION=0.1.1 curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash
```

### 离线安装

无法访问镜像仓库时，按架构下载离线包后在目标机器安装。也可在 [官网安装页](https://databuff.ai/#install) 的 **Docker → 离线安装** 中选择版本并下载。

```bash
tar -zxvf databuff-ai-apm-offline-<version>-<arch>.tar.gz
cd databuff-ai-apm-offline-<version>-<arch>

# 安装平台
sudo ./install.sh
```

详情见 [离线安装](../运维参考/离线安装.md)。

![安装成功](../images/docker-install-success.png)

常用命令：

```bash
cd /opt/databuff-ai-apm
./start.sh
./stop.sh
```

> **运维详情**：[Docker 运维参考](../运维参考/Docker运维.md) — 安装目录、启停、端口、健康检查、日志排障与升级卸载。内网环境见 [离线安装](../运维参考/离线安装.md)。

## 3. 安装 Demo（可选）

让 Demo 应用持续上报 Trace，打开平台就能看到链路和拓扑。

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-demo-install.sh | bash
```

## 4. 安装后验证

1. 打开 Web UI，用默认账号登录
2. **应用性能 → 服务列表**，确认 Demo 服务有数据
3. （可选）**配置管理 → 模型配置**，输入 API Key 启用 AI：

![配置 API Key](../images/set-api-key.png)

AI 示例问句：「查询最近1小时的服务列表」
