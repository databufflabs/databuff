<p align="center">
  <a href="K8s运维.md">中文</a>
  &nbsp;|&nbsp;
  <a href="K8s运维_en.md">English</a>
</p>

# Kubernetes 运维参考

K8s 部署包内的安装、启停、访问方式与镜像管理。快速安装见 [K8s 安装部署](../快速入门/k8s安装部署.md)。

## 部署包结构

一键脚本解压后典型目录：

```
databuff-ai-apm-k8s-<version>/
├── install.sh          # 卸载后全新安装
├── start.sh            # 按顺序启动（不卸载资源）
├── stop.sh             # 缩容至 0，保留 Service / ConfigMap
├── download-images.sh
├── download-apm-images.sh
├── manifests/          # ZooKeeper、Doris、ingest、web
├── sql/
└── scripts/lib.sh
```

命名空间默认为 **`databuff`**。`install.sh` / `start.sh` 使用 `kubectl` 直装（不依赖 Helm），顺序为：ZooKeeper + Doris → 等待就绪并执行 `databuff.sql`（表已存在则跳过）→ ingest + web。

## 启停

```bash
cd databuff-ai-apm-k8s-<version>
./install.sh    # 全新安装（会先清理同名资源）
./start.sh      # 仅启动，不删除 ConfigMap / Service
./stop.sh       # ingest / web / doris / zookeeper 缩容至 0
```

## 访问方式

| 服务 | 集群内端口 | NodePort（节点访问） |
|------|------------|----------------------|
| Web | 27403 | **32703** |
| Ingest (OTLP gRPC) | 4317 | **30417** |
| Ingest (OTLP HTTP) | 4318 | **30418** |

示例：`http://<node-ip>:32703` 打开 Web UI；集群外 OTLP 上报 `http://<node-ip>:30418`（gRPC 用 `30417`）。集群内 Service `ai-apm-ingest`：`http://ai-apm-ingest:4318`。

默认登录账号（与 Docker 安装一致；未改密时）：

- 用户名：`admin`
- 密码：`Databuff@123`

`start.sh` / `install.sh` 结束时会从 Web Pod 环境变量读取并打印**当前生效**账号。修改登录账号、Doris 连接账号或 Ingest 任务参数见 [参数配置](参数配置.md)。

## 离线镜像

各节点拉取镜像困难时（自动识别 amd64/arm64）：

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-download-images.sh | bash
# 或部署包内
./download-images.sh
```

k3s / containerd 节点：

```bash
export IMAGE_LOAD_CMD=ctr
curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-download-images.sh | bash
```

仅升级 **ingest / web** 镜像（不更新 Doris / ZooKeeper）：

```bash
curl -fsSL https://databuff.ai/databuff/ai-apm-k8s-download-apm-images.sh | bash
# 或
./download-apm-images.sh
```

## Ingest 扩缩容（可选）

默认单副本以 standalone 运行。多副本且集群协调开启时，ingest 通过 ZooKeeper 做成员发现：

```bash
kubectl scale deploy/ai-apm-ingest -n databuff --replicas=4
kubectl rollout status deploy/ai-apm-ingest -n databuff
```

## 健康检查与日志

| 服务 | 探活 |
|------|------|
| Web | `kubectl exec -n databuff deploy/ai-apm-web -- wget -qO- http://127.0.0.1:27403/health` |
| Ingest | `kubectl exec -n databuff deploy/ai-apm-ingest -- wget -qO- http://127.0.0.1:4318/health` |

```bash
kubectl logs -n databuff deploy/ai-apm-ingest -f
kubectl logs -n databuff deploy/ai-apm-web -f
```

## 常见故障

| 现象 | 处理 |
|------|------|
| 服务列表为空 | 确认 Agent 指向 `http://ai-apm-ingest:4318`（集群外用 NodePort）；见 [OTLP 接入](../opentelemetry-otlp-ingestion.md) |
| 规则创建后无告警 | 确认服务已有指标；评估每分钟执行；检查规则监控对象是否匹配 |
| Pod 启动失败 | `kubectl describe pod -n databuff <pod>` 查看 Events；检查节点内存 |

## 注意事项

- Doris 在演示 manifests 中使用 `emptyDir`，**Pod 重建后需重新执行 init SQL**。
- 默认资源限制与 Docker 栈一致（ingest 5Gi、web 2Gi 等），见 `manifests/doris.yaml`、`ingest.yaml`、`web.yaml`。

## 相关文档

- [升级与卸载](升级与卸载.md)
- [Docker 运维参考](Docker运维.md)
