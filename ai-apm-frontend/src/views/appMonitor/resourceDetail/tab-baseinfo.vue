<template>
  <div class="baseinfo-cont">
    <div class="baseinfo-wrapper">
      
      <div class="chart-group">
        <div v-for='value,key in chartGroup' :key='key' class="chart-item br-4">
          <h3 class="fw-normal font-14 chart-title">{{ key | chartTitleFilter }}</h3>
          <basic-chart
            :showEmpty="!chartGroup[key].loading && !chartGroup[key].source.length"
            :key='key'
            :colors='chartGroup[key].colors'
            :showLegend='true'
            :compactGrid="true"
            :textSmallMode="true"
            :minInterval="1"
            :min="0"
            group='baseinfo'
            :yAxisSplitNum="3"
            :interval="timeParams.interval"
            :source='chartGroup[key].source'
            :tsSource='chartGroup[key].tsSource'
            @on-ts-tooltip-show='onTsTooltipShow'
          >
            <template slot='ts'>
              <ChartTsSlot v-if='chartGroup[key].tsSource' :current='currentTsItem' />
            </template>
          </basic-chart>
        </div>
        <template v-if='hasProfiling'>
          <div v-for='value,key in profilingChartGroup' :key='key' class="chart-item br-4">
            <h3 class="fw-normal font-14 chart-title">{{ key | chartTitleFilter }}</h3>
            <basic-chart
              :showEmpty="!profilingChartGroup[key].loading && !profilingChartGroup[key].source.length"
              :key='key'
              :colors='profilingChartGroup[key].colors'
              :showLegend='true'
              :compactGrid="true"
              :textSmallMode="true"
              :minInterval="1"
              :min="0"
              group='baseinfo'
              :yAxisSplitNum="3"
              :interval="timeParams.interval"
              :source='profilingChartGroup[key].source'
              :tsSource='profilingChartGroup[key].tsSource'
              @on-ts-tooltip-show='onTsTooltipShow'
            >
              <template slot='ts'>
                <ChartTsSlot v-if='profilingChartGroup[key].tsSource' :current='currentTsItem' />
              </template>  
            </basic-chart>
          </div>
        </template>
        <template v-for='value,key in chartGroup2'>
          <div :key='key' v-if='value.componentTypes.includes(componentType)' class="chart-item br-4">
            <h3 class="fw-normal font-14 chart-title">{{ key | chartTitleFilter }}</h3>
            <basic-chart
              :showEmpty="!chartGroup2[key].loading && !chartGroup2[key].source.length"
              :key='key'
              :colors='chartGroup2[key].colors'
              :showLegend='true'
              :compactGrid="true"
              :textSmallMode="true"
              :minInterval="1"
              :min="0"
              group='baseinfo'
              :yAxisSplitNum="3"
              :interval="timeParams.interval"
              :source='chartGroup2[key].source'
              :tsSource='chartGroup2[key].tsSource'
              @on-ts-tooltip-show='onTsTooltipShow'
            >
              <template slot='ts'>
                <ChartTsSlot v-if='chartGroup2[key].tsSource' :current='currentTsItem' />
              </template>  
            </basic-chart>
          </div>
        </template>
      </div>

      <div class="attribute-group mt-20">
        <h3 class="m-0 fw-normal font-14">{{ $t('modules.views.appMonitor.resourceDetail.s_c5ea2ca1') }}</h3>
        <div class="mt-10 attribute-group-wrapper">
          
          <div class="attribute-item">
            <label class="attribute-item-label">
              <span>{{ $t('modules.views.alarmCenter.eventDetail.s_34cab80c') }}</span>
            </label>
            <span class="">{{ current.resource || '-' }}</span>
          </div>
          <div class="attribute-item">
            <label class="attribute-item-label">
              <span>{{ $t('modules.views.appMonitor.resourceDetail.s_27405e34') }}</span>
            </label>
            <span class="">{{ componentType | RequestTypeFilter }}</span>
          </div>
          <div v-if='componentType === "service.http"' class="attribute-item">
            <label class="attribute-item-label">
              <span>{{ $t('modules.views.appMonitor.resourceDetail.s_e4af82e3') }}</span>
            </label>
            <span class="">{{ current.httpMethod || '-' }}</span>
          </div>
          <!-- <div v-if='componentType === "service.rpc"' class="attribute-item">
            <label class="attribute-item-label">
              <span>{{ $t('modules.views.appMonitor.resourceDetail.s_5b26b249') }}</span>
            </label>
            <span class="">{{ current.type || '-' }}</span>
          </div> -->

          <template v-if='componentType === "service.mq"'>
            <div class="attribute-item">
              <label class="attribute-item-label">
                <span>Topic</span>
              </label>
              <span class="">{{ current.topic || '-' }}</span>
            </div>
            <div class="attribute-item">
              <label class="attribute-item-label">
                <span>ConsumerGroup</span>
              </label>
              <span class="">{{ current.group || '-' }}</span>
            </div>
            <div class="attribute-item">
              <label class="attribute-item-label">
                <span>Partition</span>
              </label>
              <span class="">{{ current.partition || '-' }}</span>
            </div>
            <div class="attribute-item">
              <label class="attribute-item-label">
                <span>MQ Type</span>
              </label>
              <span class="">{{ current.type || '-' }}</span>
            </div>
            <div class="attribute-item">
              <label class="attribute-item-label">
                <span>Broker</span>
              </label>
              <span class="">{{ current.broker || '-' }}</span>
            </div>
          </template>

          <template v-if='componentType === "service.db"'>
            <div class="attribute-item">
              <label class="attribute-item-label">
                <span>{{ $t('modules.views.appMonitor.resourceDetail.s_84b916da') }}</span>
              </label>
              <span class="">{{ current.dbType || '-' }}</span>
            </div>
            <div class="attribute-item">
              <label class="attribute-item-label">
                <span>{{ $t('modules.views.alarmCenter.eventDetail.s_de9cc3dd') }}</span>
              </label>
              <span class="">{{ current.sqlOperation || '-' }}</span>
            </div>
            <div class="attribute-item">
              <label class="attribute-item-label">
                <span>{{ $t('modules.views.alarmCenter.eventDetail.s_5ccbbd01') }}</span>
              </label>
              <span class="">{{ current.sqlDatabase || '-' }}</span>
            </div>
          </template>

          <div class="attribute-item">
            <label class="attribute-item-label">
              <span>{{ $t('modules.views.appMonitor.resourceDetail.s_9f71eff6') }}</span>
            </label>
            <span v-if="current.service && current.serviceId" @click="viewServiceDetailHandle" class="blue cp">{{ current.service || '-' }}</span>
            <span v-else>{{ current.service || '-' }}</span>
          </div>

        </div>
      </div>
    </div>

  </div>
