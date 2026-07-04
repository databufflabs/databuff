<template>
  <div class="overview-chart-group">
    <div class="ts-chart-group clear g-xs-12">
      <div class="ts-bar-chart g-xs-4">
        <div class="ts-chart-wrapper"
          v-loading='barLoading1 || parentLoading'>
          <div class="chart-title">{{ $t('modules.views.appMonitor.trace.s_be3a103f', { value0: hasRequestAttrParams ? 'Span' : 'Trace' }) }}</div>
          <div class="chart-cont">
            <basic-chart
              :source="chartSource1"
              :showEmpty="showEmpty1"
              :showLegend="true"
              :minInterval="1"
              group='trace'
              :yAxisSplitNum="3"
              :textSmallMode="true"
              :interval="timeParams.interval"
              :axisClickEvent="($event) => chartClickHandle($event)"
              :colors='["#7A5FF3", "#F37370"]'
              :tooltipEnterable="false" />
          </div>
        </div>
      </div>
      <div class="ts-bar-chart g-xs-4">
        <div class="ts-chart-wrapper"
          v-loading='barLoading2 || parentLoading'>
          <div class="chart-title">{{ $t('modules.views.appMonitor.trace.s_5a980360', { value0: hasRequestAttrParams ? 'Span' : 'Trace' }) }}</div>
          <div class="chart-cont">
            <basic-chart
              :source="chartSource2"
              :showEmpty="showEmpty2"
              :showLegend="true"
              group='trace'
              :minInterval="1"
              :yAxisSplitNum="3"
              :textSmallMode="true"
              :interval="timeParams.interval"
              :axisClickEvent="($event) => chartClickHandle($event, 'error')"
              :colors="[
                '#ED3B3B', '#F99B3B', '#FFCC55', '#91c7ae',
                '#a0a7e6', '#c23531', '#2f4554', '#61a0a8',
                '#d48265', '#FF6A84', '#749f83', '#ca8622',
                '#bda29a', '#6e7074', '#546570', '#c4ccd3',
              ]"
              :tooltipEnterable="false" />
          </div>
        </div>
      </div>
      <div class="ts-bar-chart g-xs-4">
        <div class="ts-chart-wrapper"
          v-loading='barLoading3 || parentLoading'>
          <div class="chart-title">{{ $t('modules.views.appMonitor.trace.s_462e0c63', { value0: hasRequestAttrParams ? 'Span' : 'Trace' }) }}</div>
          <div class="chart-cont">
            <basic-chart
              :source="chartSource3"
              :showEmpty="showEmpty3"
              group='trace'
              :showLegend="true"
              :colors="['#2962FF']"
              :yAxisSplitNum="3"
              :textSmallMode="true"
              :interval="timeParams.interval"
              :axisClickEvent="($event) => chartClickHandle($event)"
              :tooltipEnterable="false" />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Watch, Prop } from 'vue-property-decorator';
import i18n from '@/i18n';
import BasicChart from '@/components/charts/basic-chart.vue';
import { toAsyncWait } from '@/utils/common'
import ApmApi from '@/api/apm';
import dayjs from 'dayjs';

@Component({
  components: {
    BasicChart,
  },
})
export default class OverviewChartGroup extends Vue {
  @Prop({ default: () => ({}) }) private timeParams!: any;
  @Prop({ default: () => ({}) }) private query!: any;
  @Prop({ default: false }) private queryLoading!: boolean;
  @Prop({ default: false }) private searchInitLoading!: boolean;
  @Prop({ default: false }) private hasRequestAttrParams!: boolean;

  private barLoading1 = false;
  private barLoading2 = false;
  private barLoading3 = false;

  private chartSource1: any = [];
  private chartSource2: any = [];
  private chartSource3: any = [];

  private showEmpty1 = false;
  private showEmpty2 = false;
  private showEmpty3 = false;

  private volumeCounts: Record<string, number | null> = {};
  
  get parentLoading () {
    return this.queryLoading || this.searchInitLoading
  }

  public getVolumeCounts () {
    return this.volumeCounts;
  }

  public async getData () {
    const params: any = {
      ...this.timeParams,
      ...this.query,
    }
    await this.getSpanRequestGraph(params)
    this.getSpanErrorGraph(params)
    this.getSpanResponseTimeGraph(params)
    return this.volumeCounts
  }

