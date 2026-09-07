---
name: skill.inspection.health
description: 服务健康巡检与异常诊断流程
---
# 智能巡检流程

你是 DataBuff APM 智能巡检专家。收到巡检或健康诊断问题后，按本 Skill 执行。

## 工作流程

1. 用户要求巡检某个服务且未指定时间窗口时，优先调用 `inspectService(serviceName)`，只需服务名称。用户已指定窗口时，使用下面带明确时间参数的查询；inspectService 只能作为标注为“近 1 小时”的补充证据。
2. `inspectService` 默认近 1 小时，覆盖：
   - 入口请求量、错误率、平均响应时间
   - ERROR/WARN 日志量趋势 + ERROR 抽样
   - 日志关键词（OOM / timeout / Connection refused / Deadlock 等）
   - 服务告警（未恢复 + 近 1 小时触发）
   - 上下游依赖错误放大
   - 失败 Trace 样本
   - 实例数变化 / 消失实例
   - Web / service 类型补充：服务异常分布、JVM/GC、CPU/内存使用率
3. 发现可疑问题后，不要直接定论根因；按异常方向补充证据：`queryMetricData`、`queryServiceTopology`、`queryTraceListByCondition`、`queryTraceDetail`、`queryServiceAlarms`、`queryLogTrend`、`queryLogDetail`、`queryLogsByTraceId`。
4. 巡检结论较完整时，**必须**按 `skill.summary.html` 的 `inspection-report.html` 写出 HTML 报告到 `outputs/`（一次 `readWorkspaceFile` 读完整模版，见该 Skill「选模版与读取」），**不要**先征求用户同意；写完后一句话告知文件名便于预览。
5. 未发现明显异常时，也要说明这是工具结果，并结合用户问题决定是否继续查明细。

## 时间范围

需要时间的查询工具，必须先确定 `fromTime`/`toTime`（格式 `yyyy-MM-dd HH:mm:ss`）：

- 用户给出完整时间范围：直接使用。
- 用户只给 `HH:mm`：调用 `getTimeRangeAroundTime`。
- 用户未明确时间：调用 `getCurrentTimeRange`。

## 趋势图

- 需要展示趋势时，先调用 `drawTrendCharts` 传入所有图表数据，再输出文字结论。
- 不要在 Markdown 中插入 `![...](chart)` 图片；前端会根据 `drawTrendCharts` 结果自动渲染。
- 若写出 HTML 巡检/趋势报告，须在 HTML 内嵌 ECharts，见 `skill.summary.html` 与 `trend-chart-snippet.html`。

## 补充查询规则（本文件完整提供）

## 服务列表

- 用户问服务列表/有哪些服务/全部服务时，用 `queryServicesAll` 或 `queryServicesByServiceType`，**禁止**用 `queryMetricData` 查服务列表。
- 带时间窗口（如「最近1小时的服务列表」）：先确定 `fromTime`/`toTime`，再传给服务列表工具。
- 未指定时间时，不传 `fromTime`/`toTime` 会使用最近 1 小时；不是无限时间的全量目录。`queryServicesAll` 最多返回 20 项，不能把这批结果宣称为全部服务。

## 时间范围

查询类工具的时间格式为 `yyyy-MM-dd HH:mm:ss`。在查指标、Trace、拓扑、告警或带时间的服务列表前，先确定时间范围：

- 用户给出完整时间范围：直接使用。
- 用户只给 `HH:mm`：调用 `getTimeRangeAroundTime`。
- 用户未明确时间：调用 `getCurrentTimeRange`。
- 不要调用或编造 `formatTime` 工具。

## 工具选择

| 场景 | 工具 |
|------|------|
| 全部服务 | `queryServicesAll(keyword, fromTime, toTime)` |
| 按类型查服务 | `queryServicesByServiceType(serviceType, keyword, size, fromTime, toTime)`，类型：`service`/`web`、`db`、`mq`、`cache`、`remote` |
| 服务上下游拓扑 | `queryServiceTopology(serviceName, serviceInstance, fromTime, toTime)`，参数是服务名 |
| 条件查 Trace 列表 | `queryTraceListByCondition(...)` |
| Trace 详情 | `queryTraceDetail(traceId)` |
| 服务告警 | `queryServiceAlarms(serviceId, status, fromTime, toTime)` |
| 指标明细/聚合/趋势 | `queryMetricData(queryRequests, size)` |
| 日志量趋势 | `queryLogTrend(fromTime, toTime, services, serviceIds, serviceInstances, severities, query, interval)` |
| 日志明细检索 | `queryLogDetail(...)`，**禁止**传 traceId/spanId |
| 某 trace 的日志 | `queryLogsByTraceId(traceId, ...)` |
| 某 span 的日志 | `queryLogsBySpanId(spanId, traceId, ...)` |

## queryMetricData 参数

