/**
 * Self-monitor metric help: logic + tunable params.
 * Resolved by exact metric name, then prefix/suffix patterns.
 */

export interface MetricHelpParam {
  /** Environment variable name */
  name: string
  defaultValue?: string
  meaning: string
}

export interface MetricHelpDoc {
  title: string
  /** Related qualified metric names shown in the drawer */
  metrics: string[]
  /** What the metric measures and how it is produced */
  logic: string[]
  /** How to interpret / when to worry */
  tips?: string[]
  /** Tunable knobs that affect this metric */
  params?: MetricHelpParam[]
}

type HelpRule = {
  /** Match if any panel metric equals or matches */
  test: (metric: string) => boolean
  doc: (ctx: { metrics: string[]; title?: string }) => MetricHelpDoc
}

const SIGNAL_DIM =
  '写出侧 dim 为粗粒度信号：trace_* → trace，metric_* → metric，log_* → log，其余 → other。'

function doc(
  title: string,
  metrics: string[],
  logic: string[],
  extra: Partial<MetricHelpDoc> = {},
): MetricHelpDoc {
  return { title, metrics, logic, ...extra }
}

const WRITE_PARAMS: MetricHelpParam[] = [
  {
    name: 'INGEST_DORIS_FLUSH_BATCH_BYTES',
    defaultValue: '52428800（50MiB）',
    meaning: '线程本地 buffer 攒满该字节数后 hand-off 一整批到 Ready 队列；越大写频越低、单批越大。',
  },
  {
    name: 'INGEST_DORIS_FLUSH_INTERVAL_MS',
    defaultValue: '30000',
    meaning: '未满批时按时间强制 hand-off 的间隔（ms）。',
  },
  {
    name: 'INGEST_DORIS_MAX_READY_BATCHES',
    defaultValue: '32',
    meaning: 'Ready 队列容量（批次数）。满则整批丢弃，计入 write.drop。',
  },
  {
    name: 'INGEST_DORIS_STREAM_LOAD_MAX_FAILURES',
    defaultValue: '3',
    meaning: '单 sink 连续 Stream Load 失败次数上限，超限丢弃当前批并记 write.drop。',
  },
  {
    name: 'INGEST_DORIS_TRACE_FLUSH_CONCURRENCY',
    defaultValue: '1',
    meaning: 'trace_dc_span 并行 Stream Load 数。加大吞吐但易推高 Doris tablet version。',
  },
  {
    name: 'INGEST_DORIS_FLUSH_TIMEOUT_MS',
    defaultValue: '60000',
    meaning: '单次 Stream Load 等待超时；大批次时可加大，避免误失败。',
  },
  {
    name: 'DORIS_BE_HTTP_HOST',
    meaning: '直连 BE 做 Stream Load 的 Host；未配则经 FE 302 跳转。',
  },
  {
    name: 'DORIS_BE_HTTP_PORT',
    defaultValue: '8040',
    meaning: '直连 BE 的 HTTP 端口。',
  },
]

function pipelineParams(kind: string): MetricHelpParam[] {
  const envMap: Record<string, { tasks: string; buf: string; tasksDef: string; bufDef: string }> = {
    trace: {
      tasks: 'INGEST_TRACE_TASKS',
      buf: 'INGEST_TRACE_BUFFER_SIZE',
      tasksDef: '8',
      bufDef: '8192',
    },
    metric: {
      tasks: 'INGEST_METRIC_TASKS',
      buf: 'INGEST_METRIC_BUFFER_SIZE',
      tasksDef: '4',
      bufDef: '1024',
    },
    aggregate: {
      tasks: 'INGEST_AGGREGATE_TASKS',
      buf: 'INGEST_AGGREGATE_BUFFER_SIZE',
      tasksDef: '4',
      bufDef: '1024',
    },
  }
  const e = envMap[kind] || envMap.trace
  return [
    {
      name: e.tasks,
      defaultValue: e.tasksDef,
      meaning: `${kind} 管道并发消费任务数；积压时可适度加大（注意 CPU）。`,
    },
    {
      name: e.buf,
      defaultValue: e.bufDef,
      meaning: `${kind} 管道环形缓冲容量；满则 drop。加大可抗突发，但占用更多堆。`,
    },
  ]
}

const SYSTEM_PARAMS: MetricHelpParam[] = [
  {
    name: 'JAVA_TOOL_OPTIONS',
    defaultValue: 'ingest 推荐 -Xms1g -Xmx4g；web 推荐 -Xms512m -Xmx1536m',
    meaning: 'JVM 启动参数（含 -Xmx）。heap.used 接近 heap.max 时需加大堆或降流量。',
  },
]

