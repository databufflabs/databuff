# DataBuff vs SkyWalking

> 对比文档 · [切换到英文](./vs-skywalking_en.md)

## 能力边界

| 维度 | SkyWalking | DataBuff |
|------|------------|----------|
| 定位 | APM 系统（Trace + 拓扑） | AI Native OTel APM（Trace + Metrics + Log + AI） |
| AI 能力 | ❌ | ✅ AI 驱动排障、拓扑关联、自然语言查询 |
| 内置存储 | H2 / Elasticsearch | Doris（列式存储） |

## 客观差异

### Agent 不换

DataBuff 支持 SkyWalking 原生 gRPC 协议接入，已部署 SkyWalking Agent 的用户无需更换 Agent，只需将上报地址指向 DataBuff 即可。

### AI 智能化

DataBuff 内置 AI 排障引擎，SkyWalking 不具备此能力。

### 拓扑下钻

SkyWalking 提供服务拓扑，DataBuff 在此基础上提供 AI 增强的下钻路径。

## 不适用场景

（详见迁移指南，即将发布）

## 延伸阅读

- [SkyWalking 接入](/docs/zh/manual/skywalking-ingestion)
- [迁移指南：从 SkyWalking 到 DataBuff](/docs/zh/migration/from-skywalking)（即将发布）