  private async getSpanRequestGraph (params: any) {
    this.barLoading1 = true;
    const { result, error } = await toAsyncWait(ApmApi.getSpanRequestGraph(params))
    this.barLoading1 = false;
    if (!error) {
      const graphData = result.data.callCnts || {}
      this.volumeCounts = graphData
      this.showEmpty1 = !Object.values(graphData).some((value) => value != null);
      if (!this.showEmpty1) {
        const hitsSource = Object.keys(graphData).sort((a: string, b: string) => Number(a) - Number(b)).map((date) => ({
          key: dayjs(Number(date)).format('YYYY-MM-DD HH:mm'),
          value: graphData[date] ?? '-',
        }))
        this.chartSource1 = [
          { name: i18n.t('modules.views.appMonitor.relationMap.s_ae1e7b60') as string, nameKey: 'modules.views.appMonitor.relationMap.s_ae1e7b60', data: hitsSource, type: 'bar', stack: 'total' },
        ];
      } else {
        this.chartSource1 = []
      }
    } else {
      this.volumeCounts = {}
    }
  }
  private async getSpanErrorGraph (params: any) {
    this.barLoading2 = true;
    const { result, error } = await toAsyncWait(ApmApi.getSpanErrorGraph(params))
    this.barLoading2 = false;
    if (!error) {
      const graphData = result.data.errorCnts || {}
      this.showEmpty2 = !Object.values(graphData).some(
        (item: any) => item && typeof item === 'object' && Object.keys(item).length > 0
      );
      if (!this.showEmpty2) {
        // 取全量的group，防止部分数据丢失
        let errsName: any = [];
        Object.values(graphData).forEach((item: any) => {
          if (item && typeof item === 'object') {
            errsName.push(...Object.keys(item))
          }
        });
        errsName = [...new Set(errsName)]
        const chartSource = errsName.map((group: string) => {
          return {
            name: group,
            data: Object.entries(graphData).map((item: any) => {
              const bucket = item[1] && typeof item[1] === 'object' ? item[1] : null
              return {
                key: dayjs(Number(item[0])).format('YYYY-MM-DD HH:mm'),
                value: bucket && Object.prototype.hasOwnProperty.call(bucket, group) ? bucket[group] : '-'
              }
            }).sort((a: any, b: any) => new Date(a.key).valueOf() - new Date(b.key).valueOf()),
            type: 'bar',
            stack: 'total',
          }
        });
        this.chartSource2 = chartSource;
      } else {
        this.chartSource2 = []
      }
    }
  }
  private async getSpanResponseTimeGraph (params: any) {
    this.barLoading3 = true;
    const { result, error } = await toAsyncWait(ApmApi.getSpanResponseTimeGraph(params))
    this.barLoading3 = false;
    if (!error) {
      const graphData = result.data.avgLatencys || {}
      this.showEmpty3 = !Object.values(graphData).some((value) => value != null);
      if (!this.showEmpty3) {
        const avgSource = Object.entries(graphData)
          .sort((a: any, b: any) => Number(a[0]) - Number(b[0]))
          .map((item: any) => ({
            key: dayjs(Number(item[0])).format('YYYY-MM-DD HH:mm'),
            value: item[1] ?? '-',
          }));
        this.chartSource3 = [{
          name: i18n.t('modules.views.appMonitor.cache.s_96a0c062') as string,
          nameKey: 'modules.views.appMonitor.cache.s_96a0c062',
          data: avgSource,
          unit: 'nanosecond',
          type: 'line',
        }];
      } else {
        this.chartSource3 = []
      }
    }
  }

  private chartClickHandle (params: { xAxisName: string }, type?: string) {
    const { xAxisName } = params
    // 正在加载中，不允许点击
    if (this.barLoading1 || this.barLoading2 || this.barLoading3 || this.queryLoading || this.searchInitLoading) {
      return
    }
    this.$emit('chart-click', xAxisName, type)
  }
}
</script>

<style lang="scss" scoped>
.overview-chart-group{
  height: auto;

  .ts-chart-group {
    .ts-bar-chart {
      height: 100%;
      height: 260px;
      padding: 0 8px;
    }
    .ts-overview-list {
      padding: 0 8px;
      margin-bottom: 16px;
      height: 230px;
    }
    .ts-chart-wrapper {
      height: 100%;
      background-color: var(--bg-color);
      padding: 16px 16px 0;
      position: relative;
      border: 1px solid var(--border-color-base);
      border-radius: 4px;
      .chart-title {
        font-size: 14px;
        line-height: 14px;
        user-select: none;
        color: var(--color-text-primary);
      }
      .chart-cont {
        height: calc(100% - 14px);
        padding-top: 6px;
        margin: 0 -10px;
        position: relative;
        overflow: hidden;
      }
    }
  }
}
</style>
