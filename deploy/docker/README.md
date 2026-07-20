# Docker 部署

ingest、web、Doris 默认从离线镜像包目录按本机架构下载并 `docker load`；运行时挂载 `data/`（Doris + web 持久化）。应用镜像统一为 `databuffhub/*` 短名。

## 目录结构

```
docker/
├── docker-compose.yml    # 主栈（Doris + ingest + web）
├── env.sh                # 运行时镜像配置（与 deploy/env.sh 一致）
├── start.sh / stop.sh / update.sh
├── demo/                 # demo 造数 Compose 包
├── ai-apm-install.sh
├── ai-apm-update.sh
├── ai-apm-offline-install.sh   # 离线包内 install.sh 模板
├── ai-apm-offline-update.sh    # 离线包内 update.sh 模板
├── ai-apm-demo-install.sh
├── ai-apm-demo-update.sh
├── ai-apm-offline-demo-install.sh
├── build-docker.sh       # 打部署包并 SCP 上传到 databuff-site
├── build-docker-offline.sh # 打一体化离线大包（部署包 + 镜像）
├── data/                 # 持久化（Doris FE/BE + web 报告/Skill）
└── scripts/
```

镜像构建上下文见 [`../images/`](../images/)。

## 目标机安装

一键安装 / 升级脚本会按本机架构（amd64/arm64）从 `APM_IMAGES_PKG_BASE` 下载镜像包并 `docker load`；`start.sh` 仅负责启动：

```bash
# 全新安装（删除旧目录与 data/）
curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash

# 就地升级（保留 data/）
curl -fsSL https://databuff.ai/databuff/ai-apm-update.sh | bash
cd /opt/databuff-ai-apm && ./update.sh
```

## 离线安装（一体化大包）

无法访问镜像仓库时，按架构从 `${APM_PKG_BASE}/<version>/offline/` 下载离线包（对外地址示例：`https://openocta.com/pkg/databuff/0.1.2/offline/`），解压后执行 `install.sh`，**全程无需联网**：

```bash
tar -zxvf databuff-ai-apm-offline-0.1.2-amd64.tar.gz
cd databuff-ai-apm-offline-0.1.2-amd64

# 全新安装
sudo ./install.sh
# 已有安装时升级（保留 data/）
sudo ./update.sh
sudo ./install_demo.sh    # 可选：安装或升级 demo（会停掉旧 demo 后替换）
```

离线包由 `build-docker-offline.sh` 生成，内含：部署脚本包、`ai-apm-stack` 镜像、`doris-stack` 镜像。用户侧步骤与 [官网安装页](https://databuff.ai/#install) **Docker → 离线安装** 一致。

## 构建与发布

**每次发版**（应用版本变更）：

```bash
./deploy/images/build-images.sh          # 构建 databuffhub/* 镜像 + 导出离线包
./deploy/docker/build-docker.sh          # 部署脚本包
./deploy/docker/build-docker-offline.sh  # 一体化离线大包（按架构）
```

**仅首次或 Doris 版本升级时**（`deploy/env.sh` 中 `DORIS_*_IMAGE` 变更）：

```bash
./deploy/images/upload-infra-images.sh   # Doris / ZooKeeper 离线包，打一次即可复用
```

离线打包会优先用本机 `dist/infra/images/` 里已有的 `doris-stack-*`；没有则从 `APM_PKG_BASE/infra/images/` 拉取，无需每次重跑 infra 构建。

**本地开发**（离线包 load 或本地 build 后的镜像）：

```bash
cd deploy/docker
./start.sh
./stop.sh
```

固定容器名：

| 服务 | 容器名 |
|------|--------|
| web | `ai-apm-web` |
| ingest | `ai-apm-ingest` |
| Doris FE | `ai-apm-doris-fe` |
| Doris BE | `ai-apm-doris-be` |

## JVM 与资源

默认推荐资源（Docker 不设容器 `mem_limit`；堆内存靠 JVM / FE `-Xmx`；K8s 仍用 `resources.limits`）：

| 组件 | 建议主机内存余量 | JVM / 进程侧 |
|------|------------------|--------------|
| Doris FE | ≥2g | FE `-Xmx1200m`（启动时 patch，见 compose） |
| Doris BE | ≥6g | 官方 `be-4.1.1` 镜像 |
| ingest | ≥5g | `-Xms1g -Xmx4g` |
| web | ≥2g | `-Xms512m -Xmx1536m` |

需要调整堆大小时改 `docker-compose.yml` 中的 `JAVA_TOOL_OPTIONS` / FE `sed` patch，不再依赖 `.env` 注入变量。

Doris 4.x 使用官方 `fe` / `be` 分离镜像（默认 **4.1.1**）。首次启动时 `start.sh` 会等待 Doris FE/BE 就绪后执行 `scripts/init-doris.sh` 导入 `sql/databuff.sql`（轮询 BE 存储容量，不再固定 sleep）。FE 默认 `-Xmx8192m` 过大易拖垮主机；compose 启动前会 patch 为 `-Xmx1200m`。ingest 通过 `DORIS_BE_HTTP_HOST=ai-apm-doris-be` 直连 BE 做 Stream Load。手动重置表结构用 `./reset-table.sh`。

## 端口

| 服务 | 端口 |
|------|------|
| web | 27403 |
| ingest | 4317 / 4318 / **11800** (SkyWalking Agent gRPC) |
