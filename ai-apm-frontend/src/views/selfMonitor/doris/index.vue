<template>
  <div class="sm-page">
    <metric-section
      title="可用性"
      desc="连通与节点 Alive · 按 Doris 实例（IP/Host）"
      :panels="alivePanels"
      :timeParams="timeParams"
    />

    <div class="sm-group">
      <div class="sm-group-head">
        <h2 class="sm-group-title">FE</h2>
        <p class="sm-group-desc">Frontend · JVM · 元数据事务 · 查询</p>
      </div>
      <metric-section
        title="资源"
        desc="JVM · GC · 进程 FD"
        :panels="feResourcePanels"
        :timeParams="timeParams"
        :loading="discovering"
      />
      <metric-section
        title="写入与元数据"
        desc="事务 · Edit Log · Image"
        :panels="feWritePanels"
        :timeParams="timeParams"
        :loading="discovering"
      />
      <metric-section
        title="查询"
        desc="查询成功次数"
        :panels="feQueryPanels"
        :timeParams="timeParams"
        :loading="discovering"
      />
      <metric-section
        v-if="fePoolPanels.length"
        title="线程池"
        desc="活跃线程"
        :panels="fePoolPanels"
        :timeParams="timeParams"
        :loading="discovering"
      />
      <metric-section
        v-if="feOtherPanels.length"
        title="其他"
        desc="未归类 FE 指标"
        :panels="feOtherPanels"
        :timeParams="timeParams"
        :loading="discovering"
      />
    </div>

    <div class="sm-group">
      <div class="sm-group-head">
        <h2 class="sm-group-title">BE</h2>
        <p class="sm-group-desc">Backend · 资源 · Stream Load · Compaction · 扫描</p>
      </div>
      <metric-section
        title="资源"
        desc="CPU · 内存 · 磁盘 · 网络 · Tablet"
        :panels="beResourcePanels"
        :timeParams="timeParams"
        :loading="discovering"
      />
      <metric-section
        title="写入"
        desc="Stream Load · MemTable Flush · 事务"
        :panels="beWritePanels"
        :timeParams="timeParams"
        :loading="discovering"
      />
      <metric-section
        title="Compaction"
        desc="字节量 · Score · 任务请求"
        :panels="beCompactionPanels"
        :timeParams="timeParams"
        :loading="discovering"
      />
      <metric-section
        title="查询与扫描"
        desc="Scan · Segment Read"
        :panels="beQueryPanels"
        :timeParams="timeParams"
        :loading="discovering"
      />
      <metric-section
        v-if="beEnginePanels.length"
        title="引擎任务"
        desc="engine_requests（非 Compaction）"
        :panels="beEnginePanels"
        :timeParams="timeParams"
        :loading="discovering"
      />
      <metric-section
        v-if="bePoolPanels.length"
        title="线程池"
        desc="写入 / Compaction / 查询相关活跃线程"
        :panels="bePoolPanels"
        :timeParams="timeParams"
        :loading="discovering"
      />
      <metric-section
        v-if="beOtherPanels.length"
        title="其他"
        desc="未归类 BE 指标"
        :panels="beOtherPanels"
        :timeParams="timeParams"
        :loading="discovering"
      />
    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Watch } from 'vue-property-decorator';
import MetricSection from '../components/MetricSection.vue';
import { ChartPanelSpec, listPlatformMetrics, shortMetricLabel } from '../shared';

const PREFIX = 'web.doris.';

function dorisPanel(metric: string, title?: string, extra: Partial<ChartPanelSpec> = {}): ChartPanelSpec {
  const logical = logicalName(metric);
  const defaults: Partial<ChartPanelSpec> = {};
  // 落库为 µs，展示换算为 ms
  if (logical.includes('memtable_flush_duration_us')) {
    defaults.scale = 0.001;
    defaults.unit = 'ms';
  }
  return {
    metric,
    title: title || panelTitle(metric),
    value: 'gauge',
    groupBy: ['instance'],
    hint: '按 Doris 实例',
    components: ['web'],
    ...defaults,
    ...extra,
  };
}

function shortName(metric: string): string {
  return metric.startsWith(PREFIX) ? metric.slice(PREFIX.length) : metric;
}

/** Strip optional fe./be. role for classification / sorting. */
function logicalName(metric: string): string {
  const n = shortName(metric);
  if (n.startsWith('be.') || n.startsWith('fe.')) {
    return n.slice(3);
  }
  return n;
}

