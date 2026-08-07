<template>
  <div class="sm-page">
    <summary-cards :cards="cards" :timeParams="timeParams" />

    <div class="sm-group">
      <div class="sm-group-head">
        <h2 class="sm-group-title">ingest</h2>
        <p class="sm-group-desc">入站 · 出站</p>
      </div>
      <metric-section
        title="核心指标"
        :panels="ingestPanels"
        :timeParams="timeParams"
      />
    </div>

    <div class="sm-group">
      <div class="sm-group-head">
        <h2 class="sm-group-title">Doris</h2>
        <p class="sm-group-desc">磁盘 · CPU · 内存 · 处理</p>
      </div>
      <metric-section
        title="核心指标"
        :panels="dorisPanels"
        :timeParams="timeParams"
      />
    </div>

    <div class="sm-group">
      <div class="sm-group-head">
        <h2 class="sm-group-title">web</h2>
        <p class="sm-group-desc">Portal 查询 · 进程 · Doris 连通</p>
      </div>
      <metric-section
        title="核心指标"
        :panels="webPanels"
        :timeParams="timeParams"
      />
    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Watch } from 'vue-property-decorator';
import SummaryCards from '../components/SummaryCards.vue';
import MetricSection from '../components/MetricSection.vue';
import { ChartPanelSpec, SummaryCardSpec } from '../shared';

function doris(metric: string, title: string, extra: Partial<ChartPanelSpec> = {}): ChartPanelSpec {
  return {
    metric,
    title,
    components: ['web'],
    groupBy: ['dim'],
    hint: '按 Host',
    value: 'gauge',
    ...extra,
  };
}

function web(metric: string, title: string, extra: Partial<ChartPanelSpec> = {}): ChartPanelSpec {
  return {
    metric,
    title,
    components: ['web'],
    groupBy: ['instance'],
    hint: '按实例',
    ...extra,
  };
}

@Component({ components: { SummaryCards, MetricSection } })
export default class SelfMonitorOverview extends Vue {
  private timeParams: any = {};

  private cards: SummaryCardSpec[] = [
    {
      title: '入站 TPS',
      metricPrefixes: ['ingest.otel.', 'ingest.sw.'],
      metricSuffixes: ['.req'],
      value: 'cnt',
      components: ['ingest'],
      asRate: true,
      rateUnit: '/s',
      digits: 1,
      tone: 'ok',
    },
    {
      title: '写出失败',
      metrics: ['ingest.write.fail'],
      metric: 'ingest.write.fail',
      value: 'cnt',
      components: ['ingest'],
      tone: 'warn',
    },
    {
      title: 'Doris 磁盘占用',
      metrics: ['web.doris.be.used_pct'],
      metric: 'web.doris.be.used_pct',
      value: 'gauge',
      components: ['web'],
      unit: '%',
      digits: 2,
      tone: 'default',
    },
    {
      title: '查询失败',
      metricPrefixes: ['web.query.'],
      metricSuffixes: ['.fail'],
      value: 'cnt',
      components: ['web'],
      tone: 'danger',
    },
  ];