</template>
<script lang='ts'>
import { toAsyncWait, decodeRouteQuery } from '@/utils/common';
import i18n from '@/i18n';
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import EllipseChart from '@/views/appMonitor/serviceDetail/bar-stack.vue';
import ServiceApi from '@/api/service';
import ApmApi from '@/api/apm';
import dayjs from 'dayjs';
import { copy } from '@/utils/common';
import ChartTsSlot from '@/views/appMonitor/serviceAnalysis/chart-ts-slot.vue';

const ChartTitleFilter = (key: string) => {
  switch (key) {
    case 'call':
      return i18n.t('modules.views.appMonitor.resourceDetail.s_3025354a') as string;
    case 'error':
      return i18n.t('modules.views.appMonitor.resourceDetail.s_11085e19') as string;
    case 'response':
      return i18n.t('modules.views.appMonitor.relationMap.s_207c26c9') as string;
    case 'errorAna':
      return i18n.t('modules.views.appMonitor.resourceDetail.s_358adb19') as string;
    case 'thread':
      return i18n.t('modules.views.appMonitor.resourceDetail.s_44e03b37') as string;
    case 'duration':
      return i18n.t('modules.views.appMonitor.resourceDetail.s_b9bee898') as string;
    case 'avgReadRows':
      return i18n.t('modules.views.appMonitor.resourceDetail.s_5734b2db') as string;
    case 'avgUpdateRows':
      return i18n.t('modules.views.appMonitor.resourceDetail.s_d181886c') as string;
    case 'avgMqBodyLengths':
      return i18n.t('modules.views.appMonitor.resourceDetail.s_94351feb') as string;
    default:
      return '';
  }
}

@Component({
  components: {
    EllipseChart, ChartTsSlot
  },
  filters: {
    chartTitleFilter (key: string) {
      return ChartTitleFilter(key)
    }
  }
})
export default class TabBaseinfo extends Vue {
  @Prop({ default: {} }) private current!: any;
  @Prop({ default: '' }) private componentType!: any;
  @Prop({ default: {} }) private queryParams!: any;