function roleOf(metric: string): 'be' | 'fe' | '' {
  const n = shortName(metric);
  if (n.startsWith('be.')) {
    return 'be';
  }
  if (n.startsWith('fe.')) {
    return 'fe';
  }
  return '';
}

/** Titles omit FE/BE — page already groups by role. */
function panelTitle(metric: string): string {
  const logical = logicalName(metric);
  return shortMetricLabel(`doris.${logical}`).replace(/^doris\./, '') || shortName(metric);
}

function isCompactionEngine(metric: string): boolean {
  return logicalName(metric).toLowerCase().includes('compaction');
}

function isRelevantPool(metric: string): boolean {
  const n = logicalName(metric);
  if (!n.startsWith('thread_pool.')) {
    return false;
  }
  const pool = n.slice('thread_pool.'.length).toLowerCase();
  return /flush|compaction|memtable|load|sendbatch|fragment|publish|segmentfilewriter|groupcommit|query|coordinator/.test(pool);
}

type Category =
  | 'resource'
  | 'write'
  | 'compaction'
  | 'query'
  | 'engine'
  | 'pool'
  | 'other'
  | 'skip';

type RoleBuckets = Record<Exclude<Category, 'skip'>, string[]>;

function emptyBuckets(): RoleBuckets {
  return {
    resource: [],
    write: [],
    compaction: [],
    query: [],
    engine: [],
    pool: [],
    other: [],
  };
}

function isResourceLogical(logical: string): boolean {
  return (
    logical === 'cpu'
    || logical.startsWith('cpu.')
    || logical.startsWith('jvm_')
    || logical.startsWith('disks_')
    || logical.startsWith('process_')
    || logical.startsWith('max_network_')
    || logical === 'used_pct'
    || logical === 'tablet_num'
    || logical === 'running_tasks'
    || logical === 'compaction_mem_bytes'
  );
}

function classifyCategory(metric: string): Category {
  const n = shortName(metric);
  const logical = logicalName(metric);
  if (
    n === 'up'
    || n === 'be.alive'
    || n === 'fe.alive'
    || n === 'fe.is_master'
  ) {
    return 'skip';
  }
  // CPU 统一一张图 groupBy dim；旧的 cpu.user 等后缀名跳过发现
  if (logical === 'cpu' || logical.startsWith('cpu.')) {
    return 'skip';
  }
  // BE JVM 堆/GC 多数场景参考价值低；FE 仍展示
  if (roleOf(metric) === 'be' && logical.startsWith('jvm_')) {
    return 'skip';
  }
  if (isResourceLogical(logical)) {
    return 'resource';
  }
  if (
    logical.startsWith('stream_load.')
    || logical.startsWith('memtable_flush')
    || logical.startsWith('txn_request.')
    || logical.startsWith('async_delta_writer')
    || logical === 'edit_log_write'
    || logical.startsWith('image_write.')
  ) {
    return 'write';
  }
  if (
    logical.startsWith('compaction_bytes_total')
    || (logical.startsWith('tablet_') && logical.includes('compaction'))
    || (logical.startsWith('engine_requests_total.') && isCompactionEngine(metric))
  ) {
    return 'compaction';
  }
  if (
    logical === 'query_success'
    || logical.startsWith('query_scan_')
    || logical.startsWith('segment_read.')
  ) {
    return 'query';
  }
  if (logical.startsWith('engine_requests_total.')) {
    return 'engine';
  }
  if (logical.startsWith('thread_pool.')) {
    return isRelevantPool(metric) ? 'pool' : 'skip';
  }
  return 'other';
}

