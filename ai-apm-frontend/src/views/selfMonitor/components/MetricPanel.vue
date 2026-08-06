<template>
  <div v-loading="loading" class="sm-panel" :style="{ height: panelHeight + 'px' }">
    <div class="sm-panel-head">
      <div class="sm-panel-title">{{ displayTitle }}</div>
      <div class="sm-panel-meta">
        <span v-if="seriesHint">{{ seriesHint }}</span>
        <span v-if="spec.unit" class="sm-panel-unit">{{ spec.unit }}</span>
      </div>
    </div>
    <div class="sm-panel-body">
      <basic-chart
        :source="source"
        :showLegend="source.length > 1"
        :showEmpty="!loading && !source.length"
        :showAxisLabelCount="5"
        :grid="chartGrid"
        :legend="chartLegend"
        :colors="chartColors"
      />
    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import BasicChart from '@/components/charts/basic-chart.vue';
import {
  ChartPanelSpec,
  queryPlatformSeries,
  resolveGroupBy,
  resolveSingleMetric,
  shortMetricLabel,
} from '../shared';

const COLORS = [
  '#0f766e', '#2563eb', '#b45309', '#7c3aed',
  '#be123c', '#0369a1', '#4d7c0f', '#c2410c',
];

@Component({ components: { BasicChart } })
export default class MetricPanel extends Vue {
  @Prop({ required: true }) public timeParams!: { fromTime: string; toTime: string; interval?: number };
  @Prop({ required: true }) public spec!: ChartPanelSpec;
  @Prop({ default: 240 }) public height!: number;

  private loading = false;
  private source: any[] = [];
  private chartColors = COLORS;
  private chartGrid = { left: 44, right: 16, top: 28, bottom: 24 };
  private chartLegend = { top: 0, right: 4, itemWidth: 10, itemHeight: 8, textStyle: { fontSize: 11 } };

  get panelHeight() {
    return this.spec.height || this.height || 240;
  }

  get displayTitle() {
    if (this.spec.title) {
      return this.spec.title;
    }
    const m = resolveSingleMetric(this.spec);
    return m ? shortMetricLabel(m) : '指标';
  }

  get seriesHint() {
    if (this.spec.hint) {
      return this.spec.hint;
    }
    const g = resolveGroupBy(this.spec);
    if (g.length === 1 && g[0] === 'instance') {
      return '按实例';
    }
    if (g.length === 1 && g[0] === 'dim') {
      return '按维度';
    }
    return g.map(x => `按${x}`).join(' · ');
  }

  @Watch('timeParams', { deep: true })
  private onTime() {
    this.load();
  }

  @Watch('spec', { deep: true })
  private onSpec() {
    this.load();
  }

  private mounted() {
    this.load();
  }

  private async load() {
    if (!this.timeParams?.fromTime || !this.timeParams?.toTime) {
      return;
    }
    this.loading = true;
    try {
      this.source = await queryPlatformSeries(this.timeParams, this.spec);
    } finally {
      this.loading = false;
    }
  }
}
</script>

<style lang="scss" scoped>
.sm-panel {
  background: #fff;
  border: 1px solid #e6ebf0;
  border-radius: 10px;
  padding: 12px 14px 6px;
  display: flex;
  flex-direction: column;
  min-width: 0;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.sm-panel-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 2px;
  flex: none;
}

.sm-panel-title {
  font-size: 13px;
  font-weight: 600;
  color: #0f172a;
  letter-spacing: 0.01em;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sm-panel-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
  font-size: 11px;
  color: #94a3b8;
}

.sm-panel-unit {
  padding: 1px 6px;
  border-radius: 999px;
  background: #f1f5f9;
  color: #64748b;
}

.sm-panel-body {
  flex: 1;
  min-height: 0;
}
</style>
