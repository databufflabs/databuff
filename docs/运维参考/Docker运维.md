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

## 相关文档

- [升级与卸载](升级与卸载.md)
- [离线安装](离线安装.md)
- [遥测数据流](../架构设计/遥测数据流.md)
