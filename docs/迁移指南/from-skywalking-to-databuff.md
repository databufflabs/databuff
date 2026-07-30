# 从 SkyWalking 迁移到 DataBuff

保留 **SkyWalking Agent**，不换探针、不改业务代码。能力差异见 [DataBuff vs SkyWalking](../业界对比/vs-skywalking.md)。

有两种迁法，按风险承受能力选：

| | **方案 A · 直接替换** | **方案 B · 双写（databuff-proxy）** |
|--|----------------------|-------------------------------------|
| 做法 | Agent 改指 DataBuff Ingest | Agent 先指 proxy，同时写 OAP + DataBuff |
| 额外组件 | 无 | 需部署 [databuff-proxy](https://github.com/databufflabs/databuff-proxy) |
| 适合 | 可接受一次性切换、环境可控 | 生产要并行对比、平滑切流 |
| 回滚 | Agent 改回 OAP | proxy 关掉 DataBuff 一路，或 Agent 改回 OAP |

协议细节见 [SkyWalking 接入](../使用手册/SkyWalking接入.md)。

---

## 方案 A · 直接替换

```
迁移前：  Agent  ──gRPC:11800──▶  SkyWalking OAP
迁移后：  Agent  ──gRPC:11800──▶  DataBuff Ingest
```

**核心：修改 `agent.backend_service`，主机改为 DataBuff Ingest，端口保持 11800。**

推荐 **金丝雀 → 验收 → 分批扩**；服务少可一次性改全量地址并重启。

### 前置条件

- [DataBuff 已部署](../快速入门/docker安装部署.md)
- Ingest SkyWalking gRPC 可达（Docker `11800`，K8s 常见 `31180`）
- 记录当前 OAP 地址，便于回滚

### 操作步骤

#### 1. 确认 Ingest 地址

| 部署 | Agent 指向 |
|------|------------|
| Docker | `<ingest-host>:11800` |
| Kubernetes | `<node-ip>:31180` 或 Ingress `11800` |

#### 2. 修改上报地址

`agent.config`：

```properties
agent.backend_service=<databuff-ingest-host>:11800
agent.service_name=my-service
```

或 JVM 参数：

```bash
-Dskywalking.collector.backend_service=<databuff-ingest-host>:11800
```

K8s / 容器：将原 `oap:11800` 改为 DataBuff Ingest 地址，滚动重启 Pod。

#### 3. 分批切流

1. 先改 1–2 个非核心服务并重启  
2. 在 DataBuff Web 完成验收（见文末）  
3. 按业务域逐步扩批  
4. 稳定后可下线 OAP / UI  

### 本方案回滚

`backend_service` 改回 OAP 地址并重启应用。变更单保留改前 / 改后两个值。

---

## 方案 B · 双写（databuff-proxy）

```
Agent  ──gRPC:11800──▶  databuff-proxy ──▶ SkyWalking OAP
                                   └──▶ DataBuff Ingest
```

Agent 只改一次，指向 proxy；两路同时收数。确认 DataBuff 侧数据与体验 OK 后，再停 OAP 那一路（或让 Agent 直连 DataBuff）。

稳定性实测（资源不到 1 核、异常场景表现正常）见 [databuff-proxy 双写稳定性验证](./proxy-dual-write-stability.md)。

### 前置条件

- DataBuff 与 SkyWalking OAP 都在线，且 proxy 能访问两边的 `:11800`
- 准备一台放 proxy 的机器（或容器）

### 操作步骤

#### 1. 安装并配置 proxy

从 [databuff-proxy Releases](https://github.com/databufflabs/databuff-proxy/releases) 下载解压，编辑 `config.yaml`：

```yaml
backends:
  - name: skywalking
    addr: "<oap-host>:11800"
    enabled: true
  - name: databuff
    addr: "<databuff-ingest-host>:11800"
    enabled: true
```

```bash
./start.sh
```

管理页默认见仓库 README（`admin.addr`）。更细配置见 [CONFIG.md](https://github.com/databufflabs/databuff-proxy/blob/main/CONFIG.md)。

#### 2. Agent 改指 proxy

```properties
agent.backend_service=<proxy-host>:11800
```

重启 Agent / 滚动 Pod。此后流量经 proxy 双写。

#### 3. 并行验证

| 检查 | 预期 |
|------|------|
| SkyWalking | 原有服务、Trace 仍正常 |
| DataBuff | 出现相同服务；新 Trace 可查 |
| proxy | 两路成功转发、无持续丢弃；管理页熔断状态正常 |

#### 4. 切走 SkyWalking

确认 DataBuff 达标后，任选其一：

- 在 proxy 管理页 **关掉 skywalking 写入**（立刻只写 DataBuff），或  
- 把 `agent.backend_service` 改成 DataBuff Ingest，并下线 proxy / OAP  

### 本方案回滚

- 双写阶段：proxy 关掉 `databuff` 写入，或 Agent 改回 OAP  
- 已切走后：Agent 改回 OAP（或重新打开 proxy 的 skywalking 一路）

---

## 验收（两种方案通用）

| 检查项 | 预期 |
|--------|------|
| 服务 | 应用性能页出现 `service_name` |
| Trace | 新请求可查；`data.source` = `SkyWalking` |
| JVM / Log | Agent 有上报则有数据 |
| 告警 | 在 DataBuff **重新配置**（OAP 规则不自动迁移） |

**不自动迁移**：OAP 历史 Trace / 指标、告警 YAML；需在 DataBuff 侧按需重建。

## 延伸阅读

- [databuff-proxy 双写稳定性验证](./proxy-dual-write-stability.md)
- [databuff-proxy 仓库](https://github.com/databufflabs/databuff-proxy)
- [DataBuff vs SkyWalking](../业界对比/vs-skywalking.md)
- [SkyWalking 接入](../使用手册/SkyWalking接入.md)
- [Docker 安装部署](../快速入门/docker安装部署.md)
