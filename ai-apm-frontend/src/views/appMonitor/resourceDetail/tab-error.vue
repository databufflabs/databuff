<template>
  <div class="alarm-cont">
    <div class="alarm-wrapper flex-v">

      <h3 class="m-0 font-14 fw-normal flex-none">{{ $t('modules.views.appMonitor.resourceDetail.s_627f9108') }}</h3>
      <div class="chart-wrapper mt-10 mb-10 flex-none">
        <basic-chart
          :showEmpty="!chartGroup.trend.loading && !chartGroup.trend.source.length"
          :key='key'
          :colors='chartGroup.trend.colors'
          :showLegend='true'
          :textSmallMode="true"
          :minInterval="1"
          :min="0"
          :yAxisSplitNum="3"
          :interval="timeParams.interval"
          :source='chartGroup.trend.source'></basic-chart>
      </div>

      <div class="list-wrapper flex-1">
        <db-table
          v-if="!isEbpfSource"
          ref='listTable'
          :queryApi='queryApi'
          :queryParams='tableQueryParams'
          :offsetMode='true'
          :showSetting='true'
          :columnConfig='getColumns'
          @on-table-inited='tableInitedHandle'
          @sort-change='refresh'
          @on-fetch-end='onFetchEnd'
          :formatFunc='formatFunc'
          :row-style='{ cursor: "pointer" }'
          @row-click="showDetailHandle"
          tableKey='APM_RESOURCE_DETAIL_ERROR'>
        </db-table>
        <db-table
          v-else
          ref='listTable'
          :queryApi='ebpfQueryApi'
          :queryParams='tableQueryParams'
          :offsetMode='true'
          :showSetting='true'
          :columnConfig='ebpfColumnConfig'
          @on-table-inited='tableInitedHandle'
          @sort-change='refresh'
          @on-fetch-end='onFetchEnd'
          :formatFunc='formatFunc'
          tableKey='APM_RESOURCE_DETAIL_ERROR_EBPF'>
          <el-table-column slot="suffix" :label="$t('modules.views.aiPlatform.experts.s_2b6bc0f2')" :min-width="100">
            <template slot-scope="{ row }">
              <a
                @click.stop="viewErrorStackHandle(row)"
                class="blue">{{ $t('modules.views.appMonitor.resourceDetail.s_ab4c88f0') }}</a>
            </template>
          </el-table-column>
        </db-table>
      </div>
    </div>

    <!-- 错误堆栈弹框 -->
    <el-dialog
      :visible.sync="showStackDialog"
      :title="$t('modules.views.appMonitor.resourceDetail.s_e5e94591')"
      width="480px">
      <div class="stack-dialog-cont">
        <code-view :code="stackInfo" />
      </div>
    </el-dialog>
  </div>
</template>
<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import i18n from '@/i18n';
import CodeView from '@/components/code-view.vue';
import ServiceApi from '@/api/service';
import ApmApi from '@/api/apm';
import dayjs from 'dayjs';
import { toAsyncWait } from '@/utils/common';

@Component({
  components: {
    CodeView,
  },
})
export default class TabLog extends Vue {
  @Prop({ default: {} }) private current!: any;
  @Prop({ default: '' }) private componentType!: any;
  @Prop({ default: {} }) private queryParams!: any;
  @Prop({ default: false }) private isEbpfSource!: boolean;

  @Watch('current', { immediate: true })
  private onCurrentChange (val: any, oldVal: any) {
    if (val && val?.serviceId !== oldVal?.serviceId && val?.resource !== oldVal?.resource && this.isMounted) {
      this.fetchAllData();
    }
  }

  public $refs!: {
    listTable: any;
  };

  private timeParams = {
    fromTime: '',
    toTime: '',
    interval: 60,
  }

  private chartGroup: any = {
    trend: {
      loading: false,
      source: [],
      colors: ['#E12828'],
    },
  }

  private isMounted = false;

  get tableQueryParams () {
    const params: any = {
      ...this.timeParams,
      ...this.queryParams,
    }
    if (!this.isEbpfSource) {
      params.componentType = this.componentType
    } else {
      params.isIn = 1
    }
    return params
  }

