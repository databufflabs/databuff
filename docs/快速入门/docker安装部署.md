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

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash
```

安装完成后，终端会输出 Web UI、账号和 OTLP 地址。

指定版本安装：

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash -s -- --version 0.1.1
# 或
APM_VERSION=0.1.1 curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash
```

![安装成功](../images/docker-install-success.png)

常用命令：

```bash
cd /opt/databuff-ai-apm
docker-compose up -d
docker-compose down
```

> **运维详情**：[Docker 运维参考](../运维参考/Docker运维.md) — 安装目录、启停、端口、健康检查、日志排障与升级卸载。内网环境见 [离线安装](../运维参考/离线安装.md)。

## 3. 安装 Demo（可选）

让 Demo 应用持续上报 Trace，打开平台就能看到链路和拓扑。

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-demo-install.sh | bash
```

## 4. 启用 AI

配置管理 → 模型配置，输入 API Key 即可：

![配置 API Key](../images/set-api-key.png)

现在可以直接问：

> order-service 为什么变慢了？
