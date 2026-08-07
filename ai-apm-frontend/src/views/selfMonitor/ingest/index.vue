<template>
  <div class="sm-page">
    <metric-section
      title="协议入站"
      desc="Trace / Metric / Log 各一行 · req → bytes → cost_ms"
      :panels="inboundPanels"
      :timeParams="timeParams"
    />

    <metric-section
      title="Pipeline"
      desc="每行一类 · req（event 数）→ cost_ms（入队到处理完）→ drop"
      :panels="pipelinePanels"
      :timeParams="timeParams"
    />

    <metric-section
      title="写出 Stream Load"
      desc="每行一类 · 写出字节 → 写出耗时 → 写出丢弃"
      :panels="writePanels"
      :timeParams="timeParams"
    />

    <metric-section
      title="Trace 组装"
      :panels="tracePanels"
      :timeParams="timeParams"
    />

    <metric-section
      title="进程资源"
      desc="CPU / Heap / 线程 / GC"
      :panels="systemPanels"
      :timeParams="timeParams"
      :loading="discovering"
    />

    <metric-section
      title="集群状态"
      desc="成员 / Leader / 模式生效 · 折线按实例"
      :panels="statusPanels"
      :timeParams="timeParams"
    />

    <metric-section
      title="转发出站"
      desc="每个 stream 指标单独成图"
      :panels="forwardOutPanels"
      :timeParams="timeParams"
      :loading="discovering"
    />

    <metric-section
      title="转发入站"
      :panels="forwardInPanels"
      :timeParams="timeParams"
      :loading="discovering"
    />

    <metric-section
      title="转发丢弃"
      :panels="dropPanels"
      :timeParams="timeParams"
      :loading="discovering"
    />
  </div>
</template>

<script lang="ts">
import { Vue, Component, Watch } from 'vue-property-decorator';
import MetricSection from '../components/MetricSection.vue';
import {
  ChartPanelSpec,
  gcPanelsFromMetrics,
  listPlatformMetrics,
  panelsFromMetrics,
  shortMetricLabel,
} from '../shared';

function valueForClusterMetric(metric: string): ChartPanelSpec['value'] {
  if (metric.endsWith('.cost_ms')) {
    return 'avg';
  }
  if (metric.endsWith('.bytes')) {
    return 'cnt';
  }
  return 'cnt';
}

/** OTel: one row per signal — req, bytes, cost_ms. */
function otelInboundPanels(): ChartPanelSpec[] {
  const signals: Array<{ key: string; label: string }> = [
    { key: 'trace', label: 'Trace' },
    { key: 'metric', label: 'Metric' },
    { key: 'log', label: 'Log' },
  ];
  const panels: ChartPanelSpec[] = [];
  for (const s of signals) {
    const isTrace = s.key === 'trace';
    panels.push(
      {
        metric: `ingest.otel.${s.key}.req`,
        title: `${s.label} req`,
        value: 'cnt',
        components: ['ingest'],
        hint: '按实例',
        ...(isTrace ? { asRate: true, unit: '/s' } : {}),
      },
      {
        metric: `ingest.otel.${s.key}.bytes`,
        title: `${s.label} bytes`,
        value: 'cnt',
        unit: isTrace ? 'bytes/s' : 'bytes',
        components: ['ingest'],
        hint: '按实例',
        ...(isTrace ? { asRate: true } : {}),
      },
      {
        metric: `ingest.otel.${s.key}.cost_ms`,
        title: `${s.label} cost_ms`,
        value: 'avg',
        unit: 'ms',
        components: ['ingest'],
        hint: '按实例',
      },
    );
  }
  return panels;
}

/** Pipeline: one row per type — req, cost_ms, drop. */
function pipelineTypePanels(): ChartPanelSpec[] {
  const types: Array<{ key: string; label: string }> = [
    { key: 'trace', label: 'Trace' },
    { key: 'metric', label: 'Metric' },
    { key: 'aggregate', label: 'Aggregate' },
  ];
  const panels: ChartPanelSpec[] = [];
  for (const t of types) {
    panels.push(
      {
        metric: `ingest.pipeline.${t.key}.req`,
        title: `${t.label} req`,
        value: 'cnt',
        components: ['ingest'],
        hint: '按实例 · event 数',
      },
      {
        metric: `ingest.pipeline.${t.key}.cost_ms`,
        title: `${t.label} cost_ms`,
        value: 'avg',
        unit: 'ms',
        components: ['ingest'],
        hint: '按实例 · 入队→处理完',
      },
      {
        metric: `ingest.pipeline.${t.key}.drop`,
        title: `${t.label} drop`,
        value: 'cnt',
        components: ['ingest'],
        hint: '按实例',
      },
    );
  }
  return panels;
}

