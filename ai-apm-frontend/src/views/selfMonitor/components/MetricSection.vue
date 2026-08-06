<template>
  <section class="sm-section">
    <header class="sm-section-head">
      <div>
        <h3 class="sm-section-title">{{ title }}</h3>
        <p v-if="desc" class="sm-section-desc">{{ desc }}</p>
      </div>
    </header>
    <div class="sm-grid">
      <metric-panel
        v-for="(panel, idx) in panels"
        :key="(panel.metric || panel.title || '') + '-' + idx"
        :spec="panel"
        :timeParams="timeParams"
        :height="panel.height || 240"
      />
      <div v-if="!panels.length && !loading" class="sm-empty">当前时间范围暂无指标</div>
    </div>
  </section>
</template>

<script lang="ts">
import { Vue, Component, Prop } from 'vue-property-decorator';
import MetricPanel from './MetricPanel.vue';
import { ChartPanelSpec } from '../shared';

@Component({ components: { MetricPanel } })
export default class MetricSection extends Vue {
  @Prop({ required: true }) public title!: string;
  @Prop({ default: '' }) public desc!: string;
  @Prop({ required: true }) public panels!: ChartPanelSpec[];
  @Prop({ required: true }) public timeParams!: { fromTime: string; toTime: string; interval?: number };
  @Prop({ default: false }) public loading!: boolean;
}
</script>

<style lang="scss" scoped>
.sm-section {
  margin-bottom: 22px;
}

.sm-section-head {
  margin-bottom: 12px;
}

.sm-section-title {
  margin: 0;
  font-size: 14px;
  font-weight: 650;
  color: #0f172a;
}

.sm-section-desc {
  margin: 4px 0 0;
  font-size: 12px;
  color: #94a3b8;
}

.sm-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

@media (max-width: 1200px) {
  .sm-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .sm-grid {
    grid-template-columns: 1fr;
  }
}

.sm-empty {
  grid-column: 1 / -1;
  padding: 28px;
  text-align: center;
  color: #94a3b8;
  font-size: 13px;
  background: #fff;
  border: 1px dashed #dbe3ea;
  border-radius: 10px;
}
</style>