const RULES: HelpRule[] = [
  // —— Overview multi-metric inbound ——
  {
    test: m => /^ingest\.(otel|sw)\.(trace|metric|log)\.req$/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || '入站请求', metrics, [
        '统计 OTLP / SkyWalking 入站的 item 吞吐：Trace=span 条数，Metric=metric 行数，Log=log 条数（不是 HTTP/gRPC Export 次数）。',
        '总览图将 trace / metric / log 三条画在同一图；接入页按信号分行。',
        'asRate 时为每秒条数（TPS）。',
      ], {
        tips: ['长期为 0：Agent/Collector 未打到 ingest，或防火墙/端口未通。', '突增：对照写出队列与 pipeline drop，判断是否过载。'],
        params: [
          { name: 'INGEST_HTTP_PORT', defaultValue: '4318', meaning: '宿主映射的 OTLP HTTP 端口（容器内 4318）。' },
          { name: 'INGEST_GRPC_PORT', defaultValue: '4317', meaning: '宿主映射的 OTLP gRPC 端口（容器内 4317）。' },
          { name: 'INGEST_SKYWALKING_PORT', defaultValue: '11800', meaning: '宿主映射的 SkyWalking Agent gRPC 端口。' },
        ],
      }),
  },
  {
    test: m => /^ingest\.(otel|sw)\.(trace|metric|log)\.bytes$/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || '入站字节', metrics, [
        '入站 payload 字节累计（协议解码前/接收侧计量）。',
        '与 req 对照可估算单条平均大小；asRate 时为 bytes/s。',
      ], {
        tips: ['字节涨、req 不涨：单包变大（大 span / 大 log body）。'],
        params: [
          {
            name: 'INGEST_DORIS_LOG_BODY_MAX_LENGTH',
            defaultValue: '1048576',
            meaning: '写入 log_dc_record.body 的最大字符数，超长截断。',
          },
        ],
      }),
  },
  {
    test: m => /^ingest\.(otel|sw)\.(trace|metric|log)\.cost_ms$/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || '入站耗时', metrics, [
        '单次 Export / 接收处理耗时（avg = SUM(val_sum)/SUM(cnt)，单位 ms）。',
        '含解析与入队到 pipeline 前的处理；不含后续 Stream Load 等待。',
      ], {
        tips: ['持续升高：解析慢、下游 pipeline 反压或 CPU 不足。'],
      }),
  },
  {
    test: m => /^ingest\.(otel|sw)\.(trace|metric|log)\.fail$/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || '入站失败', metrics, [
        '协议解析或处理失败计数（按失败 span/批次累计）。HTTP 层解析失败也会记 fail。',
      ], {
        tips: ['对照同信号 req：fail 占比高优先查 Agent 协议版本、鉴权、payload 损坏。'],
      }),
  },

  // —— Pipeline ——
  {
    test: m => /^ingest\.pipeline\.(trace|metric|aggregate)\.req$/.test(m),
    doc: ({ metrics, title }) => {
      const kind = (metrics[0] || '').split('.')[2] || 'trace'
      return doc(title || `Pipeline ${kind} req`, metrics, [
        `${kind} 管道处理的 event 数（出队并完成处理的次数）。`,
        '与入站 req 对照可看管道是否跟得上；aggregate 为分钟聚合等异步任务。',
      ], { params: pipelineParams(kind) })
    },
  },
  {
    test: m => /^ingest\.pipeline\.(trace|metric|aggregate)\.cost_ms$/.test(m),
    doc: ({ metrics, title }) => {
      const kind = (metrics[0] || '').split('.')[2] || 'trace'
      return doc(title || `Pipeline ${kind} 耗时`, metrics, [
        '从入队到处理结束的耗时（ms，avg）。反映管道内部排队 + 业务处理延迟。',
      ], {
        tips: ['升高且 queue 高：消费慢，考虑加 tasks 或查下游 Doris 写出。'],
        params: pipelineParams(kind),
      })
    },
  },
  {
    test: m => /^ingest\.pipeline\.(trace|metric|aggregate)\.drop$/.test(m),
    doc: ({ metrics, title }) => {
      const kind = (metrics[0] || '').split('.')[2] || 'trace'
      return doc(title || `Pipeline ${kind} 丢弃`, metrics, [
        '管道环形缓冲满时丢弃的 event 数。属于背压保护，数据会丢。',
      ], {
        tips: ['非 0 需立刻处理：加 buffer、加 tasks，或降上游流量 / 加快写出。'],
        params: pipelineParams(kind),
      })
    },
  },
  {
    test: m => /^ingest\.pipeline\.(trace|metric|aggregate)\.queue(\.cap)?$/.test(m),
    doc: ({ metrics, title }) => {
      const kind = (metrics[0] || '').split('.')[2] || 'trace'
      const isCap = (metrics[0] || '').endsWith('.cap')
      return doc(
        title || (isCap ? `Pipeline ${kind} 队列上限` : `Pipeline ${kind} 队列`),
        metrics,
        isCap
          ? [`${kind} 管道缓冲容量（与 INGEST_*_BUFFER_SIZE 一致）。`]
          : [`${kind} 管道当前占用（gauge）。接近 cap 时易触发 drop。`],
        { params: pipelineParams(kind) },
      )
    },
  },

  // —— Write / Stream Load ——
  {
    test: m => m === 'ingest.write.bytes',
    doc: ({ metrics, title }) =>
      doc(title || '写出字节', metrics, [
        '成功提交到 Doris Stream Load 的 NDJSON 字节累计。',
        SIGNAL_DIM,
        'UI 按 dim 分线；asRate 时为 bytes/s。',
      ], {
        tips: ['某信号 bytes 突增：该类型流量或单行变大。', 'bytes 低但 queue 高：写出卡住（Doris/网络）。'],
        params: WRITE_PARAMS,
      }),
  },
  {
    test: m => m === 'ingest.write.cost_ms',
    doc: ({ metrics, title }) =>
      doc(title || '写出耗时', metrics, [
        '单次 Stream Load HTTP 往返耗时（avg，ms），含 FE 跳转或直连 BE。',
        SIGNAL_DIM,
      ], {
        tips: ['持续 > 数秒：Doris BE 忙、compaction、磁盘或网络问题。', '对照 web.doris.be.* 与 write.queue。'],
        params: WRITE_PARAMS,
      }),
  },
  {
    test: m => m === 'ingest.write.drop',
    doc: ({ metrics, title }) =>
      doc(title || '写出丢弃', metrics, [
        '写出侧主动丢弃的行/批：① Ready 队列满，hand-off 整批丢；② 连续 Stream Load 失败达到上限后丢弃。',
        SIGNAL_DIM,
      ], {
        tips: ['任何持续 drop 都意味着数据丢失。先看 queue vs queue.cap、write.fail、Doris alive。'],
        params: WRITE_PARAMS,
      }),
  },
  {
    test: m => m === 'ingest.write.fail',
    doc: ({ metrics, title }) =>
      doc(title || '写出失败', metrics, [
        'Stream Load 返回失败（HTTP/业务 Status 非 Success）的批次数。失败批会 requeue，直到成功或触发 drop。',
        SIGNAL_DIM,
      ], {
        tips: ['查 ingest 日志中的 Stream Load body / sampleRow；常见原因：schema 不匹配、BE 不可达、超时。'],
        params: WRITE_PARAMS,
      }),
  },
  {
    test: m => m === 'ingest.write.req',
    doc: ({ metrics, title }) =>
      doc(title || '写出成功', metrics, [
        'Stream Load 成功批次数（cnt）。与 fail/drop 对照看写出健康度。',
        SIGNAL_DIM,
      ], { params: WRITE_PARAMS }),
  },
  {
    test: m => m === 'ingest.write.queue',
    doc: ({ metrics, title }) =>
      doc(title || '写出队列', metrics, [
        '待刷入 Doris 的行数（gauge）= 各线程本地 buffer 行数 + Ready 队列中已打包批的行数。',
        '同信号多表（如多张 metric_*）会加总到同一 dim。',
        SIGNAL_DIM,
      ], {
        tips: [
          '持续升高：Stream Load 跟不上生产；对照 cost_ms / Doris CPU·磁盘。',
          '接近「各表 max-ready-batches × 批大小」量级时易触发 drop。',
        ],
        params: WRITE_PARAMS,
      }),
  },
  {
    test: m => m === 'ingest.write.queue.cap',
    doc: ({ metrics, title }) =>
      doc(title || 'Ready 队列上限', metrics, [
        'Ready 队列容量（批次数）按信号加总（每张业务表一个 writer，各自 max-ready-batches）。',
        '注意：queue 是行数，queue.cap 是批次数，单位不同，不能直接相除当利用率。',
      ], { params: WRITE_PARAMS }),
  },

  // —— Trace assembly ——
  {
    test: m => m === 'ingest.trace.assembly.pending',
    doc: ({ metrics, title }) =>
      doc(title || 'Trace 组装待处理', metrics, [
        '等待组装/补齐的 Trace 相关积压（gauge）。',
      ], {
        params: [
          {
            name: 'INGEST_TRACE_ASSEMBLY_CHECK_INTERVAL_MS',
            defaultValue: '2000',
            meaning: '组装检查调度间隔（ms）。',
          },
          ...pipelineParams('trace'),
        ],
      }),
  },
  {
    test: m => m === 'ingest.trace.drop.ignore',
    doc: ({ metrics, title }) =>
      doc(title || 'Ignore 丢弃', metrics, [
        '命中 Trace Ignore 规则（资源名/URL 精确或正则全匹配）而主动丢弃的 span 数。属于配置行为，不是故障。',
      ], {
        params: [
          {
            name: 'INGEST_TRACE_IGNORE_RESOURCES',
            meaning: '精确匹配 resource / metaHttpUrl 列表（逗号分隔等，按部署约定传入）。',
          },
          {
            name: 'INGEST_TRACE_IGNORE_RESOURCE_REGEX',
            meaning: 'Java Matcher#matches 全串正则列表。',
          },
        ],
      }),
  },

  // —— Cluster ——
  {
    test: m => m === 'ingest.cluster.member.count',
    doc: ({ metrics, title }) =>
      doc(title || '集群成员数', metrics, [
        '当前 ingest 集群感知到的成员数（gauge）。',
      ], {
        params: [
          { name: 'INGEST_CLUSTER_ENABLED', defaultValue: 'false', meaning: '是否启用多实例集群模式。' },
          { name: 'ZK_CONNECT_STRING', meaning: 'ZooKeeper 连接串，用于成员发现/选主。' },
          { name: 'INGEST_CLUSTER_GRPC_PORT', defaultValue: '18112', meaning: '实例间转发 gRPC 端口。' },
        ],
      }),
  },
  {
    test: m => m === 'ingest.cluster.leader',
    doc: ({ metrics, title }) =>
      doc(title || '本节点 Leader', metrics, [
        '本 ingest 实例是否为 Leader（0/1）。部分协调任务仅 Leader 执行。',
      ]),
  },
  {
    test: m => m === 'ingest.cluster.effective',
    doc: ({ metrics, title }) =>
      doc(title || '集群模式生效', metrics, [
        '集群模式是否真正生效（0/1）。enabled 但 ZK 不可用时可能为 0。',
      ]),
  },
  {
    test: m => m.startsWith('ingest.cluster.forward.in.'),
    doc: ({ metrics, title }) =>
      doc(title || '转发入站', metrics, [
        '其他 ingest 实例转发到本机的流量（req/fail 等）。指标名中含 stream 分区名。',
      ], {
        tips: ['入站 fail 升高：本机处理不过来或协议错误。'],
        params: [
          { name: 'INGEST_CLUSTER_ENABLED', meaning: '关闭则无跨实例转发。' },
          { name: 'INGEST_CLUSTER_GRPC_PORT', defaultValue: '18112', meaning: '转发端口。' },
        ],
      }),
  },
  {
    test: m => m.startsWith('ingest.cluster.forward.'),
    doc: ({ metrics, title }) =>
      doc(title || '转发出站', metrics, [
        '本机按分区把流量转发给其他 ingest 成员的出站指标（req/fail/bytes/cost_ms）。',
      ], {
        tips: ['fail / cost_ms 升高：对端宕机、网络或对端积压。'],
        params: [
          { name: 'INGEST_CLUSTER_ENABLED', meaning: '关闭则无跨实例转发。' },
          { name: 'INGEST_CLUSTER_GRPC_PORT', defaultValue: '18112', meaning: '转发端口。' },
        ],
      }),
  },
  {
    test: m => m.startsWith('ingest.cluster.drop.'),
    doc: ({ metrics, title }) =>
      doc(title || '集群丢弃', metrics, [
        '集群转发路径上丢弃的数据（对端不可达、队列满等）。',
      ], {
        tips: ['与 forward.fail 一起看，定位是出站失败还是本地直接丢。'],
      }),
  },

  // —— System ——
  {
    test: m => /\.system\.cpu\.usage$/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || 'CPU 使用率', metrics, [
        '进程 CPU 使用率（%）。ingest / web 各有一套。',
      ], {
        tips: ['长期高位：加大副本/任务数前先确认是否写出或查询热点。'],
        params: SYSTEM_PARAMS,
      }),
  },
  {
    test: m => /\.system\.memory\.heap\.used$/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || 'Heap Used', metrics, [
        'JVM 堆已用字节。对照 heap.max；接近上限时 GC 频繁或 OOM 风险。',
      ], { params: SYSTEM_PARAMS }),
  },
  {
    test: m => /\.system\.memory\.heap\.max$/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || 'Heap Max', metrics, [
        'JVM 堆上限（-Xmx）。',
      ], { params: SYSTEM_PARAMS }),
  },
  {
    test: m => /\.system\.thread\.count$/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || '线程数', metrics, [
        'JVM 存活线程数。管道 tasks、Stream Load 并发、gRPC 线程都会影响。',
      ], {
        params: [...pipelineParams('trace'), ...WRITE_PARAMS.slice(4, 5), ...SYSTEM_PARAMS],
      }),
  },
  {
    test: m => /\.system\.gc\.(count|time)\./.test(m),
    doc: ({ metrics, title }) =>
      doc(title || 'GC', metrics, [
        'GC 次数（count）或耗时增量（time，ms），按 GC 名称分指标。',
      ], {
        tips: ['time 突增伴随 heap 锯齿：堆偏小或短命对象过多（队列积压）。'],
        params: SYSTEM_PARAMS,
      }),
  },

  // —— Web query ——
  {
    test: m => /^web\.query\.[^.]+\.req$/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || '查询请求', metrics, [
        'Web Portal API 请求量，按 URI 归入 domain：trace / metric / log / alarm / ai / portal / other。',
        '例：/webapi/trace* → trace，/webapi/api/v1/ai* → ai。',
      ]),
  },
  {
    test: m => /^web\.query\.[^.]+\.cost_ms$/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || '查询耗时', metrics, [
        '该 domain API 的平均耗时（ms）。含 Doris 查询与业务组装。',
      ], {
        tips: ['升高：查 Doris 负载、扫描范围、慢 SQL；对照 doris.be / fe 指标。'],
      }),
  },
  {
    test: m => /^web\.query\.[^.]+\.fail$/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || '查询失败', metrics, [
        'HTTP status ≥ 400 的请求计数。',
      ], {
        tips: ['对照 web 日志与 doris.up；排障模式下部分查询会失败。'],
      }),
  },

  // —— Doris ——
  // 官方含义对照：https://doris.apache.org/zh-CN/docs/admin-manual/maint-monitor/metrics/
  // 平台名 ← Prometheus 名 见 DorisSelfMonitorMapper；counter 存相邻刮取差值。
  {
    test: m => m === 'web.doris.up',
    doc: ({ metrics, title }) =>
      doc(title || 'Doris 连通', metrics, [
        'Web 对配置 FE 的 JDBC/探活是否通（0/1）。instance 为 FE Host。',
      ], {
        tips: ['up=0 时 Web 可能进入排障模式，业务查询不可用。'],
        params: [
          { name: 'DORIS_FE_HOST', defaultValue: '127.0.0.1', meaning: 'Doris FE 地址，需与 ingest / web 一致。' },
          { name: 'DORIS_FE_HTTP_PORT', defaultValue: '8030', meaning: 'FE HTTP 端口。' },
          { name: 'DORIS_FE_QUERY_PORT', defaultValue: '9030', meaning: 'FE MySQL/JDBC 查询端口。' },
        ],
      }),
  },
  {
    test: m => m === 'web.doris.be.alive' || m === 'web.doris.fe.alive',
    doc: ({ metrics, title }) =>
      doc(title || '节点 Alive', metrics, [
        'SHOW BACKENDS / FRONTENDS 中该节点是否 Alive（0/1）。instance=节点 Host。',
      ]),
  },
  {
    test: m => m === 'web.doris.fe.is_master',
    doc: ({ metrics, title }) =>
      doc(title || 'FE is_master', metrics, [
        'SHOW FRONTENDS 中该 FE 是否为 Master（0/1）。',
      ], {
        tips: ['元数据写入、tablet 调度等只在 Master 上发生；多 FE 时对照 edit_log / txn 看是否写在 Master。'],
      }),
  },
  {
    test: m => /web\.doris\..*edit_log_write/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || 'edit_log_write', metrics, [
        '映射自官方 doris_fe_edit_log{type="write"}：元数据日志（EditLog）写入次数的累计计数。',
        '平台存相邻刮取差值，等价于官网「通过斜率观察元数据写入频率」。',
      ], {
        tips: [
          '频率异常升高：DDL/导入事务/副本调度等元数据变更过密，或 FE 元数据写入延迟。',
          '可对照 doris_fe_edit_log 其它 type（字节量、current 条数）与 Master 角色。',
        ],
      }),
  },
  {
    test: m => /web\.doris\..*image_write/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || 'image_write', metrics, [
        '映射自官方 doris_fe_image_write：生成元数据镜像文件的成功/失败次数（type=success|failed）。',
        '平台存相邻刮取差值。',
      ], {
        tips: ['failed 不应出现；若失败需按官方建议人工介入检查 FE 元数据镜像。'],
      }),
  },
  {
    test: m => /web\.doris\.fe\.txn_request/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || 'FE 导入事务', metrics, [
        '映射自官方 doris_fe_txn_counter：各状态导入事务数量的累计值（success / failed / reject / begin）。',
        '平台名后缀为 type；存相邻刮取差值。',
      ], {
        tips: [
          'failed / reject 升高：导入失败或运行中事务超阈被拒（官方：运行事务过多时会 reject）。',
          '对照 ingest.write.fail 与 BE stream_load / txn_request。',
        ],
      }),
  },
  {
    test: m => /web\.doris\.be\.txn_request/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || 'BE Stream Load 事务', metrics, [
        '映射自官方 doris_be_stream_load_txn_request：Stream Load（含 Routine Load）事务 begin / commit / rollback 累计次数。',
        '平台存相邻刮取差值。',
      ], {
        tips: ['rollback 升高：BE 侧 load 事务失败；对照 stream_load、memtable_flush 与 ingest.write.fail。'],
        params: WRITE_PARAMS,
      }),
  },
  {
    test: m => /web\.doris\..*query_success/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || 'query_success', metrics, [
        '映射自官方 doris_fe_query_total：所有查询请求的累积计数（不含带 user 标签的分用户系列）。',
        '平台存相邻刮取差值，可看成刮取间隔内的查询量。',
      ], {
        tips: ['骤降：FE 不可用或客户端连不上；对照 web.doris.up、web.query.*.fail。'],
      }),
  },
  {
    test: m => /web\.doris\.(be|fe)\.cpu$/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || 'BE CPU', metrics, [
        '映射自官方 doris_be_cpu（采集自 /proc/stat）。平台对各 mode jiffies 做差值后归一成占比 %（user/system/idle/…），同节点合计约 100。',
        'groupBy dim 看各 mode。',
      ], {
        tips: ['user+system 长期很高：查询/compaction/stream load 过重，考虑扩 BE 或降写入频率。'],
      }),
  },
  {
    test: m => /web\.doris\..*process_mem/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || '进程内存', metrics, [
        '映射自官方 doris_be_memory_allocated_bytes：BE 进程物理内存（/proc/self/status VmRSS）。',
      ], {
        tips: ['持续升高需查 compaction、SegmentCache、导入并发；对照 used_pct 与主机 free。'],
      }),
  },
  {
    test: m => /web\.doris\..*(jvm_heap|jvm_young_gc|jvm_old_gc)/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || 'JVM', metrics, [
        '映射自官方 jvm_heap_size_bytes（max/used/committed）与 GC 系列（count/time）：观测 FE/BE JVM 堆与垃圾回收。',
        'GC counter 类存相邻刮取差值。',
      ], {
        tips: ['FE -Xmx 过大易拖垮主机；官方镜像默认堆较大，部署脚本常会下调。old GC 频繁需关注堆与元数据压力。'],
      }),
  },
  {
    test: m => /web\.doris\..*process_fd/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || '文件句柄', metrics, [
        '映射自官方 doris_be_process_fd_num_used / _limit_hard（及 soft）：经 /proc/pid/limits 采集的进程文件句柄使用与上限。',
      ], {
        tips: ['used 接近 hard limit 时打开文件/连接会失败，需调高 ulimit 或排查句柄泄漏。'],
      }),
  },
  {
    test: m => /web\.doris\..*running_tasks/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || '运行任务', metrics, [
        '来自 SHOW BACKENDS 的 RunningTasks：该 BE 当前正在运行的任务数（gauge）。',
      ], {
        tips: ['长期偏高：查询/导入/compaction 堆积，对照 compaction score 与 stream_load。'],
      }),
  },
  {
    test: m => /web\.doris\..*tablet_num/.test(m) && !m.includes('compaction'),
    doc: ({ metrics, title }) =>
      doc(title || 'Tablet 数', metrics, [
        '来自 SHOW BACKENDS 的 TabletNum（与官方 doris_fe_tablet_num 同类信息）：各 BE 当前 tablet 总数。',
      ], {
        tips: ['官方建议关注分布是否均匀、绝对值是否合理；过多会加重元数据与 compaction。'],
      }),
  },
  {
    test: m => /web\.doris\..*(used_pct|disks_)/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || '磁盘占用', metrics, [
        'used_pct 来自 SHOW BACKENDS UsedPct；disks_* 映射自官方 doris_be_disks_local_used_capacity / disks_total_capacity（按 path）。',
      ], {
        tips: ['持续升高需扩容磁盘或做数据保留/compaction 治理。'],
      }),
  },
  {
    test: m => /web\.doris\..*stream_load/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || 'Stream Load', metrics, [
        '映射自官方 doris_be_stream_load：receive_bytes=接收字节累计，load_rows=最终导入行数累计（含 Stream Load 与 Routine Load）。',
        '平台存相邻刮取差值。与 ingest.write.* 对照：平台侧写出 vs Doris 侧接收。',
      ], {
        tips: ['receive 有量但 load_rows 不涨：导入失败或卡在事务；对照 txn_request.rollback、write.fail。'],
        params: WRITE_PARAMS,
      }),
  },
  {
    test: m => /web\.doris\..*memtable_flush/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || 'MemTable Flush', metrics, [
        '映射自官方 doris_be_memtable_flush_total / _duration_us：memtable 刷盘次数与耗时累计；duration 官方单位为微秒。',
        '平台存相邻刮取差值，斜率可观测写入频率与延迟。',
      ], {
        tips: ['duration 斜率升高常伴随 ingest write.cost_ms 升高；磁盘或 compaction 压力大时会拖慢 flush。'],
        params: WRITE_PARAMS,
      }),
  },
  {
    test: m => /web\.doris\..*compaction/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || 'Compaction', metrics, [
        'compaction_bytes_total 映射自官方 doris_be_compaction_bytes_total：compaction 处理 input rowset 磁盘字节累计（可按 type 分 cumulative/base/write/read）。',
        'tablet_*_max_compaction_score 映射自官方同名指标：当前最大 Base/Cumulative Compaction Score；越高表示堆积越严重，可能拖慢查询/写入。',
      ], {
        tips: [
          'Score 长期偏高：写入过频（小批量 Stream Load）推高 tablet version。',
          '与 INGEST_DORIS_FLUSH_BATCH_BYTES、TRACE_FLUSH_CONCURRENCY 联动：偏大批次、低并发有利于 version。',
        ],
        params: WRITE_PARAMS,
      }),
  },
  {
    test: m => /web\.doris\..*query_scan_/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || '查询扫描', metrics, [
        '映射自官方 doris_be_query_scan_bytes / query_scan_rows：仅统计读取 Olap 表的数据量/行数累计；rows 为 RawRowsRead（索引跳过的行仍计入）。',
        '平台存相邻刮取差值，斜率可观测查询速率。',
      ], {
        tips: ['突增：大范围扫描或慢查询；对照 web.query.*.cost_ms 与 segment_read。'],
      }),
  },
  {
    test: m => /web\.doris\..*segment_read/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || 'Segment 读取', metrics, [
        '映射自官方 doris_be_segment_read：segment_read_total=读取的 segment 个数累计；segment_row_total=读取行数累计（含被索引过滤的行，≈ segment 数 × 每段总行数）。',
        '平台存相邻刮取差值。',
      ], {
        tips: ['与 query_scan_* 一起看扫描放大；过高说明读放大或 compaction 不足。'],
      }),
  },
  {
    test: m => /web\.doris\..*thread_pool/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || '线程池', metrics, [
        'FE 映射自官方 doris_fe_thread_pool{type="active_thread_num"}：各类线程池当前执行中的任务数。',
        'BE 映射自 doris_be_thread_pool_active_threads：按 thread_pool_name 统计活跃线程。',
      ], {
        tips: ['某池长期打满：对应路径（查询/导入/心跳等）堆积；对照同类官方 queue 指标。'],
      }),
  },
  {
    test: m => /web\.doris\..*engine_requests_total/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || 'Engine 请求', metrics, [
        '映射自官方 doris_be_engine_requests_total：BE 引擎侧各类请求累计（type/status 折入指标名后缀）。',
        '平台存相邻刮取差值。',
      ], {
        tips: ['关注 status=失败类后缀是否升高；对照 compaction / publish 相关 type。'],
      }),
  },
  {
    test: m => /web\.doris\..*async_delta_writer/.test(m),
    doc: ({ metrics, title }) =>
      doc(title || 'Async Delta Writer', metrics, [
        '映射自官方 doris_be_async_delta_writer_*：异步 delta writer 执行耗时、队列长度、任务等待时长等。',
        '累计类指标存相邻刮取差值。',
      ], {
        tips: ['queue / pending 升高：写入路径堆积，常与 memtable flush、磁盘 IO 相关。'],
        params: WRITE_PARAMS,
      }),
  },
  {
    test: m => m.startsWith('web.doris.'),
    doc: ({ metrics, title }) =>
      doc(title || 'Doris 指标', metrics, [
        '由 web 定时刮取 FE/BE /metrics（Prometheus）或 SHOW 元数据，映射到 metric_platform，前缀 web.doris.(fe|be).。',
        'instance 为 Doris 节点 Host（不是 web 主机名）。counter 类写入相邻刮取差值。',
        '官方指标说明：https://doris.apache.org/zh-CN/docs/admin-manual/maint-monitor/metrics/',
      ], {
        tips: ['可用 tagValues 发现当前环境实际指标名；对照同页 Alive / Compaction / Stream Load。'],
      }),
  },
]