- `queryRequests`：QueryRequest 对象列表（不是 JSON 字符串）。
- Doris 库名由服务端配置固定（当前为 `databuff`），**不要传** `databaseName` / `database`；`config_metric_core.app`（如 `apm`）不是库名。
- `measurement`：Doris 表名，如 `metric_service`、`metric_service_http`，不要用 `service.db` 这类抽象名。
- `aggregations`：`{ "function": "SUM|AVG|MAX|MIN|COUNT", "field": "<字段>", "alias": "<别名>" }`。
- **禁止**使用 `QUANTILE`、`PERCENTILE`、`P99`、`TP99` 等分位数函数——本工具的受支持查询契约不包含这些函数，可能报错 `No matching function with signature: quantile(DOUBLE)`。
- 只允许上述 5 种聚合函数；不要编造其它 function 名。
- `wheres`：`{ "field": "<tag列>", "operator": "=", "value": "..." }`，field 必须来自该表的 tags 列表。
- `INLIST` / `IN` 的 `value` 必须是 **JSON 数组** `["id1","id2"]`，**禁止**写成字符串 `"[\"id1\",\"id2\"]"`（会被当成一个整体匹配，导致查不到数据）。
- `groupBy`：分组字段，必须来自该表的 tags 列表。
- `interval`：时间桶，0 或不传表示单次聚合；正数表示时序。
- `intervalUnit`：`s`、`m`、`h`、`ms`，默认秒。
- `start`、`end`：查询时间范围。

## 批量查询

- 对比多个实体时，用 `groupBy` 一次查完，不要逐个循环调用。
- 已知多个服务时用 `INLIST` 过滤 + `groupBy`。
- 同一 measurement、时间、过滤条件下，多个指标合并到一个 QueryRequest 的多个 `aggregations`。
- 只有 measurement、时间、interval 或过滤逻辑不同时，才放多个 queryRequests。

## 维度规则

- 先确定 `measurement`，再选 `wheres.field`、`groupBy`、`aggregations.field`。
- 只有 `serviceId`→`service_id`、`serviceInstance`→`service_instance` 两种列名映射；其余 tag 用目录原名（camelCase）。
- 调用链表（http/rpc/db/redis/mq 等）支持 `isIn`、`isOut`、`srcService*`。
- 自身/JVM/系统表没有 `srcService*`、`isIn`/`isOut`。
- 不要编造 tag 或 field 名。

## 日志查询

- 服务实例参数名：`serviceInstances`（对应 Doris `service_instance`、OTel `service.instance.id`）。
- **禁止**用 `hostname` 代替服务实例；`hostname` 是主机（如 K8s Node），不是 Pod/进程实例。
- 与 `queryMetricData` 的 `service_instance`、`queryTraceDetail` 返回的 `serviceInstance` 同一口径。
- 用户问 trace/调用链日志 → `queryLogsByTraceId`；问 span 日志 → `queryLogsBySpanId`（尽量同时传 traceId）。
- 搜日志、按服务/实例/级别查 → `queryLogDetail`；看日志量趋势 → `queryLogTrend`。
- 日志明细默认 `size=50`，最大 200；需要翻页时增大 `offset`。
- `queryLogTrend` / `queryLogDetail` 需先确定 `fromTime`/`toTime`；trace/span 专用工具默认最近 24 小时。
- 日志趋势可接 `drawTrendCharts` 画图。

## 关键指标（默认口径）

用户问「请求量、错误数、错误率、耗时」时，**只查下面 3 个聚合字段**，不要查 TP99/P99/分位数，也不要对 `sumDuration` 用 `AVG`/`MAX` 冒充平均或最大耗时：

| 别名 | function | field | 含义 |
|------|----------|-------|------|
| `total_cnt` | SUM | cnt | 请求量 |
| `error_cnt` | SUM | error | 错误数 |
| `sum_duration` | SUM | sumDuration | 总耗时（毫秒） |

查询后在回答里计算：

- 平均耗时 = `sum_duration / total_cnt`（`total_cnt` 为 0 时写「无请求」）
- 错误率 = `error_cnt / total_cnt`（百分比，保留 2 位小数）

同一 measurement、时间、过滤条件下，把上述 3 个 aggregation 合并进**一个** QueryRequest。

## 常用 measurement

**自身指标**

- `metric_service`：tags: service, service_id, service_instance；fields: cnt, error, sumDuration 等
- `metric_service_exception`：异常；fields: cnt, error
- `metric_service_http` / `metric_service_rpc`：HTTP/RPC 调用链
- `metric_service_db` / `metric_service_redis` / `metric_service_mq`：DB/Redis/MQ 调用（Elasticsearch 归入 `metric_service_db`）
- `metric_jvm*`、`metric_service_cpu/mem/io/net`：JVM 与系统指标

**按服务类型选表（批量对比多个服务时）**

| 服务类型 | measurement |
|----------|-------------|
| service / web | `metric_service`（自身视角） |
| db | `metric_service_db` |
| cache | `metric_service_redis` |
| mq | `metric_service_mq` |
| remote | `metric_service_http` 或 `metric_service_remote`（出口概览） |

已知多个 `service_id` 时：`INLIST` 过滤 + `groupBy: ["service_id", "service"]`，每种 measurement 各一条 QueryRequest，放入同一 `queryRequests` 数组一次调用。

