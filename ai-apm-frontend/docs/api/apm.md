# APM API

> 文件: `src/api/apm.ts`

## 概述

`apm.ts` 主要覆盖 APM 场景下的服务概览、服务详情、调用链查询、慢接口分析与手动智能分析能力。它和 `service.ts` 有一定交叉，但更偏向“APM 页面查询接口”，尤其是调用链与服务概览页。

## 接口分组

### 服务概览与详情

| 方法 | HTTP | 路径 | 说明 |
|------|------|------|------|
| `getServiceList` | `POST` | `/service/list` | 服务列表 |
| `getServiceGraph` | `POST` | `/service/graph_stats` | 服务图表数据 |
| `getServiceLatencyGraph` | `POST` | `/service/distribution_stats` | 服务响应时间分布 |
| `getServiceDetail` | `POST` | `/service/serviceInfo` | 服务详情 |
| `getServiceReqTop` | `POST` | `/service/reqTop` | 服务请求贡献 Top |
| `getReqContributorService` | `POST` | `/service/reqContributorService` | 资源来源服务 |

### 调用链

| 方法 | HTTP | 路径 | 说明 |
|------|------|------|------|
| `getSpanParams` | `POST` | `/trace/query_params` | 调用链筛选参数 |
| `getSpanList` | `POST` | `/trace/list` | 调用链列表 |
| `getEventSpanList` | `POST` | `/trace/search/list` | 事件调用链列表 |
| `getSpanRequestGraph` | `POST` | `/trace/cnt_graph_stats` | 请求数量图 |
| `getSpanErrorGraph` | `POST` | `/trace/error_cnt_graph_stats` | 错误数量图 |
| `getSpanResponseTimeGraph` | `POST` | `/trace/graph_stats` | 响应时间图 |
| `getTraceSpans` | `POST` | `/trace/spans` | 调用链拓扑/节点明细 |
| `getSpanFlow` | `POST` | `/trace/serviceFlow` | 调用链服务流 |

### 慢接口与 SQL

| 方法 | HTTP | 路径 | 说明 |
|------|------|------|------|
| `getEndpointList` | `POST` | `/service/endpoints` | 服务端点列表 |
| `getSlowSqlTop` | `POST` | `/service/slowSqlTopList` | 慢 SQL Top 列表 |
| `updateRequestAlias` | `POST` | `/slowInterface/updateResourceAlias` | 修改接口别名 |
| `slowApiRelation` | `POST` | `/slowInterface/getResourceRelations` | 查询接口上下游关系 |
| `traceTrend` | `POST` | `/trace/allCnt` | 接口总请求趋势 |
| `traceSlowTrend` | `POST` | `/trace/slowCnt` | 慢接口趋势 |
| `traceErrorTrend` | `POST` | `/trace/errorCnt` | 错误接口趋势 |

### 智能分析

| 方法 | HTTP | 路径 | 说明 |
|------|------|------|------|
| `mannualAi` | `POST` | `/root/syncAnalyse` | 手动触发智能分析 |

## 特殊处理

### `getServiceGraph`

该方法支持显式传入 `cancelToken`，适合图表切换或搜索联动时取消旧请求。

## 与其他 API 文件的关系

- `service.ts` 更偏服务详情、资源分析、数据库/MQ/线程剖析
- `apm.ts` 更偏服务概览、调用链、慢接口与 APM 页查询