  @Watch('current', { immediate: true })
  private onCurrentChange (val: any, oldVal: any) {
    if (val && val?.serviceId !== oldVal?.serviceId && val?.resource !== oldVal?.resource && this.isMounted) {
      this.fetchAllData();
    }
  }

  get hasProfiling () {
    return ['service.http', 'service.rpc', 'service.mq'].includes(this.componentType);
  }

  private isMounted = false;
  private showCharts = false;

  private timeParams = {
    fromTime: '',
    toTime: '',
    interval: 60,
  }

  private chartGroup: any = {
    call: {
      loading: true,
      source: [],
      tsSource: [],
      colors: ['#2962FF', '#00AFF4'],
    },
    error: {
      loading: true,
      source: [],
      tsSource: [],
      colors: ['#2962FF', '#F37370'],
    },
    response: {
      loading: true,
      source: [],
      tsSource: [],
      colors: ['#2962FF', '#00AFF4'],
    },
  }
  private chartGroup2: any = {
    avgMqBodyLengths: {
      loading: true,
      source: [],
      tsSource: [],
      unit: 'bytes/req',
      colors: ['#2962FF', '#00AFF4'],
      componentTypes: ['service.mq'],
    },
    avgReadRows: {
      loading: true,
      source: [],
      tsSource: [],
      unit: 'row/reqs',
      colors: ['#2962FF', '#00AFF4'],
      componentTypes: ['service.db'],
    },
    avgUpdateRows: {
      loading: true,
      source: [],
      tsSource: [],
      unit: 'row/reqs',
      colors: ['#2962FF', '#00AFF4'],
      componentTypes: ['service.db'],
    },
  }

  private profilingChartGroup: any = {
    errorAna: {
      loading: false,
      source: [],
      tsSource: [],
    },
    thread: {
      loading: false,
      source: [],
      tsSource: [],
    },
    duration: {
      loading: false,
      source: [],
      tsSource: [],
    },
  }
  private currentTsItem: any = null;

  get serviceId () {
    const sid = this.$route.query.sid as string || ''
    return decodeURIComponent(sid)
  }
  get serviceInstance () {
    const si = this.$route.query.si as string || ''
    return decodeURIComponent(si)
  }
  get srcServiceId () {
    const srcSid = this.$route.query.srcSid as string || ''
    return decodeURIComponent(srcSid)
  }
  get resource () {
    const endpoint = this.$route.query.endpoint as string || ''
    return decodeRouteQuery(endpoint)
  }

  private endpointMetricParams () {
    if (this.componentType === 'service.http') {
      return { url: this.resource }
    }
    return { resource: this.resource }
  }

  get inOutParamsByType () {
    switch (this.componentType) {
      case 'service.http':
        return { isIn: 1 }
      case 'service.mq':
        return { isIn: 1 }
      case 'service.rpc':
        return {}
      case 'service.db':
        return { isOut: 1 }
      case 'service.redis':
        return { osOut: 0 }
      default:
        return {}
    }
  }

  get isLoading () {
    return this.chartGroup.call.loading || this.chartGroup.error.loading ||
      this.chartGroup.response.loading || (this.hasProfiling ? (this.profilingChartGroup.errorAna.loading ||
      this.profilingChartGroup.thread.loading || this.profilingChartGroup.duration.loading) : false)
  }

  @Watch('isLoading')
  private onIsLoading (newVal: boolean) {
    if (!newVal) {
      this.$emit('on-loaded')
    }
  }

  private created () {
    this.$emit('on-created');
    this.resetTimeParams();
  }
  private mounted () {
    if (this.current?.serviceId ) {
      this.refresh();
    }
    this.isMounted = true;
  }

  public refresh () {
    this.fetchAllData();
  }

  private resetTimeParams () {
    const { fromTime, toTime, interval } = this.getGlobalTimeV2();
    this.timeParams = { fromTime, toTime, interval };
  }

  private fetchAllData () {
    this.resetTimeParams();
    this.getGraphSource();
    this.getGraphSourceByType();
    if (this.hasProfiling) {
      this.getAbnormalStats()
      this.getThreadPoolStats()
      this.getResourceStats()
    }
  }

