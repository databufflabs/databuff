# Deploy

| 目录 | 用途 |
|------|------|
| [`common/`](common/) | Docker / K8s 共享部署素材：Doris SQL、运行时 skills 等 |
| [`images/`](images/) | ingest / web / demo 镜像构建（`build-images.sh`） |
| [`docker/`](docker/) | Docker Compose 主栈 + demo 造数包 + 一键安装脚本（`build-docker.sh`） |
| [`k8s/`](k8s/) | Helm Chart + demo K8s 清单 + 一键安装脚本（`build-k8s.sh`） |
| [`test/`](test/) | 应用性能接口集成测试 |

**镜像与版本**在 [`env.sh`](env.sh) 统一配置（发版时修改 `APM_VERSION`）；应用镜像统一 `databuffhub/*` 短名，通过离线包 `docker load` 使用。K8s 集群若需从镜像仓库拉取，由用户自行将 `databuffhub/*` 推到其仓库。

Demo 源码位于仓库根目录 [`ai-apm-demo/`](../ai-apm-demo/)。

## 构建脚本职责

| 脚本 | 职责 |
|------|------|
| [`images/build-images.sh`](images/build-images.sh) | 编译 jar → 构建 **databuffhub/** 镜像 → 导出 amd/arm 离线包并上传 |
| [`images/upload-infra-images.sh`](images/upload-infra-images.sh) | 拉取 **Doris FE/BE、ZooKeeper** 多架构镜像 → 导出 tar → 上传到镜像包目录 |
| [`docker/build-docker.sh`](docker/build-docker.sh) | 打 Docker 部署包 + install 脚本 → **SCP 上传到 databuff-site** |
| [`docker/build-docker-offline.sh`](docker/build-docker-offline.sh) | 打 Docker **一体化离线大包**（部署包 + 镜像，按架构） |
| [`k8s/build-k8s.sh`](k8s/build-k8s.sh) | 打 Helm / K8s 部署包 + install 脚本 → **SCP 上传到 databuff-site** |
| [`k8s/download-images.sh`](k8s/download-images.sh) | 按本机架构从镜像包目录下载 K8s 所需镜像并 `docker load` / `ctr import` |
| [`k8s/download-apm-images.sh`](k8s/download-apm-images.sh) | 仅强制更新 ingest / web 两个镜像包 |

### 发布地址

| 内容 | 地址 | 脚本 |
|------|------|------|
| 离线镜像包（`.tar.gz`，databuffhub/* + infra） | `https://databuff.ai/databuff/<ver>/images/` 与 `.../infra/images/` | `build-images.sh` / `upload-infra-images.sh` |
| 部署包（tar.gz + install.sh） | `https://databuff.ai/databuff/` | `build-docker.sh` / `build-k8s.sh` |

### 发布流程

```bash
# 0. 基础设施镜像（Doris + ZooKeeper）— 通常只做一次，或 Doris 版本升级时重做
./deploy/images/upload-infra-images.sh

# 1. 应用镜像（含 amd/arm 离线包上传）
./deploy/images/build-images.sh

# 2. Docker 部署包 → databuff-site
./deploy/docker/build-docker.sh

# 3. Docker 一体化离线大包（按架构，复用已有 infra 包）
./deploy/docker/build-docker-offline.sh

# 4. K8s 部署包 → databuff-site
./deploy/k8s/build-k8s.sh
```

跳过上传：`SKIP_PKG_UPLOAD=1` 或 `SKIP_IMAGE_PKG_UPLOAD=1`

上传依赖 `sshpass`（`brew install sshpass`），目标目录见 `env.sh` 中 `APM_PKG_REMOTE_DIR`（默认 `/opt/databuff-site/databuff`，由 databuff-site nginx 映射为 `APM_PKG_BASE`）。

### 目标机安装

Docker / K8s 安装脚本会**自动识别 amd64/arm64**，从镜像包目录下载并 `docker load` / `ctr import`。

**版本**：默认从 `${APM_PKG_BASE}/VERSION` 读取最新版；也可指定：

```bash
# 安装最新版（默认）
curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash

# 安装指定版本
curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash -s -- --version 0.1.1
APM_VERSION=0.1.1 curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash
```

```bash
# Docker 主栈
curl -fsSL https://databuff.ai/databuff/ai-apm-install.sh | bash

# K8s（各节点先导入镜像，再安装）
./download-images.sh          # 部署包内
curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-download-images.sh | bash
curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-install.sh | bash

# K8s 仅升级 ingest / web 镜像（各节点强制重新导入）
curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-download-apm-images.sh | bash

# Demo 造数
curl -fsSL https://databuff.ai/databuff/ai-apm-demo-install.sh | bash
curl -fsSL https://databuff.ai/databuff/ai-apm-demo-k8s-install.sh | bash
```

详见 [`k8s/README.md`](k8s/README.md)、[`docker/README.md`](docker/README.md)。

## 集成测试

```bash
# 跑接口 + AI chat 集成测试（默认）
./deploy/test/run-tests.sh

# 维护者：按当前环境刷新 expected/*.json 基线
./deploy/test/run-tests.sh --snapshot

# 强制跳过 AI chat（即使已配置 API Key）
TEST_SKIP_AI_CHAT=1 ./deploy/test/run-tests.sh
```

未配置可用 API Key 时，AI chat 用例会自动跳过，不影响 APM 接口测试结果。

初始化 SQL：`deploy/common/sql/databuff.sql`。
