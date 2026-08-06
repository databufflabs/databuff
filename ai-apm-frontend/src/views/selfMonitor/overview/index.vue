<template>
  <div class="sm-page">
    <summary-cards :cards="cards" :timeParams="timeParams" />

    <div class="sm-group">
      <div class="sm-group-head">
        <h2 class="sm-group-title">ingest</h2>
        <p class="sm-group-desc">入口 · 写入 · 延迟</p>
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

function ingest(metric: string, title: string, extra: Partial<ChartPanelSpec> = {}): ChartPanelSpec {
  return {
    metric,
    title,
    components: ['ingest'],
    groupBy: ['instance'],
    hint: '按实例',
    ...extra,
  };
}

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

  /** 入口流量 + 写入量 + 延迟，各挑关键信号 */
  private ingestPanels: ChartPanelSpec[] = [
    ingest('ingest.otel.trace.req', 'Trace 入站', { value: 'cnt' }),
    ingest('ingest.write.bytes', '写出字节', { value: 'cnt', unit: 'bytes' }),
    ingest('ingest.write.cost_ms', '写出耗时', { value: 'avg', unit: 'ms' }),
    ingest('ingest.write.fail', '写出失败', { value: 'cnt' }),
    ingest('ingest.write.queue', '写出队列', { value: 'gauge' }),
    ingest('ingest.pipeline.trace.queue', 'Trace 积压', { value: 'gauge' }),
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
