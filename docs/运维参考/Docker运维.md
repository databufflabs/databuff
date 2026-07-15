<p align="center">
  <a href="Docker运维.md">中文</a>
  &nbsp;|&nbsp;
  <a href="Docker运维_en.md">English</a>
</p>

# Docker 运维参考

本文说明一键安装后的目录结构、启停、端口、健康检查与常见排障。快速安装步骤见 [Docker 安装部署](../快速入门/docker安装部署.md)。

## 安装目录

默认路径为 `/opt/databuff-ai-apm`，可通过环境变量 `APM_INSTALL_DIR` 覆盖（安装脚本与 `install.sh` 均支持）。

```
/opt/databuff-ai-apm/
├── docker-compose.yml    # 主栈：Doris FE/BE + ingest + web
├── start.sh / stop.sh    # 推荐启停方式
├── env.sh                # 镜像版本（与 deploy/env.sh 一致）
├── data/                 # Doris 持久化（fe-meta、be-storage）
├── scripts/              # init-doris、pull-images、runtime 等
└── sql/                  # databuff.sql（首次启动导入）
```

## 启停与重启

**推荐**在安装目录执行：

```bash
cd /opt/databuff-ai-apm
./start.sh    # 首次自动初始化 Doris 并导入表结构
./stop.sh     # 停止全部容器
```

重启单服务示例：

```bash
docker compose restart ai-apm-web
docker compose restart ai-apm-ingest
```

`start.sh` 仅启动服务：检查 `vm.max_map_count`，等待 Doris 就绪后启动 ingest 与 web，并探测健康检查端点。镜像加载由 `install.sh` / `update.sh` 或手动执行 `scripts/pull-images.sh` 完成。

## 服务与端口

| 容器名 | 组件 | 宿主机端口 | 说明 |
|--------|------|------------|------|
| `ai-apm-web` | Web 平台 | **27403** | UI 与 API |
| `ai-apm-ingest` | Ingest | **4317** / **4318** / **11800** | OTLP gRPC / HTTP；SkyWalking Agent gRPC |
| `ai-apm-doris-fe` | Doris FE | 8030 / 9030 | HTTP / MySQL 协议 |
| `ai-apm-doris-be` | Doris BE | 8040 | BE HTTP |

## 健康检查与默认账号

| 服务 | 探活 URL |
|------|----------|
| Ingest | `http://127.0.0.1:4318/health` |
| Web | `http://127.0.0.1:27403/health` |

安装完成或 `start.sh` 结束后，终端会输出 Web 地址与默认账号：

- 用户名：`admin`
- 密码：`Databuff@123`

## 查看日志

```bash
cd /opt/databuff-ai-apm
docker compose logs -f ai-apm-ingest ai-apm-web
docker compose logs ai-apm-doris-fe ai-apm-doris-be
```

服务未就绪时，`start.sh` 超时后会提示检查 ingest / web 日志。

## 常见故障

| 现象 | 处理 |
|------|------|
| Doris 启动失败 / BE OOM | 确认宿主机内存；FE 已在 compose 中将 `-Xmx` patch 为 1200m |
| `vm.max_map_count` 过低 | `start.sh` 会尝试调至 2000000；Linux 可写入 `sysctl.conf` 持久化 |
| 端口被占用 | 修改 `docker-compose.yml` 中 ports 映射或释放 27403 / 4317 / 4318 / 11800 |
| 服务列表为空 | 确认 Agent/SDK 指向 `4317`/`4318`；见 [OTLP 接入](../opentelemetry-otlp-ingestion.md) |
| 规则创建后无告警 | 确认服务已有指标；评估每分钟执行一次；检查规则监控对象是否匹配 |
| 需重置表结构 | 安装目录执行 `./reset-table.sh`（会清空 Doris 业务表，慎用） |

数据持久化在 `data/`。停止服务不会删除数据；彻底清理见 [升级与卸载](升级与卸载.md)。

## 发布验收 / Release gate

开源发版前请在仓库根目录跑通下列三项（**互不替代**；任一 PASS 不能证明另外两门）。矩阵与勾选项见 Epic 交付物 `self-ops-release-test-matrix.html`（G-OPS*）。

| 门禁 | 脚本 | 覆盖什么 | 主证据 / 前提 |
|------|------|----------|----------------|
| **A** | `deploy/test/doris-failover-e2e.sh` | 安装期 Doris 起不来 → Web 排障 / bootstrap → 恢复 | 安装失败路径 e2e PASS |
| **B** | `deploy/test/doris-runtime-failover-e2e.sh` | 稳态运行时 Doris 宕机 → **运维专家（ops）对话恢复** | **主证据 = ops 会话**（chat/submit + 消息/tool）；`/health` 仅辅助。需已配置 LLM API Key。**禁止**脚本 `docker start` FE/BE 冒充专家恢复。`SKIP_OPS_EXPERT=1` **不是**有效发版门禁 |
| **C** | `deploy/test/run-tests.sh` | API / 接口回归 | 报告 PASS（通常需 demo 遥测） |

发版 checklist（建议原样勾选）：

```text
□ A  ./deploy/test/doris-failover-e2e.sh
□ B  ./deploy/test/doris-runtime-failover-e2e.sh
     （未设 SKIP_OPS_EXPERT；会话证据默认 /tmp/doris-runtime-ops-evidence.json；勾满 G-OPS0～G-OPS3）
□ C  ./deploy/test/run-tests.sh
```

常用命令：

```bash
# A — 安装失败排障（详见脚本头注释；离线常需 BUNDLE_ROOT / APM_INSTALL_DIR）
./deploy/test/doris-failover-e2e.sh

# B — 运行时宕机 → 运维专家恢复（栈已起且 Web 已配大模型 Key）
./deploy/test/doris-runtime-failover-e2e.sh

# C — API 回归
./deploy/test/run-tests.sh
```

升级后的冒烟仍见 `deploy/docker/UPGRADE.md` 的 `verify-upgrade.sh`；**不能**替代上述 A/B/C 发版门禁。

## 相关文档

- [升级与卸载](升级与卸载.md)
- [离线安装](离线安装.md)
- [遥测数据流](../架构设计/遥测数据流.md)