  /** 入站 req/bytes/cost · 出站 drop/bytes/cost · 均按 trace / metric / log */
  private ingestPanels: ChartPanelSpec[] = [
    {
      title: '入站请求',
      metrics: [
        'ingest.otel.trace.req',
        'ingest.otel.metric.req',
        'ingest.otel.log.req',
      ],
      value: 'cnt',
      components: ['ingest'],
      groupBy: ['metric'],
      asRate: true,
      unit: '/s',
      hint: 'trace / metric / log',
      seriesNameMap: {
        'ingest.otel.trace.req': 'trace',
        'ingest.otel.metric.req': 'metric',
        'ingest.otel.log.req': 'log',
      },
    },
    {
      title: '入站字节',
      metrics: [
        'ingest.otel.trace.bytes',
        'ingest.otel.metric.bytes',
        'ingest.otel.log.bytes',
      ],
      value: 'cnt',
      components: ['ingest'],
      groupBy: ['metric'],
      asRate: true,
      unit: 'bytes/s',
      hint: 'trace / metric / log',
      seriesNameMap: {
        'ingest.otel.trace.bytes': 'trace',
        'ingest.otel.metric.bytes': 'metric',
        'ingest.otel.log.bytes': 'log',
      },
    },
    {
      title: '入站耗时',
      metrics: [
        'ingest.otel.trace.cost_ms',
        'ingest.otel.metric.cost_ms',
        'ingest.otel.log.cost_ms',
      ],
      value: 'avg',
      components: ['ingest'],
      groupBy: ['metric'],
      unit: 'ms',
      hint: 'trace / metric / log',
      seriesNameMap: {
        'ingest.otel.trace.cost_ms': 'trace',
        'ingest.otel.metric.cost_ms': 'metric',
        'ingest.otel.log.cost_ms': 'log',
      },
    },
    {
      title: '出站丢弃',
      metric: 'ingest.write.drop',
      value: 'cnt',
      components: ['ingest'],
      groupBy: ['dim'],
      hint: 'trace / metric / log',
      seriesNameMap: { trace: 'trace', metric: 'metric', log: 'log' },
    },
    {
      title: '出站字节',
      metric: 'ingest.write.bytes',
      value: 'cnt',
      unit: 'bytes/s',
      asRate: true,
      components: ['ingest'],
      groupBy: ['dim'],
      hint: 'trace / metric / log',
      seriesNameMap: { trace: 'trace', metric: 'metric', log: 'log' },
    },
    {
      title: '出站耗时',
      metric: 'ingest.write.cost_ms',
      value: 'avg',
      unit: 'ms',
      components: ['ingest'],
      groupBy: ['dim'],
      hint: 'trace / metric / log',
      seriesNameMap: { trace: 'trace', metric: 'metric', log: 'log' },
    },
  ];

  private dorisPanels: ChartPanelSpec[] = [
    doris('web.doris.be.used_pct', '磁盘占用', { unit: '%', groupBy: ['instance'] }),
    doris('web.doris.be.cpu', 'BE CPU', {
      groupBy: ['dim'],
      value: 'gauge',
      unit: '%',
      hint: '各 mode 占比',
    }),
    doris('web.doris.be.process_mem_bytes', '进程内存', { unit: 'bytes', groupBy: ['instance'] }),
    doris('web.doris.be.memtable_flush_duration_us', 'Flush 耗时', {
      unit: 'ms',
      scale: 0.001,
      groupBy: ['instance'],
    }),
    doris('web.doris.be.running_tasks', '运行任务', { groupBy: ['instance'] }),
    doris('web.doris.be.alive', 'BE Alive', { unit: '0/1', groupBy: ['instance'] }),
  ];

  private webPanels: ChartPanelSpec[] = [
    web('web.query.portal.req', 'Portal 查询量', { value: 'cnt' }),
    web('web.query.portal.cost_ms', 'Portal 耗时', { value: 'avg', unit: 'ms' }),
    web('web.query.portal.fail', 'Portal 失败', { value: 'cnt' }),
    web('web.system.cpu.usage', 'CPU', { value: 'gauge', unit: '%' }),
    web('web.system.memory.heap.used', 'Heap', { value: 'gauge', unit: 'bytes' }),
    web('web.doris.up', 'Doris 连通', { value: 'gauge', unit: '0/1', groupBy: ['instance'] }),
  ];

  @Watch('globalTimeV2', { deep: true })
  private watchGlobalTime() {
    this.refreshTime();
  }

  private mounted() {
    this.refreshTime();
    this.$eventBus.$on('GlobalRefresh', this, () => {
      this.onRefresh();
    });
  }

  private beforeDestroy() {
    this.$eventBus.$off('GlobalRefresh');
  }

  private onRefresh() {
    this.refreshTime();
  }

  private refreshTime() {
    const { fromTime, toTime, interval } = this.getGlobalTimeV2();
    this.timeParams = { fromTime, toTime, interval };
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