/** Write Stream Load: one row per signal — bytes, cost_ms, drop. */
function writeSignalPanels(): ChartPanelSpec[] {
  const signals: Array<{ key: 'trace' | 'metric' | 'log'; label: string }> = [
    { key: 'trace', label: 'Trace' },
    { key: 'metric', label: 'Metric' },
    { key: 'log', label: 'Log' },
  ];
  const panels: ChartPanelSpec[] = [];
  for (const s of signals) {
    panels.push(
      {
        metric: 'ingest.write.bytes',
        title: `${s.label} 写出字节`,
        value: 'cnt',
        unit: 'bytes/s',
        asRate: true,
        components: ['ingest'],
        dims: [s.key],
        groupBy: ['instance'],
        hint: '按实例',
      },
      {
        metric: 'ingest.write.cost_ms',
        title: `${s.label} 写出耗时`,
        value: 'avg',
        unit: 'ms',
        components: ['ingest'],
        dims: [s.key],
        groupBy: ['instance'],
        hint: '按实例',
      },
      {
        metric: 'ingest.write.drop',
        title: `${s.label} 写出丢弃`,
        value: 'cnt',
        components: ['ingest'],
        dims: [s.key],
        groupBy: ['instance'],
        hint: '按实例',
      },
    );
  }
  return panels;
}

@Component({ components: { MetricSection } })
export default class SelfMonitorIngest extends Vue {
  private timeParams: any = {};
  private discovering = false;
  private inboundPanels: ChartPanelSpec[] = otelInboundPanels();
  private pipelinePanels: ChartPanelSpec[] = pipelineTypePanels();
  private writePanels: ChartPanelSpec[] = writeSignalPanels();
  private forwardOutPanels: ChartPanelSpec[] = [];
  private forwardInPanels: ChartPanelSpec[] = [];
  private dropPanels: ChartPanelSpec[] = [];

  private statusPanels: ChartPanelSpec[] = [
    { metric: 'ingest.cluster.member.count', title: '成员数', value: 'gauge' },
    { metric: 'ingest.cluster.leader', title: '本节点 Leader', value: 'gauge', unit: '0/1' },
    { metric: 'ingest.cluster.effective', title: '集群模式生效', value: 'gauge', unit: '0/1' },
  ];

  private tracePanels: ChartPanelSpec[] = [
    { metric: 'ingest.trace.assembly.pending', title: '组装待处理', value: 'gauge' },
    { metric: 'ingest.trace.drop.ignore', title: 'Ignore 丢弃', value: 'cnt' },
  ];

  private readonly baseSystemPanels: ChartPanelSpec[] = [
    { metric: 'ingest.system.cpu.usage', title: 'CPU 使用率', value: 'gauge', unit: '%' },
    { metric: 'ingest.system.memory.heap.used', title: 'Heap Used', value: 'gauge', unit: 'bytes' },
    { metric: 'ingest.system.memory.heap.max', title: 'Heap Max', value: 'gauge', unit: 'bytes' },
    { metric: 'ingest.system.thread.count', title: '线程数', value: 'gauge' },
  ];

  private systemPanels: ChartPanelSpec[] = [...this.baseSystemPanels];

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
      const [fwd, fwdIn, drop, gc] = await Promise.all([
        listPlatformMetrics(this.timeParams, {
          metricPrefixes: ['ingest.cluster.forward.'],
          components: ['ingest'],
        }),
        listPlatformMetrics(this.timeParams, {
          metricPrefixes: ['ingest.cluster.forward.in.'],
          components: ['ingest'],
        }),
        listPlatformMetrics(this.timeParams, {
          metricPrefixes: ['ingest.cluster.drop.'],
          components: ['ingest'],
        }),
        listPlatformMetrics(this.timeParams, {
          metricPrefixes: ['ingest.system.gc.'],
          components: ['ingest'],
        }),
      ]);
      const outOnly = fwd.filter(m => !m.includes('.forward.in.'));
      this.forwardOutPanels = panelsFromMetrics(outOnly, {
        components: ['ingest'],
        titleFn: shortMetricLabel,
      }).map(p => ({
        ...p,
        value: valueForClusterMetric(p.metric || ''),
        unit: (p.metric || '').endsWith('.cost_ms') ? 'ms' : undefined,
      }));
      this.forwardInPanels = panelsFromMetrics(fwdIn, {
        value: 'cnt',
        components: ['ingest'],
        titleFn: shortMetricLabel,
      });
      this.dropPanels = panelsFromMetrics(drop, {
        value: 'cnt',
        components: ['ingest'],
        titleFn: shortMetricLabel,
      });
      this.systemPanels = [
        ...this.baseSystemPanels,
        ...gcPanelsFromMetrics(gc, 'ingest'),
      ];
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
</style>