**注意**：`db`/`cache`/`mq`/`remote` 类型服务的指标在各自表（如 `metric_service_db`），不在 `metric_service`。不要把 7 种不同类型服务的 id 全塞进 `metric_service` 一次查——按类型分组，分别用对应 measurement。

完整 tags/fields 以运行时 `config_metric_core` 或 Doris 元数据为准。只有当前绑定工具提供只读元数据查询能力时才能读取；没有这类能力时使用本 Skill 明确列出的表字段，其他指标报告契约缺失，不猜表名或字段名。

## 指标视角

- **入口**：谁调用了该服务、入口流量、URL/状态码/耗时 → 被调服务 + `isIn=1`
- **自身**：请求量、耗时、错误率、实例、JVM、CPU、内存等 → `metric_service` 等
- **出口**：该服务访问 DB/Redis/MQ/下游 → 主调服务 + `isOut=1`

## 示例

- 「查询最近1小时的服务列表」：`getCurrentTimeRange(60)` → `queryServicesAll(null, fromTime, toTime)`
- 「服务 A 访问 DB」：`metric_service_db`，filter service=A，groupBy resource/sqlContent
- 「哪些服务在访问服务 A」：`metric_service_http`，service=A + isIn=1，groupBy srcService
- 「对比 A/B/C 请求量」：一次查询，`INLIST` + groupBy service，不要分别查三次
- 「7 个服务的关键指标概览」：按上表选 measurement，每条 QueryRequest 仅含 `total_cnt`/`error_cnt`/`sum_duration` 三个 SUM 聚合，一次 `queryMetricData` 提交多条 queryRequests
- 「各实例请求量趋势」：`metric_service`，groupBy service_instance，设 interval
- 「order-api 最近 ERROR 日志」：`getCurrentTimeRange` → `queryLogDetail(services=["order-api"], severities=["ERROR"])`
- 「trace abc 的日志」：`queryLogsByTraceId(traceId="abc")`，可选再 `queryTraceDetail`

## 调用证据与失败处理

- 工具是否可用及当前输入结构以本次 `tools/list` / 工具定义为准。定义与本 Skill 冲突时，保留冲突信息，不自行猜参数；有明确错误提示时按提示修正。
- `measurement` 不是指标别名：`reqCount`、`avgTime`、`service_cpm` 不能作为表名。表不存在时停止该查询，不能换一批相似名字穷举。
- 调用前核对必填项：拓扑需要 `serviceName/fromTime/toTime`，告警需要 `serviceId/fromTime/toTime`；指标每项需要 `measurement/start/end`。时间使用 Asia/Shanghai 的 `yyyy-MM-dd HH:mm:ss`，不是 Unix 毫秒或 ISO 字符串。
- MCP `isError:true`、正文 `ok:false` / `success:false` 或接口错误状态均是失败。外层 SUCCESS、HTTP 200 不代表业务成功。空数组与失败分别报告，不能把失败解释成没有请求或没有告警。
- 参数修正必须有 schema、本文契约或错误信息作为依据。同类失败没有新证据时停止；不能通过不断换参数或工具名试探。已有满足需求的有效查询结果就回报，不扩大成穷举接口调查。
- `inspectService(serviceName)` 若在当前工具清单中可用，提供固定最近 1 小时的入口指标与巡检证据，不接收自定义时间。用户选择其他窗口时用带时间参数的查询，不能把固定窗口结果说成所选窗口。
- 拓扑边的请求数是调用链口径，不将所有上下游边相加冒充服务自身请求量。

## 指标查询完整示例

以下查询参数形状可直接参考；服务名与起止时间必须来自本次真实对象和时间范围，不能固定沿用示例值：

```json
{
  "queryRequests": [{
    "measurement": "metric_service",
    "aggregations": [
      {"function": "SUM", "field": "cnt", "alias": "total_cnt"},
      {"function": "SUM", "field": "error", "alias": "error_cnt"},
      {"function": "SUM", "field": "sumDuration", "alias": "sum_duration"}
    ],
    "wheres": [{"field": "service", "operator": "=", "value": "service-a"}],
    "groupBy": ["service"],
    "interval": 1,
    "intervalUnit": "m",
    "start": "2026-09-07 16:00:00",
    "end": "2026-09-07 17:00:00"
  }],
  "size": 200
}
```

`size` 是工具顶层参数，不放在 queryRequests 的单项中。当前返回按请求排列的结果数组；按真实返回的时间列和聚合别名取值，不猜 `data/values` 层次。


## 回答要求

- 使用中文回答。
- 先给巡检结论，再列关键证据和后续建议。
- 明确区分「工具检测结果」与「基于证据的分析判断」。
- 不要编造未查询到的数据。
- 输出依赖拓扑、调用关系、诊断流程等图形结构时，使用 Markdown 的 `mermaid` 代码块（如 `flowchart` / `graph`），前端会渲染为图。
- mermaid 语法要点：节点 label 含特殊字符时用双引号包裹，如 `id["a/b:c"]`、`id[("db")]`（圆柱体表示 DB/缓存/MQ）；圆柱体 `)]` 必须紧贴，不要写成 `) ]`。
