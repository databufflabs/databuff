<template>
  <div class="apm-table-cont">
    <db-table
      showSetting
      :queryApi='queryApi'
      :queryParams='tableQueryParams'
      :offsetMode='true'
      :columnConfig='getCloumns'
      :autoRefresh='false'
      @on-table-inited='tableInitedHandle'
      @on-columns-inited='columnsInitedHandle'
      @on-columns-change='columnsChangeHandle'
      @sort-change='getData'
      :formatFunc='formatFunc'
      tableKey='SERVICE_ANALYSIS_REQUEST'
      ref='listTable'>

      <template slot='resource' slot-scope="{ row }">
        <div class="flex-h-jc">
          <span @click="viewResourceDetailHandle(row)" class="db-blue cphu flex-1 ell">{{ row.resource || '-' }}</span>
          <span @click.stop="addQueryHandle(row)" class="db-icon db-icon-filter describe cphl mr-5" :class="{ 'action-disabled': queryName && row.resource === queryName }" :title="$t('modules.views.appMonitor.serviceAnalysis.s_ab1a54fb')"></span>
        </div>
      </template>

      <template slot='rate' slot-scope="{ row }">
        <el-popover trigger='hover' placement="top">
          <div class="font-12">
            <div class="font-14 fw-500 mb-16">{{ $t('modules.views.appMonitor.resourceDetail.s_acccc0cc') }}</div>
            <div class="flex-h mb-10 w-240">
              <div class="flex-none w-60">{{ $t('modules.views.appMonitor.resourceDetail.s_049722b4') }}</div>
              <div class="flex-1 mr-6">
                <div class="height-6 bg-green" :style='{ width: row.normalPercent + "%" }'></div>
              </div>
              <div class="flex-none w-50 ell tr">{{ row.normalPercent / 100 | PercentFilter }}</div>
              <div class="flex-none w-50 ell tr">{{ row.normalCnt | NumberFilter }}</div>
            </div>
            <div class="flex-h mb-10 w-240">
              <div class="flex-none w-60">{{ $t('modules.views.appMonitor.resourceDetail.s_d39c530f') }}</div>
              <div class="flex-1 mr-6">
                <div class="height-6 bg-yellow" :style='{ width: row.slowPercent + "%" }'></div>
              </div>
              <div class="flex-none w-50 ell tr">{{ row.slowPercent / 100 | PercentFilter }}</div>
              <div class="flex-none w-50 ell tr">{{ row.slowCnt | NumberFilter }}</div>
            </div>
            <div class="flex-h w-240">
              <div class="flex-none w-60">{{ $t('modules.views.appMonitor.resourceDetail.s_08736f40') }}</div>
              <div class="flex-1 mr-6">
                <div class="height-6 bg-red" :style='{ width: row.errPercent + "%" }'></div>
              </div>
              <div class="flex-none w-50 ell tr">{{ row.errPercent / 100 | PercentFilter }}</div>
              <div class="flex-none w-50 ell tr">{{ row.errCnt | NumberFilter }}</div>
            </div>
          </div>
          <div slot="reference" class="process-info pt-5 pb-5 cp">
            <span class="process-info-item bg-green" :style='{ width: row.normalPercent + "%" }'></span>
            <span class="process-info-item bg-yellow" :style='{ width: row.slowPercent + "%" }'></span>
            <span class="process-info-item bg-red" :style='{ width: row.errPercent + "%" }'></span>
          </div>
        </el-popover>
      </template>

      <template slot='callCnt' slot-scope="{ row }">
        <!-- 添加鼠标移入移出事件，触发悬浮框展示 -->
        <div class="flex-h">
          <div>
            <span>{{ row.callCnt | NumberFilter }}</span>
            <div style='width: 54px;'>
              <el-progress :width='54'
                :percentage="row.progressValue && row.progressValue.callCnt || 0" stroke-width="2"
                :show-text="false" stroke-linecap='butt'
                :class='["vm", "" ]'></el-progress>
            </div>
          </div>
          <span @mouseenter="showChartTooltip($event, row, 'callCnt')" @mouseleave="hideChartTooltip" class="el-icon el-icon-s-data information cphl font-16 ml-15"></span>
        </div>
      </template>

      <template slot='avgLatency' slot-scope="{ row }">
        <div class="flex-h">
          <div>
            <span>{{ row.avgLatency | NsFilter }}</span>
            <div style='width: 54px;'>
              <el-progress :width='54'
                :percentage="row.progressValue && row.progressValue.avgLatency || 0" stroke-width="2"
                :show-text="false" stroke-linecap='butt'
                :class='["vm", "" ]'></el-progress>
            </div>
          </div>
          <span @mouseenter="showChartTooltip($event, row, 'avgLatency')" @mouseleave="hideChartTooltip" class="el-icon el-icon-s-data information cphl font-16 ml-15"></span>
        </div>
      </template>

      <template slot='errRate' slot-scope="{ row }">
        <div class="flex-h">
          <div>
            <el-progress type="circle" :width='20'
              :percentage="row.progressValue && row.progressValue.errRate || 0" :stroke-width="3"
              :show-text="false" stroke-linecap='butt'
              class="vm mr-5" status='exception'></el-progress>
            <span>{{ row.errRate | PercentFilter }}</span>
          </div>
          <span @mouseenter="showChartTooltip($event, row, 'errorRate')" @mouseleave="hideChartTooltip" class="el-icon el-icon-s-data information cphl font-16 ml-15"></span>
        </div>
      </template>
        
    </db-table>

    <div v-if='tooltipModal' :style='tooltipPosition' class="chart-trend-tooltip">
      <div class="tooltip-chart-title ell">
        <span>{{ tooltipChart.titleKey ? $t(tooltipChart.titleKey) : tooltipChart.title }}</span>
        <span v-if='tooltipChart.describe' class="font-12 describe ell ml-10">({{ tooltipChart.describeKey ? $t(tooltipChart.describeKey) : tooltipChart.describe }})</span>
      </div>
      <div class="tooltip-chart-wrapper" v-loading='tooltipChart.loading'>
        <basic-chart
          ref="alarmChart"
          :source="tooltipChart.source"
          :minInterval="1"
          :min="0"
          :showLegend="false"
          :compactGrid="true"
          :textSmallMode="true"
          :yAxisSplitNum="3"
          :showEmpty="!tooltipChart.loading && !tooltipChart.source.length"/>
      </div>
    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Watch, Prop } from 'vue-property-decorator'