  // 响应时间
  private async getGraphSource () {
    this.chartGroup.call.loading = true;
    this.chartGroup.error.loading = true;
    this.chartGroup.response.loading = true;
    const params: any = {
      ...this.timeParams,
      componentType: this.componentType,
      serviceId: this.serviceId,
      ...this.endpointMetricParams(),
      isIn: 1,
      graphStats: ['callCnts', 'errorCnts', 'avgLatencys'],
    }
    if (this.serviceInstance) {
      params.serviceInstance = this.serviceInstance
    }
    if (this.srcServiceId) {
      params.srcServiceId = this.srcServiceId
    }
    const { result, error } = await toAsyncWait(ApmApi.getServiceGraph(params))
    if (!error) {
      const data = (result || {}).data || {};
      data.callCnts = data.callCnts || {};
      data.errorCnts = data.errorCnts || {};
      data.avgLatencys = data.avgLatencys || {};

      // 重组时间key，防止丢失
      let _allDates = [];
      _allDates = [...Object.keys(data.callCnts), ...Object.keys(data.errorCnts)]
      _allDates = [...new Set(_allDates)].sort((a: string, b: string) => Number(a) - Number(b));
      const hitsSource = _allDates.map((date) => {
        const err = typeof data.errorCnts[date] === 'number' ? data.errorCnts[date] : 0
        return {
          key: dayjs(Number(date)).format('YYYY-MM-DD HH:mm'),
          value: typeof data.callCnts[date] === 'number' ? data.callCnts[date] - err : '-',
        }
      })
      const errorsSource = _allDates.map((date) => {
        return {
          key: dayjs(Number(date)).format('YYYY-MM-DD HH:mm'),
          value: data.errorCnts[date],
        }
      })
      if (hitsSource.length) {
        this.chartGroup.call.source = [
          { name: i18n.t('modules.views.appMonitor.resourceDetail.s_7c8b41ec') as string, nameKey: 'modules.views.appMonitor.resourceDetail.s_7c8b41ec', type: 'bar', stack: 'all', color: '#2962FF', data: hitsSource },
          { name: i18n.t('modules.views.appMonitor.resourceDetail.s_8592da3e') as string, nameKey: 'modules.views.appMonitor.resourceDetail.s_8592da3e', type: 'bar', stack: 'all', color: '#F37370', data: errorsSource },
        ];
      } else {
        this.chartGroup.call.source = [];
      }
      // v2.9.1 ++
      if (Array.isArray(data?.details?.callCnts)) {
        const tsSource = data?.details?.callCnts.filter((i: any) => i.abnormalStartTime && i.abnormalEndTime).map((i: any) => {
          const { abnormalStartTime, abnormalEndTime } = i;
          return {
            duration: [
              Number(abnormalStartTime),
              Number(abnormalEndTime)
            ],
            status: 'danger',
            info: {
              ...i
            }
          }
        });
        this.chartGroup.call.tsSource = tsSource;
      } else {
        this.chartGroup.call.tsSource = [];
      }

      const errsSource = Object.keys(data.errorCnts).sort((a: string, b: string) => Number(a) - Number(b)).map((date) => {
        return {
          key: dayjs(Number(date)).format('YYYY-MM-DD HH:mm'),
          value: data.errorCnts[date],
        }
      });
      if (errsSource.length) {
        this.chartGroup.error.source = [{ name: i18n.t('modules.views.appMonitor.resourceDetail.s_8592da3e') as string, nameKey: 'modules.views.appMonitor.resourceDetail.s_8592da3e', type: 'bar', color: '#F37370', data: errsSource }];
      } else {
        this.chartGroup.error.source = [];
      }
      // v2.9.1 ++
      if (Array.isArray(data?.details?.errorCnts)) {
        const tsSource = data?.details?.errorCnts.filter((i: any) => i.abnormalStartTime && i.abnormalEndTime).map((i: any) => {
          const { abnormalStartTime, abnormalEndTime } = i;
          return {
            duration: [
              Number(abnormalStartTime),
              Number(abnormalEndTime)
            ],
            status: 'danger',
            info: {
              ...i
            }
          }
        });
        this.chartGroup.error.tsSource = tsSource;
      } else {
        this.chartGroup.error.tsSource = [];
      }

      const avgSource = Object.entries(data.avgLatencys).sort((a: any, b: any) => Number(a[0]) - Number(b[0])).map((item: any) => {
        return {
          key: dayjs(Number(item[0])).format('YYYY-MM-DD HH:mm'),
          value: item[1],
        }
      });
      if (avgSource.length) {
        this.chartGroup.response.source = [{ name: i18n.t('modules.views.appMonitor.cache.s_96a0c062') as string, nameKey: 'modules.views.appMonitor.cache.s_96a0c062', type: 'line', unit: 'nanosecond', data: avgSource }];
      } else {
        this.chartGroup.response.source = [];
      }
      // v2.9.1 ++
      if (Array.isArray(data?.details?.avgLatencys)) {
        const tsSource = data?.details?.avgLatencys.filter((i: any) => i.abnormalStartTime && i.abnormalEndTime).map((i: any) => {
          const { abnormalStartTime, abnormalEndTime } = i;
          return {
            duration: [
              Number(abnormalStartTime),
              Number(abnormalEndTime)
            ],
            status: 'danger',
            info: {
              ...i
            }
          }
        });
        this.chartGroup.response.tsSource = tsSource;
      } else {
        this.chartGroup.response.tsSource = [];
      }
    }
    this.chartGroup.call.loading = false;
    this.chartGroup.error.loading = false;
    this.chartGroup.response.loading = false;
  }