/** Stable curated order within each category. */
function sortKey(metric: string): string {
  const orderHints = [
    'cpu', 'process_mem_bytes', 'jvm_heap_size_bytes.used', 'jvm_heap_size_bytes.max',
    'jvm_young_gc', 'jvm_old_gc', 'process_fd_num_used', 'process_fd_num_limit_hard',
    'used_pct', 'disks_data_used_capacity', 'disks_total_capacity',
    'max_network_receive_bytes_rate', 'max_network_send_bytes_rate',
    'running_tasks', 'tablet_num',
    'stream_load.receive_bytes', 'stream_load.load_rows',
    'memtable_flush_total', 'memtable_flush_duration_us',
    'memtable_flush_disk_bytes_total', 'memtable_flush_io_time_us',
    'txn_request.begin', 'txn_request.commit', 'txn_request.rollback',
    'txn_request.success', 'txn_request.failed', 'txn_request.reject',
    'edit_log_write', 'image_write',
    'async_delta_writer',
    'compaction_bytes_total.write', 'compaction_bytes_total.read',
    'compaction_bytes_total.cumulative', 'compaction_bytes_total.base',
    'tablet_cumulative_max_compaction_score', 'tablet_base_max_compaction_score',
    'tablet_update_max_compaction_score',
    'query_success', 'query_scan_bytes', 'query_scan_rows',
    'segment_read.segment_read_total', 'segment_read.segment_row_total',
  ];
  const logical = logicalName(metric);
  const idx = orderHints.findIndex(h => logical === h || logical.startsWith(h));
  const order = idx >= 0 ? String(idx).padStart(3, '0') : '999';
  return `${order}:${logical}`;
}

function mergeCoreAndExtra(
  core: ChartPanelSpec[],
  discovered: string[],
  extraLimit?: number,
): ChartPanelSpec[] {
  const coreMetrics = new Set(core.map(p => p.metric).filter(Boolean) as string[]);
  const extra = discovered.filter(m => !coreMetrics.has(m));
  const sorted = extra.slice().sort((a, b) => sortKey(a).localeCompare(sortKey(b)));
  const sliced = extraLimit != null ? sorted.slice(0, extraLimit) : sorted;
  return [...core, ...sliced.map(m => dorisPanel(m))];
}

function toPanels(metrics: string[], limit?: number): ChartPanelSpec[] {
  const sorted = metrics.slice().sort((a, b) => sortKey(a).localeCompare(sortKey(b)));
  const sliced = limit != null ? sorted.slice(0, limit) : sorted;
  return sliced.map(m => dorisPanel(m));
}

@Component({ components: { MetricSection } })
export default class SelfMonitorDoris extends Vue {
  private timeParams: any = {};
  private discovering = false;

  private alivePanels: ChartPanelSpec[] = [
    dorisPanel('web.doris.up', 'Doris 连通'),
    dorisPanel('web.doris.be.alive', 'BE Alive'),
    dorisPanel('web.doris.fe.alive', 'FE Alive'),
    dorisPanel('web.doris.fe.is_master', 'FE is_master'),
  ];

  private feResourcePanels: ChartPanelSpec[] = [];
  private feWritePanels: ChartPanelSpec[] = [];
  private feQueryPanels: ChartPanelSpec[] = [];
  private fePoolPanels: ChartPanelSpec[] = [];
  private feOtherPanels: ChartPanelSpec[] = [];

  private beResourcePanels: ChartPanelSpec[] = [];
  private beWritePanels: ChartPanelSpec[] = [];
  private beCompactionPanels: ChartPanelSpec[] = [];
  private beQueryPanels: ChartPanelSpec[] = [];
  private beEnginePanels: ChartPanelSpec[] = [];
  private bePoolPanels: ChartPanelSpec[] = [];
  private beOtherPanels: ChartPanelSpec[] = [];

  @Watch('globalTimeV2', { deep: true })
  private watchGlobalTime() {
    this.refreshTime();
    this.discover();
  }

  private mounted() {
    this.refreshTime();
    this.discover();
    this.$eventBus.$on('GlobalRefresh', this, () => {
      this.onRefresh();
    });
  }

  private beforeDestroy() {
    this.$eventBus.$off('GlobalRefresh');
  }

  private onRefresh() {
    this.refreshTime();
    this.discover();
  }

  private refreshTime() {
    const { fromTime, toTime, interval } = this.getGlobalTimeV2();
    this.timeParams = { fromTime, toTime, interval };
  }