import BasicChart from '@/components/charts/basic-chart.vue';
import i18n from '@/i18n';
import ApmApi from '@/api/apm'
import axios from 'axios';
import { toAsyncWait } from '@/utils/common';
import dayjs from 'dayjs';

@Component({ components: { BasicChart } })
export default class ServiceTable extends Vue {
  @Prop({ default: {} }) private queryParams!: any;
  @Prop({ default: '' }) private componentType!: string;

  public $refs!: {
    listTable: any;
  };

  private columnConfig: any[] = [
    { field: 'resource', prop: 'resource', label: i18n.t('modules.views.alarmCenter.eventDetail.s_34cab80c') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_34cab80c', slot: 'resource', minWidth: 150, defaultShow: true },
    { field: 'datasource', prop: 'datasource', label: i18n.t('modules.views.appMonitor.service.s_a094e5b7') as string, labelKey: 'modules.views.appMonitor.service.s_a094e5b7', width: 120, defaultShow: true },
    { field: 'rate', prop: 'rate', slot: 'rate', label: i18n.t('modules.views.appMonitor.resourceDetail.s_acccc0cc') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_acccc0cc', width: 120, defaultShow: true },
    { field: 'serviceName', prop: 'serviceName', label: i18n.t('modules.views.alarmCenter.alarm.s_8f3747c0') as string, labelKey: 'modules.views.alarmCenter.alarm.s_8f3747c0', type: 'service', minWidth: 120, defaultShow: true, handleClick: this.showDetailHandle },

    // RPC请求 service.rpc
    // { field: 'type', prop: 'type', label: i18n.t('modules.views.appMonitor.serviceAnalysis.s_5b26b249') as string, labelKey: 'modules.views.appMonitor.serviceAnalysis.s_5b26b249', minWidth: 120, defaultShow: true, columnType: 'service.rpc' },
    // MQ消费 service.mq
    { field: 'topic', prop: 'topic', label: 'Topic', minWidth: 120, defaultShow: true, columnType: 'service.mq' },
    { field: 'group', prop: 'group', label: 'ConsumerGroup', minWidth: 120, defaultShow: true, columnType: 'service.mq' },
    { field: 'partition', prop: 'partition', label: 'Partition', minWidth: 120, defaultShow: true, columnType: 'service.mq' },
    { field: 'type', prop: 'type', label: 'MQ Type', minWidth: 120, defaultShow: true, columnType: 'service.mq' },
    // SQL调用 service.db
    { field: 'dbType', prop: 'dbType', label: i18n.t('modules.views.appMonitor.serviceAnalysis.s_84b916da') as string, labelKey: 'modules.views.appMonitor.serviceAnalysis.s_84b916da', minWidth: 120, defaultShow: true, columnType: 'service.db' },
    { field: 'sqlOperation', prop: 'sqlOperation', label: i18n.t('modules.views.alarmCenter.eventDetail.s_de9cc3dd') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_de9cc3dd', minWidth: 120, defaultShow: true, columnType: 'service.db' },
    { field: 'avgDelay', prop: 'avgDelay', label: i18n.t('modules.views.appMonitor.resourceDetail.s_8877d7fc') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_8877d7fc', unit: 'ns', minWidth: 120, defaultShow: true, columnType: 'service.mq' },

    { field: 'callCnt', prop: 'callCnt', label: i18n.t('modules.views.appMonitor.cache.s_8bc42b53') as string, labelKey: 'modules.views.appMonitor.cache.s_8bc42b53', slot: 'callCnt', unit: 'count', minWidth: 100, defaultShow: true, type: 'progress', sortable: true, defaultSort: 'desc', },
    { field: 'reqRate', prop: 'reqRate', label: i18n.t('modules.views.appMonitor.external.s_c0283020') as string, labelKey: 'modules.views.appMonitor.external.s_c0283020', unit: 'count', lessZeroOneKey: 'callCnt', minWidth: 100, defaultShow: true, suffix: i18n.t('modules.views.appMonitor.database.s_40b291ad') as string, type: 'progress', sortable: true },
    { field: 'errCnt', prop: 'errCnt', label: i18n.t('modules.views.appMonitor.resourceDetail.s_f9e62864') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_f9e62864', unit: 'count', minWidth: 100, defaultShow: false, type: 'progress', sortable: true },
    { field: 'errRate', prop: 'errRate', label: i18n.t('modules.views.appMonitor.cache.s_0c8524d7') as string, labelKey: 'modules.views.appMonitor.cache.s_0c8524d7', slot: 'errRate', unit: 'percent', lessZeroOneKey: 'errCnt', minWidth: 100, defaultShow: true, type: 'progress', progressDirection: 'horizontal', progressType: 'circle', progressStatus: 'exception', progressBarWidth: 3, progressMax: 1, sortable: true },
    { field: 'avgLatency', prop: 'avgLatency', label: i18n.t('modules.views.appMonitor.cache.s_96a0c062') as string, labelKey: 'modules.views.appMonitor.cache.s_96a0c062', slot: 'avgLatency', unit: 'ns', minWidth: 120, defaultShow: true, type: 'progress', sortable: true },
    { field: 'maxLatency', prop: 'maxLatency', label: i18n.t('modules.views.appMonitor.external.s_3bff553d') as string, labelKey: 'modules.views.appMonitor.external.s_3bff553d', unit: 'ns', minWidth: 120, defaultShow: true, type: 'progress', sortable: true },
  ];

  get getCloumns () {
    return this.columnConfig.filter((i) => !i.columnType || i.columnType === this.componentType)
  }

  get getBasicServiceMap () {
    return this.$store.getters['Service/basicServiceMap']
  }

  private queryApi = ApmApi.getEndpointList;

  get tableQueryParams () {
    const query: any = { ...this.queryParams }
    query.serviceId = query.sid
    query.serviceInstance = query.si;
    if (query.srcSid) {
      query.srcServiceId = query.srcSid;
    }
    delete query.srcSid;
    delete query.si
    delete query.sid
    return {
      ...query,
      componentType: this.componentType,
      showFields: [...this.showFields],
    }
  }

  private tooltipModal = false;
  private tooltipChart: any = {
    loading: false,
    source: [],
    title: '',
    timer: null,
    cancelTokenSource: null,
  }
  private tooltipPosition = {
    left: '',
    top: '',
  }

  private columnFetchFieldMap: any = {
    resource: ['resource', 'serviceName', 'serviceId'],
    datasource: ['datasource'],
    rate: ['callCnt', 'slowCnt', 'errCnt'],
    serviceName: ['serviceName', 'serviceId'],
    indices: ['indices'],
    method: ['method'],
    topic: ['topic'],
    group: ['group'],
    partition: ['partition'],
    type: ['type'],
    dbType: ['dbType'],
    sqlOperation: ['sqlOperation'],
    avgDelay: ['avgDelay'],
    callCnt: ['callCnt', 'resource'],
    reqRate: ['reqRate', 'callCnt'],
    errCnt: ['errCnt'],
    errRate: ['errRate', 'errCnt'],
    avgLatency: ['avgLatency'],
    maxLatency: ['maxLatency'],
  };
  private columnFetchFields = Object.keys(this.columnFetchFieldMap);
  private showFields: string[] = [];

  private columnsInitedHandle (data: any[]) {
    const fields = data.map((i: any) => i.field).filter((i: any) => this.columnFetchFields.includes(i));
    this.showFields = [...new Set(fields.map(t => this.columnFetchFieldMap[t]).flat())].sort();
  }
  private columnsChangeHandle (data: any[]) {
    const prevShowFields = [...this.showFields];
    const fields = data.map((i: any) => i.field).filter((i: any) => this.columnFetchFields.includes(i));
    this.showFields = [...new Set(fields.map(t => this.columnFetchFieldMap[t]).flat())].sort();
    if (this.showFields.some((i: any) => !prevShowFields.includes(i))) {
      this.getData()
    }
  }

  private showDetailHandle (row: any) {
    this.$router.push({
      path: '/appMonitor/serviceDetail',
      query: {
        sn: encodeURIComponent(row.serviceName),
        sid: encodeURIComponent(row.serviceId),
      }
    })
  }

  private tableInitedHandle () {
    this.$emit('on-table-inited')
  }

  private formatFunc (data: any) {
    data.forEach((i: any) => {
      const { service_type, type } = (this.getBasicServiceMap || {})[i?.serviceId] || {}
      i.service_type = type || service_type;
      i.normalCnt = i.callCnt - i.slowCnt - i.errCnt;
      i.normalPercent = i.callCnt ? ((i.normalCnt / i.callCnt) * 100).toFixed(2) : 0;
      i.slowPercent = i.callCnt ? ((i.slowCnt / i.callCnt) * 100).toFixed(2) : 0;
      i.errPercent = i.callCnt ? ((i.errCnt / i.callCnt) * 100).toFixed(2) : 0;

    });
  }

  public getData () {
    this.$refs.listTable?.refresh()
  }

    // 查看请求详情
  private viewResourceDetailHandle (row: any) {
    const query: any = {
      ...this.$route.query,
      endpoint: encodeURIComponent(row.resource),
      componentType: this.componentType,
      sn: encodeURIComponent(row.serviceName),
      sid: encodeURIComponent(row.serviceId),
    }
    delete query.type
    this.$router.push({
      path: '/appMonitor/resourceDetail',
      query,
    });
  }

  get queryName () {
    const { resourceQuery } = this.$route.query
    return resourceQuery ? decodeURIComponent(String(resourceQuery)) : ''
  }

  // 添加到搜索
  private addQueryHandle (row: any) {
    if (this.queryName && row.resource === this.queryName) {
      return
    }
    this.$router.replace({
      query: {
        ...this.$route.query,
        resourceQuery: encodeURIComponent(row.resource),
      }
    });
    this.$emit('add-query');
  }

  private async showChartTooltip (e: MouseEvent, row: any, field: 'avgLatency'|'errorRate'|'callCnt') {
    const { top, left, right, bottom } = (e?.target as HTMLElement)?.getBoundingClientRect();
    this.tooltipChart.title = field === 'avgLatency' ? i18n.t('modules.views.appMonitor.cache.s_96a0c062') as string : field === 'errorRate' ? i18n.t('modules.views.appMonitor.cache.s_0c8524d7') as string : i18n.t('modules.views.appMonitor.cache.s_8bc42b53') as string
    this.tooltipChart.describe = row.resource
    const tooltipWidth = 320;
    const halfWidth = tooltipWidth / 2;
    const tooltipHeight = 210;
    const { innerWidth, innerHeight } = window;
    const _left = left < halfWidth ? left : right + halfWidth > innerWidth ? innerWidth - 20 - tooltipWidth : left - halfWidth;
    const _top = top < tooltipHeight ? top + 30 : bottom + 20 + tooltipHeight >  innerHeight ? top - tooltipHeight - 20 : bottom + 20
    this.tooltipPosition = {
      left: `${_left}px`,
      top: `${_top}px`,
    }
    this.tooltipModal = true;
    this.tooltipChart.loading = true;
    this.tooltipChart.timer = setTimeout(() => {
      this.fetchTooltipTrend(row, field);
    }, 300);
  }
  private hideChartTooltip (e: MouseEvent, row: any, field: string) {
    this.tooltipModal = false;
    if (this.tooltipChart.timer) {
      window.clearTimeout(this.tooltipChart.timer);
      this.tooltipChart.timer = null;
    }
    if (this.tooltipChart.cancelTokenSource) {
      this.tooltipChart.cancelTokenSource?.cancel('interrupt');
    }
    this.tooltipChart.source = [];
    this.tooltipChart.loading = false;

  }

  private async fetchTooltipTrend (row: any, field: 'avgLatency'|'errorRate'|'callCnt') {
    this.tooltipChart.loading = true;
    if (this.tooltipChart.cancelTokenSource) {
      this.tooltipChart.cancelTokenSource?.cancel('interrupt');
    }
    this.tooltipChart.cancelTokenSource = axios.CancelToken.source();
    try {
      const { fromTime, toTime, interval } = this.getGlobalTimeV2();
      const _field = `${field}s`
      const params = {
        componentType: this.componentType,
        fromTime, toTime, interval,
        resource: row.resource,
        isIn: 1,
        graphStats: [_field],
        serviceId: row.serviceId
      }
      const { result, error } = await toAsyncWait(ApmApi.getServiceGraph(params, this.tooltipChart.cancelTokenSource.token))
      if (!error) {
        const data = result?.data || {};
        const sourceData = data[_field] || {};
        this.tooltipChart.source = [{
          name: field === 'avgLatency' ? i18n.t('modules.views.appMonitor.serviceAnalysis.s_207c26c9') as string : field === 'errorRate' ? i18n.t('modules.views.appMonitor.serviceAnalysis.s_0c8524d7') as string : i18n.t('modules.views.appMonitor.cache.s_8bc42b53') as string,
          type: 'line',
          area: true,
          unit: field === 'avgLatency' ? 'ns' : field === 'errorRate' ? '%' : '',
          data: Object.entries(sourceData).sort((a: any, b: any) => Number(a[0]) - Number(b[0])).map((item: any) => {
            return {
              key: dayjs(Number(item[0])).format('YYYY-MM-DD HH:mm'),
              value: item[1],
            }
          }),
        }]
      } else {
        this.tooltipChart.source = [];
      }
    } catch (error: any) {
      if (axios.isCancel(error)) {
        console.log('请求被取消:', error?.message);
      } else {
        console.error('请求失败', error);
      }
    } finally {
      this.tooltipChart.cancelTokenSource = null;
    }
    this.tooltipChart.loading = false;

  }
}
</script>

<style lang="scss" scoped>
.apm-table-cont {
  width: 100%;
  flex: 1;
  overflow: auto;
}
.monitor-event-info-tip {
  margin-bottom: 5px;
  padding-left: 6px;
}
.process-info{
  display: flex;
  border-radius: 2px;
  overflow: hidden;
  align-items: center;
  .process-info-item {
    height: 6px;
  }
}
.height-6 {
  height: 6px;
}
.w-60 {
  width: 60px;
}
.w-50 {
  width: 50px;
}
.chart-trend-tooltip {
  position: fixed;
  width: 320px;
  height: 210px;
  padding: 10px 15px;
  z-index: 101;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(10px);
  border: 1px solid #fff;
  border-radius: 4px;
  box-shadow: 0px 4px 10px 0px rgba(119, 122, 126, 0.14);

  .tooltip-chart-title {
    line-height: 18px;
    font-size: 13px;
    font-weight: 500;
    margin-bottom: 12px;
  }
  .tooltip-chart-wrapper {
    height: 160px;
  }
}
</style>