  private async getGraphSourceByType () {
    const availKeys: string[] = [];
    for (const key in this.chartGroup2) {
      if (this.chartGroup2[key].componentTypes.includes(this.componentType)) {
        availKeys.push(key)
      }
    }
    const params: any = {
      ...this.inOutParamsByType,
      ...this.timeParams,
      componentType: this.componentType,
      graphStats: [...availKeys],
      serviceId: this.serviceId,
      ...this.endpointMetricParams(),
    }
    if (this.serviceInstance) {
      params.serviceInstance = this.serviceInstance
    }
    if (this.srcServiceId) {
      params.srcServiceId = this.srcServiceId
    }
    const { result, error } = await toAsyncWait(ServiceApi.getServiceCallGraphStats(params));
    
    if (!error && result?.data && Object.keys(result.data).length) {
      const { data = {} } = result || {};
      const { details = {} } = data || {};
      for (const key in data) {
        if (this.chartGroup2?.[key]) {
          this.chartGroup2[key].loaded = false;
          this.chartGroup2[key].source = [{
            name: ChartTitleFilter(key),
            type: 'line',
            unit: this.chartGroup2[key].unit,
            data: Object.entries(data[key] || {}).sort((a: any, b: any) => Number(a[0]) - Number(b[0])).map((item: any) => {
              return {
                key: dayjs(Number(item[0])).format('YYYY-MM-DD HH:mm'),
                value: item[1],
              }
            })
          }];
          // v2.9.1 ++
          if (Array.isArray(details?.[key]) && Object.entries(data[key] || {}).length) {
            const tsSource = details?.[key].filter((i: any) => i.abnormalStartTime && i.abnormalEndTime).map((i: any) => {
              const { abnormalStartTime, abnormalEndTime } = i;
              return {
                duration: [
                  Number(abnormalStartTime),
                  Number(abnormalEndTime)
                ],
                status: 'danger',
                info: {
                  ...i
                }
              }
            });
            if (this.chartGroup2[key]?.tsSource) {
              this.chartGroup2[key].tsSource = tsSource;
            }
          } else {
            if (this.chartGroup2[key]?.tsSource) {
              this.chartGroup2[key].tsSource = [];
            }
          }
        }
      }
    }
  }

  private async getAbnormalStats () {
    const params: any = {
      ...this.getMetricStatsPrams(),
      metric: 'service.exception',
      groupBys: ['exceptionName'],
      fields: ['sum(error)'],
    }
    this.profilingChartGroup.errorAna.loading = true;
    const { result, error } = await toAsyncWait(ServiceApi.getRequestMetricStats(params))
    if (!error) {
      const data = (result || {}).data || {};
      this.profilingChartGroup.errorAna.source = this.formatSourceData(data, params.groupBys, params.fields[0]);
      this.profilingChartGroup.errorAna.tsSource = this.formatTsSource(data);
    }
    this.profilingChartGroup.errorAna.loading = false;

  }