  private async discover() {
    if (!this.timeParams?.fromTime) {
      return;
    }
    this.discovering = true;
    try {
      const all = await listPlatformMetrics(this.timeParams, {
        metricPrefixes: ['web.doris.'],
        components: ['web'],
        limit: 300,
      });

      const fe = emptyBuckets();
      const be = emptyBuckets();
      for (const m of all) {
        const cat = classifyCategory(m);
        if (cat === 'skip') {
          continue;
        }
        const role = roleOf(m);
        if (role === 'fe') {
          fe[cat].push(m);
        } else if (role === 'be') {
          be[cat].push(m);
        }
      }

      this.feResourcePanels = mergeCoreAndExtra([
        dorisPanel('web.doris.fe.jvm_heap_size_bytes.used'),
        dorisPanel('web.doris.fe.jvm_heap_size_bytes.max'),
        dorisPanel('web.doris.fe.jvm_young_gc.count'),
        dorisPanel('web.doris.fe.jvm_old_gc.count'),
        dorisPanel('web.doris.fe.process_fd_num_used'),
      ], fe.resource, 9);

      this.feWritePanels = mergeCoreAndExtra([
        dorisPanel('web.doris.fe.txn_request.success'),
        dorisPanel('web.doris.fe.txn_request.failed'),
        dorisPanel('web.doris.fe.txn_request.reject'),
        dorisPanel('web.doris.fe.edit_log_write'),
      ], fe.write, 9);

      this.feQueryPanels = mergeCoreAndExtra([
        dorisPanel('web.doris.fe.query_success'),
      ], fe.query, 6);

      this.fePoolPanels = toPanels(fe.pool, 12);
      this.feOtherPanels = toPanels(fe.other, 9);

      this.beResourcePanels = mergeCoreAndExtra([
        dorisPanel('web.doris.be.cpu', 'CPU', {
          groupBy: ['dim'],
          value: 'gauge',
          unit: '%',
          hint: '各 mode 占比（合计 ≈ 100%）',
        }),
        dorisPanel('web.doris.be.process_mem_bytes'),
        dorisPanel('web.doris.be.used_pct'),
        dorisPanel('web.doris.be.disks_data_used_capacity.storage'),
        dorisPanel('web.doris.be.running_tasks'),
        dorisPanel('web.doris.be.tablet_num'),
      ], be.resource, 9);

      // BE txn labels are begin/commit/rollback（不是 FE 的 success/failed）
      this.beWritePanels = mergeCoreAndExtra([
        dorisPanel('web.doris.be.stream_load.receive_bytes'),
        dorisPanel('web.doris.be.stream_load.load_rows'),
        dorisPanel('web.doris.be.memtable_flush_total'),
        dorisPanel('web.doris.be.memtable_flush_duration_us', 'Flush 耗时'),
        dorisPanel('web.doris.be.txn_request.begin'),
        dorisPanel('web.doris.be.txn_request.commit'),
        dorisPanel('web.doris.be.txn_request.rollback'),
      ], be.write, 9);

      this.beCompactionPanels = mergeCoreAndExtra([
        dorisPanel('web.doris.be.compaction_bytes_total.write'),
        dorisPanel('web.doris.be.compaction_bytes_total.read'),
        dorisPanel('web.doris.be.compaction_bytes_total.cumulative'),
        dorisPanel('web.doris.be.compaction_bytes_total.base'),
        dorisPanel('web.doris.be.tablet_cumulative_max_compaction_score'),
        dorisPanel('web.doris.be.tablet_base_max_compaction_score'),
      ], be.compaction, 9);

      this.beQueryPanels = mergeCoreAndExtra([
        dorisPanel('web.doris.be.query_scan_bytes'),
        dorisPanel('web.doris.be.query_scan_rows'),
        dorisPanel('web.doris.be.segment_read.segment_read_total'),
        dorisPanel('web.doris.be.segment_read.segment_row_total'),
      ], be.query, 6);

      this.beEnginePanels = toPanels(be.engine, 12);
      this.bePoolPanels = toPanels(be.pool, 15);
      this.beOtherPanels = toPanels(be.other, 9);
    } finally {
      this.discovering = false;
    }
  }
}
</script>

<style lang="scss" scoped>
.sm-page {
  padding: 4px 2px 20px;
}

.sm-group {
  margin-bottom: 28px;
  padding: 16px 16px 4px;
  background: #fff;
  border: 1px solid #e6ebf0;
  border-radius: 12px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.sm-group-head {
  margin-bottom: 14px;
  padding-bottom: 12px;
  border-bottom: 1px solid #eef2f6;
}

.sm-group-title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: #0f172a;
  letter-spacing: 0.01em;
}

.sm-group-desc {
  margin: 4px 0 0;
  font-size: 12px;
  color: #94a3b8;
}
</style>