  private queryApi = ServiceApi.getResourceErrorSpanList
  private columnConfig: any[] = [
    { field: 'startTime', prop: 'startTime', label: i18n.t('modules.views.alarmCenter.eventDetail.s_592c5958') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_592c5958', sortable: true, defaultSort: 'desc', unit: 'time', width: 135, defaultShow: true },
    { field: 'resource', prop: 'resource', label: i18n.t('modules.views.alarmCenter.eventDetail.s_34cab80c') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_34cab80c', sortable: true, minWidth: 200, defaultShow: true },
    { field: 'componentType', prop: 'componentType', label: i18n.t('modules.views.appMonitor.resourceDetail.s_27405e34') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_27405e34', sortable: true, unit: 'serviceRequestType', minWidth: 120, defaultShow: true },
    { field: 'duration', prop: 'duration', label: i18n.t('modules.views.appMonitor.relationMap.s_207c26c9') as string, labelKey: 'modules.views.appMonitor.relationMap.s_207c26c9', sortable: true, unit: 'ns', minWidth: 120, defaultShow: true },
    { field: 'error', prop: 'error', label: i18n.t('modules.views.alarmCenter.eventDetail.s_3fea7ca7') as string, labelKey: 'modules.views.aiPlatform.experts.s_3fea7ca7', sortable: true, type: 'healthStatus', warningText: i18n.t('modules.views.alarmCenter.eventDetail.s_1efeae37') as string, warningTextKey: 'modules.utils.filters.s_1efeae37', minWidth: 120, defaultShow: true },
    { field: 'service', prop: 'service', label: i18n.t('modules.views.alarmCenter.alarm.s_47d68cd0') as string, labelKey: 'modules.views.alarmCenter.alarm.s_47d68cd0', type: 'service', minWidth: 120, defaultShow: false },
    { field: 'serviceInstance', prop: 'serviceInstance', label: i18n.t('modules.views.alarmCenter.alarm.s_71673bab') as string, labelKey: 'modules.utils.filters.s_71673bab', minWidth: 120, defaultShow: false },
    { field: 'clientService', prop: 'clientService', label: i18n.t('modules.views.alarmCenter.eventDetail.s_e739425d') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_e739425d', minWidth: 120, defaultShow: true },
    { field: 'clientServiceInstance', prop: 'clientServiceInstance', label: i18n.t('modules.views.alarmCenter.eventDetail.s_6fcfca3f') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_6fcfca3f', minWidth: 120, defaultShow: false },
    // HTTP请求 service.http
    { field: 'meta.http.status_code', prop: 'meta.http.status_code', label: i18n.t('modules.views.alarmCenter.eventDetail.s_771d897d') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_771d897d', sortable: true, minWidth: 120, defaultShow: true, filterType: ['service.http'] },
    { field: 'meta.http.method', prop: 'meta.http.method', label: i18n.t('modules.views.alarmCenter.eventDetail.s_ea340b9d') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_ea340b9d', sortable: true, minWidth: 120, defaultShow: true, filterType: ['service.http'] },
    { field: 'meta.http.url', prop: 'meta.http.url', label: 'Url', sortable: true, minWidth: 120, defaultShow: true, filterType: ['service.http'] },
    // RPC请求 service.rpc
    { field: 'type', prop: 'type', label: i18n.t('modules.views.alarmCenter.eventDetail.s_226b0912') as string, labelKey: 'modules.views.aiPlatform.experts.s_226b0912', sortable: true, minWidth: 120, defaultShow: true, filterType: ['service.rpc'] },
    // MQ消费 service.mq
    { field: 'type', prop: 'type', label: i18n.t('modules.views.alarmCenter.eventDetail.s_226b0912') as string, labelKey: 'modules.views.aiPlatform.experts.s_226b0912', sortable: true, minWidth: 120, defaultShow: true, filterType: ['service.mq'] },
    { field: 'meta.mq.topic', prop: 'meta.mq.topic', label: 'Topic', sortable: true, minWidth: 120, defaultShow: true, filterType: ['service.mq'] },
    { field: 'meta.mq.group', prop: 'meta.mq.group', label: 'ConsumerGroup', sortable: true, minWidth: 120, defaultShow: true, filterType: ['service.mq'] },
    { field: 'meta.partition', prop: 'meta.partition', label: 'Partition', sortable: true, minWidth: 120, defaultShow: true, filterType: ['service.mq'] },
    { field: 'meta.mq.broker', prop: 'meta.mq.broker', label: 'Broker', sortable: true, minWidth: 120, defaultShow: true, filterType: ['service.mq'] },
    { field: 'meta.record.e2e_duration_ns', prop: 'meta.record.e2e_duration_ns', label: i18n.t('modules.views.appMonitor.resourceDetail.s_8877d7fc') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_8877d7fc', sortable: true, unit: 'ns', minWidth: 120, defaultShow: true, filterType: ['service.mq'] },
    // SQL调用 service.db
    { field: 'meta.db.operation', prop: 'meta.db.operation', label: i18n.t('modules.views.alarmCenter.eventDetail.s_de9cc3dd') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_de9cc3dd', sortable: true, minWidth: 120, defaultShow: true, filterType: ['service.db'] },
    { field: 'meta.db.instance', prop: 'meta.db.instance', label: i18n.t('modules.views.alarmCenter.eventDetail.s_5ccbbd01') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_5ccbbd01', sortable: true, minWidth: 120, defaultShow: true, filterType: ['service.db'] },
    { field: 'meta.db.type', prop: 'meta.db.type', label: i18n.t('modules.views.alarmCenter.eventDetail.s_c26c0d60') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_c26c0d60', sortable: true, minWidth: 120, defaultShow: true, filterType: ['service.db'] },
    { field: 'meta.db.updateRows', prop: 'meta.db.updateRows', label: i18n.t('modules.views.appMonitor.resourceDetail.s_d181886c') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_d181886c', sortable: true, minWidth: 120, defaultShow: true, filterType: ['service.db'] },
    { field: 'meta.db.return Rows', prop: 'meta.db.returnRows', label: i18n.t('modules.views.appMonitor.resourceDetail.s_973a6f08') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_973a6f08', sortable: true, minWidth: 120, defaultShow: true, filterType: ['service.db'] },
    { field: 'trace_id', prop: 'trace_id', label: 'TraceID', minWidth: 120, defaultShow: true },
    { field: 'span_id', prop: 'span_id', label: 'SpanId', minWidth: 120, defaultShow: true },
  ];
  get getColumns () {
    return this.columnConfig.filter((item) => {
      return !item.filterType || item.filterType.includes(this.componentType)
    })
  }

  private ebpfQueryApi = ServiceApi.getServiceCallLightApmErrSpans
  private ebpfColumnConfig = [
    { field: 'startTime', label: i18n.t('modules.views.alarmCenter.eventDetail.s_592c5958') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_592c5958', minWidth: 140, sortable: true, defaultSort: 'desc' },
    { field: 'resource', label: i18n.t('modules.views.alarmCenter.eventDetail.s_34cab80c') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_34cab80c', minWidth: 180 },
    { field: '_client', label: i18n.t('modules.views.appMonitor.resourceDetail.s_26791765') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_26791765', minWidth: 120 },
    { field: 'srcIp', label: i18n.t('modules.views.appMonitor.resourceDetail.s_b2316a4d') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_b2316a4d', minWidth: 120 },
    { field: 'ipPort', label: i18n.t('modules.views.appMonitor.resourceDetail.s_dfac0132') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_dfac0132', minWidth: 140 },
    { field: 'protocol', label: i18n.t('modules.views.appMonitor.resourceDetail.s_faa1ad5e') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_faa1ad5e', minWidth: 80 },
    { field: 'allCount', label: i18n.t('modules.views.appMonitor.resourceDetail.s_0f81e359') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_0f81e359', minWidth: 80, unit: 'count', sortable: true },
    { field: 'errorCount', label: i18n.t('modules.views.appMonitor.resourceDetail.s_f9e62864') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_f9e62864', minWidth: 80, unit: 'count', sortable: true },
    { field: 'reqBodyLength', label: i18n.t('modules.views.appMonitor.resourceDetail.s_0951ecd4') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_0951ecd4', minWidth: 120, unit: 'b', sortable: true },
    { field: 'respBodyLength', label: i18n.t('modules.views.appMonitor.resourceDetail.s_878d0b66') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_878d0b66', minWidth: 120, unit: 'b', sortable: true },
  ]

  private created () {
    this.resetTimeParams();
    this.$emit('on-created');
  }
  private mounted () {
    this.fetchTrendSource();
  }

  private resetTimeParams () {
    const { fromTime, toTime, interval } = this.getGlobalTimeV2()
    this.timeParams = { fromTime, toTime, interval };
  }

  public refresh () {
    this.resetTimeParams();
    this.fetchAllData();
  }

  private fetchAllData () {
    this.tableInitedHandle();
    this.fetchTrendSource();
  }

  private async fetchTrendSource () {
    const serviceId = this.current?.serviceId || decodeURIComponent(String(this.$route.query.sid));
    const _params: any = {
      ...this.timeParams,
      isIn: 1,
      serviceId,
      componentType: this.componentType,
      graphStats: ['callCnts'],
      url: this.queryParams?.url || this.current?.resource,
    }
    this.chartGroup.trend.loading = true;
    const { result, error } = await toAsyncWait(ApmApi.traceErrorTrend({ ..._params }))
    if (!error) {
      const { data = {} } = result || {};
      const rstData = data || {};
      const timeKeyStr = Object.keys(rstData);
      const errSource: any[] = timeKeyStr.map(i => +i).sort().map(i => {
        const item = rstData[`${i}`] || {}
        return {
          key: dayjs(i).format('YYYY-MM-DD HH:mm'),
          value: (item || 0),
        }
      });
      this.chartGroup.trend.source = [{
        name: i18n.t('modules.views.appMonitor.resourceDetail.s_88e6a005') as string, nameKey: 'modules.views.appMonitor.resourceDetail.s_88e6a005',
        type: 'bar',
        stack: 'call',
        data: errSource,
      }]
    }
    this.chartGroup.trend.loading = false;
  }

  // tableInitedHandle
  private tableInitedHandle () {
    this.isMounted = true;
    if (this.current?.serviceId) {
      this.$refs.listTable.refresh();
    }
  }

  private onFetchEnd () {
    this.$emit('on-loaded')
  }

  private formatFunc (data: any[]) {
    if (!this.isEbpfSource) {
      return (data || []).map((item: any) => {
        const meta: any = item.meta || {}
        const _meta: any = {};
        Object.entries(meta).forEach(([key, value]: any) => {
          _meta[`meta.${key}`] = value
        })
        item.startTime = String(item.start).substring(0, 13);
        item.componentType = this.componentType;
        return { ...item, meta, ..._meta }
      })
    } else {
      return data.map((t: any) => ({
        ...t,
        _client: [t.isOut ? i18n.t('modules.views.appMonitor.resourceDetail.s_efc6882b') as string : '', t.isIn ? '服务端' : ''].filter(i => !!i).join(','),
        ipPort: t.ip ? [t.ip, t.port].filter(i => !!i).join(':') : '',
      }));
    }
  }

  // 查看详情
  private showDetailHandle (row: any) {
    const spanStart = String(row?.start).substring(0, 13);
    const spanEnd = String(row?.end).substring(0, 13);
    this.$router.push({
      path: '/appMonitor/traceDetail',
      query: {
        spid: encodeURIComponent(row.span_id),
        tid: encodeURIComponent(row.trace_id),
        ft: `${+spanStart}`,
        tt: `${+spanEnd}`,
      }
    })
  }

  // 查看错误堆栈
  private showStackDialog = false;
  private stackInfo: string = '';
  private async viewErrorStackHandle (row: any) {
    try {
      const tags = JSON.parse(row.tags || '{}');
      this.stackInfo = tags.errorMsgDetail || '';
    } catch (error) {
      this.stackInfo = ''
    }
    this.showStackDialog = true;
  }
}
</script>
<style lang="scss" scoped>
.alarm-cont {
  padding-left: 4px;
  height: 100%;
}
.alarm-wrapper {
  height: 100%;
  display: flex;
}
.chart-wrapper {
  height: 180px;
}
.list-wrapper {
  flex: 1 1 auto;
  min-height: 286px;
}
</style>