  private async getThreadPoolStats () {
    const params: any = {
      ...this.getMetricStatsPrams(),
      metric: 'service.thread.pool.cost',
      groupBys: ['threadPoolName', 'type'],
      fields: ['sum(sumDuration)/sum(cnt)'],
    }
    this.profilingChartGroup.thread.loading = true;
    const { result, error } = await toAsyncWait(ServiceApi.getRequestMetricStats(params))
    if (!error) {
      const data = (result || {}).data || {};
      this.profilingChartGroup.thread.source = this.formatSourceData(data, params.groupBys, params.fields[0], 'ns');
      this.profilingChartGroup.thread.tsSource = this.formatTsSource(data);
    }
    this.profilingChartGroup.thread.loading = false;
  }

  private async getResourceStats () {
    const params: any = {
      ...this.timeParams,
      componentType: this.componentType,
      serviceId: this.serviceId,
      ...this.endpointMetricParams(),
    }
    if (this.serviceInstance) {
      params.serviceInstance = this.serviceInstance
    }
    if (this.srcServiceId) {
      params.srcServiceId = this.srcServiceId
    }
    this.profilingChartGroup.duration.loading = true;
    const { result, error } = await toAsyncWait(ServiceApi.getRequestResourceStats(params))
    if (!error) {
      const data = (result || {}).data || {};
      this.profilingChartGroup.duration.source = this.formatSourceData(data, ['service'], 'avgLatency', 'ns');
      this.profilingChartGroup.duration.tsSource = this.formatTsSource(data);
    }
    this.profilingChartGroup.duration.loading = false;
  }

  private getMetricStatsPrams () {
    const params: any = {
      ...this.timeParams,
      componentType: this.componentType,
      database: 'apm_metric',
      filters: {
        serviceId: this.serviceId,
        rootResource: this.resource,
      },
    }
    if (this.serviceInstance) {
      params.filters.serviceInstance = this.serviceInstance
    }
    if (this.srcServiceId) {
      params.filters.srcServiceId = this.srcServiceId
    }
    return { ...params }
  }
  private formatSourceData (data: any[], groups: string[], field: string, unit?: string) {
    return data.filter(item => !!item?.values?.length).map(item => {
      const values: any[] = item.values || []
      const name = groups.map(g => (item.tags || {})[g] || '').filter(g => !!g).join(' ');
      return {
        name,
        unit: unit || '',
        data: values.map(([key, value]: any) => ({
          key: dayjs(Number(key)).format('YYYY-MM-DD HH:mm'),
          value,
        })),
      }
    })
  }

  private formatTsSource (data: any[]) {
    if (!Array.isArray(data)) {
      return []
    }
    const target = data.find((i: any) => Array.isArray(i.rootDetails) && i.rootDetails.length) || { rootDetails: [] };
    // v2.9.1 ++
    const tsSource = (target?.rootDetails || []).filter((i: any) => i.abnormalStartTime && i.abnormalEndTime).map((i: any) => {
      const { abnormalStartTime, abnormalEndTime } = i;
      return {
        duration: [
          Number(abnormalStartTime),
          Number(abnormalEndTime)
        ],
        status: 'danger',
        info: {
          ...i
        }
      }
    });
    return tsSource
  }

  // 查看服务详情
  private viewServiceDetailHandle () {
    this.$router.push({
      path: '/appMonitor/serviceDetail',
      query: {
        sn: encodeURIComponent(this.current?.service),
        sid: encodeURIComponent(this.current?.serviceId),
      }
    })
  }

  // 查看业务系统
  private onTsTooltipShow (row: any) {
    this.currentTsItem = row.info || null;
  }

}
</script>
<style lang='scss' scoped>
.baseinfo-cont {
  overflow: hidden;
  position: relative;

}
.chart-group {
  display: flex;
  flex-wrap: wrap;
  overflow: hidden;

  .chart-item {
    flex: 1 0 auto;
    width: calc( 33.33% - 16px );
    height: 208px;
    overflow: hidden;
    margin: 8px;
    padding: 35px 15px 15px;
    border: 1px solid var(--border-color-base);
    position: relative;
  }
  .chart-title {
    position: absolute;
    top: 15px;
    left: 15px;
    line-height: 1;
    margin: 0;
  }
}
.attribute-group {
  margin-left: 4px;
}
.attribute-group-wrapper {
  border: 1px solid var(--border-color-base);
  border-radius: 4px;
  padding: 16px 20px;
}
.attribute-item {
  display: flex;

  .attribute-item-label {
    width: 140px;
    margin-bottom: 12px;
  }
}
</style>