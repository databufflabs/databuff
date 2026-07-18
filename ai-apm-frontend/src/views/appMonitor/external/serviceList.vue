<template>
  <div class="apm-table-cont">
    <db-table
      showSetting
      :queryApi='queryApi'
      :queryParams='queryParams'
      :offsetMode='true'
      :columnConfig='columnConfig'
      :autoRefresh='false'
      @on-table-inited='tableInitedHandle'
      @sort-change='refresh'
      :formatFunc='formatFunc'
      tableKey='APM_EXTERNAL_LIST'
      ref='listTable'>
      <template slot="column-name" slot-scope="{ row }">
        <div class="service-name-item ell blue">
          <span class="db-icon mr-5 vm">{{ (row.type || row.language || row.service_type || 'default') | DbIconFilter }}</span>
          <span @click="showDetailHandle(row)" class="service-name-text cphu ell">{{ row.name || row.service || '-' }}</span>
        </div>
      </template>
    </db-table>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Watch, Prop } from 'vue-property-decorator'
import ServiceApi from '@/api/service';
import i18n from '@/i18n';

@Component({
  filters: {
    apdexFormat (value: any) {
      if (!value && String(value) !== '0' || isNaN(+value) || !isFinite(+value)) {
        return '-'
      }
      return +Number(value).toFixed(2)
    },
  },
})
export default class ServiceTable extends Vue {
  @Prop({ default: {} }) private queryParams!: any;

  public $refs!: {
    listTable: any;
  };

  private columnConfig: any = [
    { field: 'name', label: i18n.t('modules.views.appMonitor.external.s_47921e9e') as string, labelKey: 'modules.views.appMonitor.external.s_47921e9e', slot: 'column-name', minWidth: 150 },
    // { field: 'service', prop: 'service', label: i18n.t('modules.views.appMonitor.external.s_5c3acce8') as string, labelKey: 'modules.views.appMonitor.external.s_5c3acce8', minWidth: 120, defaultShow: false },
    { field: 'callCnt', prop: 'callCnt', label: i18n.t('modules.views.appMonitor.cache.s_8bc42b53') as string, labelKey: 'modules.views.appMonitor.cache.s_8bc42b53', unit: 'count', minWidth: 100, defaultShow: true, type: 'progress', sortable: true, defaultSort: 'desc', },
    { field: 'reqRate', prop: 'reqRate', label: i18n.t('modules.views.appMonitor.external.s_c0283020') as string, labelKey: 'modules.views.appMonitor.external.s_c0283020', unit: 'count', lessZeroOneKey: 'callCnt', minWidth: 100, defaultShow: true, suffix: i18n.t('modules.views.appMonitor.database.s_40b291ad') as string, type: 'progress', sortable: true },
    { field: 'lastMinReqRate', prop: 'lastMinReqRate', label: i18n.t('modules.views.appMonitor.external.s_ce60910b') as string, labelKey: 'modules.views.appMonitor.external.s_ce60910b', unit: 'count', lessZeroOneKey: 'callCnt', minWidth: 150, defaultShow: false, suffix: i18n.t('modules.views.appMonitor.database.s_40b291ad') as string, type: 'progress', sortable: true },
    { field: 'errRate', prop: 'errRate', label: i18n.t('modules.views.appMonitor.cache.s_0c8524d7') as string, labelKey: 'modules.views.appMonitor.cache.s_0c8524d7', unit: 'percent', lessZeroOneKey: 'errCnt', minWidth: 100, defaultShow: true, type: 'progress', progressDirection: 'horizontal', progressType: 'circle', progressStatus: 'exception', progressBarWidth: 3, progressMax: 1, sortable: true },
    { field: 'avgLatency', prop: 'avgLatency', label: i18n.t('modules.views.appMonitor.cache.s_96a0c062') as string, labelKey: 'modules.views.appMonitor.cache.s_96a0c062', unit: 'ns', minWidth: 120, defaultShow: true, type: 'progress', sortable: true },
    { field: 'p100Latency', prop: 'p100Latency', label: i18n.t('modules.views.appMonitor.external.s_3bff553d') as string, labelKey: 'modules.views.appMonitor.external.s_3bff553d', unit: 'ns', minWidth: 120, defaultShow: true, type: 'progress', sortable: true },
      
  ];

  private queryApi = ServiceApi.getRemoteList


  private created () {
    //
  }

  private showDetailHandle (row: any) {
    this.$router.push({
      path: '/appMonitor/external/detail',
      query: {
        sn: encodeURIComponent(row.service),
        sid: encodeURIComponent(row.serviceId),
      }
    })
  }

  private tableInitedHandle () {
    this.$emit('on-table-inited')
  }

  private formatFunc (data: any) {
    data.forEach((i: any) => {
      i.errorRate = i.errRate
      i.reqCnt = i.callCnt
      i.lastMinReqRate = (i.lastMinReqRate || 0) / 60
    });
  }

  public refresh () {
    this.$refs.listTable?.refresh()
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
