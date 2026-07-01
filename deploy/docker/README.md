# Docker 部署

ingest、web、Doris 默认从离线镜像包目录按本机架构下载并 `docker load`；运行时仅挂载 `data/`（Doris 持久化）。应用镜像统一为 `databuffhub/*` 短名。

## 目录结构

```
docker/
├── docker-compose.yml    # 主栈（Doris + ingest + web）
├── env.sh                # 运行时镜像配置（与 deploy/env.sh 一致）
├── start.sh / stop.sh
├── demo/                 # demo 造数 Compose 包
├── ai-apm-install.sh
├── ai-apm-offline-install.sh   # 离线包内 install.sh 模板
├── ai-apm-demo-install.sh
├── build-docker.sh       # 打部署包并 SCP 上传到 databuff-site
├── build-docker-offline.sh # 打一体化离线大包（部署包 + 镜像）
├── data/                 # Doris 持久化
└── scripts/
```

镜像构建上下文见 [`../images/`](../images/)。

## 目标机安装

`start.sh` / 一键安装脚本会按本机架构（amd64/arm64）从 `APM_IMAGES_PKG_BASE` 下载镜像包并 `docker load`：

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash
```

## 离线安装（一体化大包）

下载对应架构的离线包，解压后执行 `install.sh`，**全程无需联网**：

```bash
tar -xzf databuff-ai-apm-offline-0.1.1-amd64.tar.gz
cd databuff-ai-apm-offline-0.1.1-amd64
sudo ./install.sh
```

离线包由 `build-docker-offline.sh` 生成，内含：部署脚本包、`ai-apm-stack` 镜像、`doris-stack` 镜像。

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
cd deploy/docker && ./start.sh
```

也可以直接使用 Compose：

```bash
cd deploy/docker
docker-compose up -d
docker-compose down
```

固定容器名：

| 服务 | 容器名 |
|------|--------|
| web | `ai-apm-web` |
| ingest | `ai-apm-ingest` |
| Doris FE | `ai-apm-doris-fe` |
| Doris BE | `ai-apm-doris-be` |

## JVM 与资源

默认资源配置（Docker `cpus` / `mem_limit`，K8s `resources.limits` 一致）：

| 组件 | CPU | 内存 | JVM (`Xmx` 建议小于内存 limit) |
|------|-----|------|--------------------------------|
| Doris FE | 1 | 2g | FE `-Xmx1200m`（启动时 patch，见 compose） |
| Doris BE | 2 | 6g | 官方 `be-4.1.1` 镜像 |
| ingest | 2 | 5g | `-Xms1g -Xmx4g` |
| web | 1 | 2g | `-Xms512m -Xmx1536m` |

需要调整资源时直接改 `docker-compose.yml`，不再依赖 `.env` 注入变量。

Doris 4.x 使用官方 `fe` / `be` 分离镜像（默认 **4.1.1**）。首次启动时 `start.sh` 会等待 Doris FE/BE 就绪后执行 `scripts/init-doris.sh` 导入 `sql/databuff.sql`（轮询 BE 存储容量，不再固定 sleep）。FE 默认 `-Xmx8192m` 与 2g 容器 limit 冲突会导致 OOM；compose 启动前会 patch 为 `-Xmx1200m`。ingest 通过 `DORIS_BE_HTTP_HOST=ai-apm-doris-be` 直连 BE 做 Stream Load。手动重置表结构用 `./reset-table.sh`。

## 端口

| 服务 | 端口 |
|------|------|
| web | 27403 |
| ingest | 4317 / 4318 |
