<template>
  <div class="overview-chart-group">
    <div class="ts-chart-group clear g-xs-12">
      <div class="ts-bar-chart g-xs-6">
        <div class="ts-chart-wrapper" v-loading="barLoading1 || parentLoading">
          <div class="chart-title">{{ $t('modules.views.appMonitor.trace.s_be3a103f', { value0: logLabel }) }}</div>
          <div class="chart-cont">
            <basic-chart
              :source="chartSource1"
              :showEmpty="showEmpty1"
              :showLegend="true"
              :minInterval="1"
              group="logs"
              :yAxisSplitNum="3"
              :textSmallMode="true"
              :interval="timeParams.interval"
              :axisClickEvent="($event) => chartClickHandle($event)"
              :colors='["#7A5FF3", "#F37370"]'
              :tooltipEnterable="false" />
          </div>
        </div>
      </div>
      <div class="ts-bar-chart g-xs-6">
        <div class="ts-chart-wrapper" v-loading="barLoading2 || parentLoading">
          <div class="chart-title">{{ severityChartTitle }}</div>
          <div class="chart-cont">
            <basic-chart
              :source="chartSource2"
              :showEmpty="showEmpty2"
              :showLegend="true"
              group="logs"
              :minInterval="1"
              :yAxisSplitNum="3"
              :textSmallMode="true"
              :interval="timeParams.interval"
              :axisClickEvent="($event) => chartClickHandle($event, 'error')"
              :tooltipEnterable="false" />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop } from 'vue-property-decorator';
import i18n from '@/i18n';
import BasicChart from '@/components/charts/basic-chart.vue';
import { toAsyncWait } from '@/utils/common';
import LogApi from '@/api/log';
import { getSeverityChartColor, sortSeverities } from '@/utils/logSeverity';
import dayjs from 'dayjs';

@Component({
  components: {
    BasicChart,
  },
})
export default class LogsOverviewChart extends Vue {
  @Prop({ default: () => ({}) }) private timeParams!: any;
  @Prop({ default: () => ({}) }) private query!: any;
  @Prop({ default: false }) private queryLoading!: boolean;
  @Prop({ default: false }) private searchInitLoading!: boolean;

  private barLoading1 = false;
  private barLoading2 = false;

  private chartSource1: any = [];
  private chartSource2: any = [];

  private showEmpty1 = false;
  private showEmpty2 = false;

  private volumeCounts: Record<string, number | null> = {};

  get parentLoading () {
    return this.queryLoading || this.searchInitLoading;
  }

  get logLabel () {
    return i18n.locale === 'en-US' ? 'Log' : '日志';
  }

  get severityChartTitle () {
    const level = i18n.t('modules.views.alarmCenter.eventDetail.s_3fea7ca7') as string;
    return i18n.locale === 'en-US' ? `${level} breakdown` : `${level}分布`;
  }

  public getVolumeCounts () {
    return this.volumeCounts;
  }

  public async getData () {
    const params = {
      ...this.timeParams,
      ...this.query,
    };
    await this.getLogVolumeGraph(params);
    this.getSeverityGraph(params);
    return this.volumeCounts;
  }

  private async getLogVolumeGraph (params: any) {
    this.barLoading1 = true;
    const { result, error } = await toAsyncWait(LogApi.getLogTrend(params));
    this.barLoading1 = false;
    if (!error) {
      const graphData = result?.data?.logCnts || {};
      this.volumeCounts = graphData;
      this.showEmpty1 = !Object.values(graphData).some((value) => value != null);
      if (!this.showEmpty1) {
        const hitsSource = Object.keys(graphData)
          .sort((a: string, b: string) => Number(a) - Number(b))
          .map((date) => ({
            key: dayjs(Number(date)).format('YYYY-MM-DD HH:mm'),
            value: graphData[date] ?? '-',
          }));
        this.chartSource1 = [
          {
            name: i18n.t('modules.views.appMonitor.relationMap.s_ae1e7b60') as string,
            nameKey: 'modules.views.appMonitor.relationMap.s_ae1e7b60',
            data: hitsSource,
            type: 'bar',
            stack: 'total',
          },
        ];
      } else {
        this.chartSource1 = [];
      }
    } else {
      this.volumeCounts = {};
    }
  }

  private async getSeverityGraph (params: any) {
    this.barLoading2 = true;
    const { result, error } = await toAsyncWait(LogApi.getLogTrend(params));
    this.barLoading2 = false;
    if (!error) {
      const graphData = result?.data?.severityCnts || {};
      this.showEmpty2 = !Object.values(graphData).some(
        (item: any) => item && typeof item === 'object' && Object.keys(item).length > 0,
      );
      if (!this.showEmpty2) {
        let severityNames: string[] = [];
        Object.values(graphData).forEach((item: any) => {
          if (item && typeof item === 'object') {
            severityNames.push(...Object.keys(item));
          }
        });
        severityNames = sortSeverities(severityNames);
        this.chartSource2 = severityNames.map((severity: string) => ({
          name: severity,
          color: getSeverityChartColor(severity),
          data: Object.entries(graphData)
            .map((item: any) => {
              const bucket = item[1] && typeof item[1] === 'object' ? item[1] : null;
              return {
                key: dayjs(Number(item[0])).format('YYYY-MM-DD HH:mm'),
                value: bucket && Object.prototype.hasOwnProperty.call(bucket, severity) ? bucket[severity] : '-',
              };
            })
            .sort((a: any, b: any) => new Date(a.key).valueOf() - new Date(b.key).valueOf()),
          type: 'bar',
          stack: 'total',
        }));
      } else {
        this.chartSource2 = [];
      }
    }
  }

  private chartClickHandle (params: { xAxisName: string }, type?: string) {
    if (this.barLoading1 || this.barLoading2 || this.queryLoading || this.searchInitLoading) {
      return;
    }
    this.$emit('chart-click', params.xAxisName, type);
  }
}
</script>

<style lang="scss" scoped>
.overview-chart-group {
  height: auto;

  .ts-chart-group {
    .ts-bar-chart {
      height: 260px;
      padding: 0 8px;
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