/** Summary card titles → help (overview KPI). */
const CARD_HELP: Record<string, MetricHelpDoc> = {
  '入站 TPS': doc('入站 TPS', ['ingest.otel.*.req', 'ingest.sw.*.req'], [
    '时间窗内入站 req 总和 ÷ 窗口秒数，得到平均每秒 item 数。',
    '覆盖 OTel / SkyWalking 的 trace·metric·log。',
  ], {
    tips: ['与接入页「协议入站」req 曲线一致，只是汇总成单值。'],
  }),
  '写出失败': doc('写出失败', ['ingest.write.fail'], [
    '窗口内 Stream Load 失败批次数汇总。',
    SIGNAL_DIM,
  ], {
    tips: ['非 0 时到接入页写出图按 dim 看是哪个信号。'],
    params: WRITE_PARAMS,
  }),
  'Doris 磁盘占用': doc('Doris 磁盘占用', ['web.doris.be.used_pct'], [
    'BE 磁盘占用 % 的窗口汇总（gauge 类）。',
  ]),
  '查询失败': doc('查询失败', ['web.query.*.fail'], [
    '各业务域查询 API（HTTP≥400）失败次数汇总。',
  ]),
}

function panelMetrics(spec: {
  metric?: string
  metrics?: string[]
  metricPrefixes?: string[]
  title?: string
}): string[] {
  if (spec.metric) {
    return [spec.metric]
  }
  if (spec.metrics?.length) {
    return spec.metrics
  }
  if (spec.metricPrefixes?.length) {
    return spec.metricPrefixes
  }
  return []
}

