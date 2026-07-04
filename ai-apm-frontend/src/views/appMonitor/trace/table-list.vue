<template>
  <div class="apm-table-cont">
    <db-table
      showSetting
      :queryApi='queryApi'
      :queryParams='queryParams'
      :offsetMode='true'
      :columnConfig='getColumnConfig'
      :timeMode="false"
      :autoRefresh='false'
      @on-table-inited='tableInitedHandle'
      @sort-change='refresh'
      @on-columns-change='onColumnsChange'
      :formatFunc='formatFunc'
      tableKey='APM_TRACE_LIST'
      ref='listTable'>

      <template slot="total" slot-scope="{ total }">
        <div class="describe">
          <span class="mr-15">{{ $t('modules.views.appMonitor.trace.s_1d0e7889', { value0: new Intl.NumberFormat().format(total) }) }}</span>
          <span class="time-range">{{ $t('modules.views.appMonitor.serviceCallDetail.s_c4023f57', listTimeDisplay) }}</span>
        </div>
      </template>
      <el-table-column slot="suffix" label="日志" width="72" align="center" fixed="right">
        <template slot-scope="{ row }">
          <span
            v-if="row.trace_id"
            @click.stop="viewLogsHandle(row)"
            class="db-blue cp font-12">查看</span>
          <span v-else class="describe">-</span>
        </template>
      </el-table-column>
    </db-table>
  </div>
</template>

<script lang="ts">import i18n from '@/i18n';

import { Vue, Component, Prop } from 'vue-property-decorator'
import ApmApi from '@/api/apm'
import { formatCompactTimeRange } from '@/utils/timeFormat'

@Component({})
export default class ServiceTable extends Vue {
  @Prop({ default: () => ({}) }) private query!: any;
  @Prop({ default: () => ({}) }) private filter!: any;

  get queryParams () {
    return {
      ...this.query,
      ...this.filter, 
    }
  }

  get listTimeDisplay () {
    return formatCompactTimeRange(this.query.fromTime, this.query.toTime);
  }

  get getBasicServiceMap () {
    return this.$store.getters['Service/basicServiceMap']
  }

  public $refs!: {
    listTable: any;
  };

  get getColumnConfig () {
    return this.columnConfig;
  }

  private columnConfig: any = [
    { field: 'start', prop: 'start', label: i18n.t('modules.views.alarmCenter.eventDetail.s_592c5958') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_592c5958', unit: 'time', width: 135, defaultShow: true, sortable: true, defaultSort: 'desc' },
    { field: 'trace_id', prop: 'trace_id', label: 'TraceID', width: 160, defaultShow: true, disabled: true, handleClick: this.showDetailHandle },
    { field: 'resource', prop: 'resource', label: i18n.t('modules.views.alarmCenter.eventDetail.s_34cab80c') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_34cab80c', minWidth: 120, defaultShow: true, sortable: true },
    { field: 'error', prop: 'error', label: i18n.t('modules.views.alarmCenter.eventDetail.s_3fea7ca7') as string, labelKey: 'modules.views.aiPlatform.experts.s_3fea7ca7', type: 'healthStatus', warningText: i18n.t('modules.views.alarmCenter.eventDetail.s_1efeae37') as string, warningTextKey: 'modules.utils.filters.s_1efeae37', minWidth: 80, defaultShow: true, sortable: true },
    { field: 'duration', prop: 'duration', label: i18n.t('modules.views.alarmCenter.eventDetail.s_39f1374d') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_39f1374d', unit: 'ns', minWidth: 100, defaultShow: true, sortable: true, type: 'progress' },
    { field: 'service', prop: 'service', label: i18n.t('modules.views.alarmCenter.alarm.s_47d68cd0') as string, labelKey: 'modules.views.alarmCenter.alarm.s_47d68cd0', type: 'service', minWidth: 120, defaultShow: true, sortable: true, handleClick: this.showServiceDetailHandle },
    { field: 'serviceInstance', prop: 'serviceInstance', label: i18n.t('modules.views.alarmCenter.alarm.s_71673bab') as string, labelKey: 'modules.utils.filters.s_71673bab', minWidth: 120, defaultShow: false, sortable: true },
    { field: '_type', prop: 'type', label: i18n.t('modules.views.alarmCenter.eventDetail.s_226b0912') as string, labelKey: 'modules.views.aiPlatform.experts.s_226b0912', minWidth: 80, defaultShow: true, sortable: true },
    { field: 'meta.http.method', prop: 'meta.http.method', label: i18n.t('modules.views.alarmCenter.eventDetail.s_ea340b9d') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_ea340b9d', minWidth: 80, defaultShow: false, sortable: true },
    { field: 'meta.http.status_code', prop: 'meta.http.status_code', label: i18n.t('modules.views.alarmCenter.eventDetail.s_771d897d') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_771d897d', minWidth: 80, defaultShow: true, sortable: true },
    { field: 'meta.error.type', prop: 'meta.error.type', label: i18n.t('modules.views.alarmCenter.eventDetail.s_f700c855') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_f700c855', minWidth: 120, defaultShow: false, sortable: true },
    { field: 'meta.peer.hostname', prop: 'meta.peer.hostname', label: 'http host', minWidth: 120, defaultShow: false, sortable: true },
    { field: 'hostName', prop: 'hostName', label: i18n.t('modules.views.alarmCenter.alarm.s_65227369') as string, labelKey: 'modules.views.alarmCenter.alarm.s_65227369', minWidth: 120, defaultShow: true, sortable: true },
  ];

  private queryApi = ApmApi.getSpanList


  private created () {
    //
  }

  private tableInitedHandle () {
    this.$emit('on-table-inited')
  }

  private formatFunc (data: any) {
    data.forEach((i: any, idx: number) => {
      i.start = String(i.start).substring(0, 13);
      i['meta.http.method'] = i?.meta?.['http.method'];
      i['meta.http.status_code'] = i?.meta?.['http.status_code'];
      i['meta.error.type'] = i?.meta?.['error.type'];
      i['meta.peer.hostname'] = i?.meta?.['peer.hostname'];
      const { service_type, type, language } = (this.getBasicServiceMap || {})[i?.serviceId] || {}
      i._type = i.type;
      i.type = type || language || service_type
    });
  }

  public refresh () {
    this.$refs.listTable?.refresh()
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

  private viewLogsHandle (row: any) {
    if (!row?.trace_id) {
      return
    }
    const spanStart = String(row?.start).substring(0, 13);
    const spanEnd = String(row?.end).substring(0, 13);
    this.$router.push({
      path: '/appMonitor/logs',
      query: {
        traceId: encodeURIComponent(row.trace_id),
        ft: `${+spanStart}`,
        tt: `${+spanEnd}`,
      },
    })
  }

  // 查看服务详情
  private showServiceDetailHandle (row: any) {
    this.$router.push({
      path: '/appMonitor/serviceDetail',
      query: {
        ...this.getRouteTimeOrRange,
        sid: encodeURIComponent(row.serviceId)
      }
    })
  }

  private onColumnsChange (payload: any) {
    this.$emit('on-columns-change', payload);
    const _requestFields = payload.fields.filter((item: any) => item.requestKey);
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
</style>
<style>
.apm-table-cont .scroll-el-table-header {
  padding-top: 0;
}
</style>
