<template>
  <div class="sm-page">
    <metric-section
      title="查询业务"
      desc="按业务域分行 · req → cost_ms → fail"
      :panels="queryPanels"
      :timeParams="timeParams"
    />

    <metric-section
      title="进程资源"
      desc="CPU / Heap / 线程 / GC"
      :panels="systemPanels"
      :timeParams="timeParams"
      :loading="discovering"
    />
  </div>
</template>

<script lang="ts">
import { Vue, Component, Watch } from 'vue-property-decorator';
import MetricSection from '../components/MetricSection.vue';
import { ChartPanelSpec, gcPanelsFromMetrics, listPlatformMetrics } from '../shared';

const DOMAINS: Array<{ key: string; label: string }> = [
  { key: 'trace', label: 'Trace' },
  { key: 'metric', label: 'Metric' },
  { key: 'log', label: 'Log' },
  { key: 'ai', label: 'AI' },
  { key: 'portal', label: 'Portal' },
  { key: 'alarm', label: 'Alarm' },
  { key: 'other', label: 'Other' },
];

function domainRow(domain: string, label: string): ChartPanelSpec[] {
  return [
    {
      metric: `web.query.${domain}.req`,
      title: `${label} req`,
      value: 'cnt',
      components: ['web'],
      hint: '按实例',
    },
    {
      metric: `web.query.${domain}.cost_ms`,
      title: `${label} cost_ms`,
      value: 'avg',
      unit: 'ms',
      components: ['web'],
      hint: '按实例',
    },
    {
      metric: `web.query.${domain}.fail`,
      title: `${label} fail`,
      value: 'cnt',
      components: ['web'],
      hint: '按实例',
    },
  ];
}

@Component({ components: { MetricSection } })
export default class SelfMonitorQuery extends Vue {
  private timeParams: any = {};
  private discovering = false;

  private queryPanels: ChartPanelSpec[] = DOMAINS.flatMap(d => domainRow(d.key, d.label));

  private readonly baseSystemPanels: ChartPanelSpec[] = [
    { metric: 'web.system.cpu.usage', title: 'CPU 使用率', value: 'gauge', unit: '%', hint: '按实例' },
    { metric: 'web.system.memory.heap.used', title: 'Heap Used', value: 'gauge', unit: 'bytes', hint: '按实例' },
    { metric: 'web.system.memory.heap.max', title: 'Heap Max', value: 'gauge', unit: 'bytes', hint: '按实例' },
    { metric: 'web.system.thread.count', title: '线程数', value: 'gauge', hint: '按实例' },
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
      const gc = await listPlatformMetrics(this.timeParams, {
        metricPrefixes: ['web.system.gc.'],
        components: ['web'],
      });
      this.systemPanels = [
        ...this.baseSystemPanels,
        ...gcPanelsFromMetrics(gc, 'web'),
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