export function resolveMetricHelp(spec: {
  metric?: string
  metrics?: string[]
  metricPrefixes?: string[]
  metricSuffixes?: string[]
  title?: string
}): MetricHelpDoc | null {
  const metrics = panelMetrics(spec)
  const title = spec.title

  if (title && CARD_HELP[title]) {
    return { ...CARD_HELP[title], title }
  }

  for (const m of metrics) {
    for (const rule of RULES) {
      if (rule.test(m)) {
        return rule.doc({ metrics, title })
      }
    }
  }

  // Prefix-only panels (discovered cluster/doris): try prefix as fake metric
  for (const p of spec.metricPrefixes || []) {
    for (const rule of RULES) {
      if (rule.test(p) || rule.test(`${p}x`)) {
        return rule.doc({ metrics: metrics.length ? metrics : [p], title })
      }
    }
  }

  if (metrics.length || title) {
    return doc(title || metrics[0] || '指标说明', metrics, [
      '该图展示平台自监控时序（metric_platform）。',
      '更细语义见运维文档《自监控指标清单》；若为动态发现指标，名称即 scrape/映射后的全名。',
    ], {
      tips: ['优先对照同页相关 queue / fail / drop 与 Doris 可用性。'],
    })
  }
  return null
}

export function resolveCardHelp(card: { title?: string; metric?: string; metrics?: string[]; metricPrefixes?: string[] }): MetricHelpDoc | null {
  if (card.title && CARD_HELP[card.title]) {
    return { ...CARD_HELP[card.title] }
  }
  return resolveMetricHelp(card)
}
