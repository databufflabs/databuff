<template>
  <div class="apm-table-cont">
    <db-table
      showSetting
      :queryApi='queryApi'
      :queryParams='tableQueryParams'
      :offsetMode='true'
      :columnConfig='columnConfig'
      :autoRefresh='false'
      @on-table-inited='tableInitedHandle'
      @on-columns-inited='columnsInitedHandle'
      @on-columns-change='columnsChangeHandle'
      @sort-change='refresh'
      :formatFunc='formatFunc'
      tableKey='APM_SERVICE_LIST_V2'
      ref='listTable'>
      <template slot="column-name" slot-scope="{ row }">
        <div class="service-name-item ell blue">
          <span class="db-icon mr-5 vm">{{ (row.type || 'default') | DbIconFilter }}</span>
          <span @click="showDetailHandle(row)" class="service-name-text cphu ell">{{ row.name || row.service || '-' }}</span>
        </div>
      </template>
      <template slot="column-language" slot-scope="{ row }">
        <span v-if="hasLanguageIcon(row.language) || languageDisplayName(row.language)" class="language-cell">
          <span
            v-if="hasLanguageIcon(row.language)"
            class="db-icon mr-5 vm language-icon"
            :style="{ color: languageIconColor(row.language) }"
          >{{ resolveLanguageIcon(row.language) | DbIconFilter }}</span>
          <span>{{ languageDisplayName(row.language) || row.language || '-' }}</span>
        </span>
        <span v-else>-</span>
      </template>
    </db-table>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop } from 'vue-property-decorator'
import i18n from '@/i18n';
import ApmApi from '@/api/apm'
import {
  hasLanguageIcon,
  languageDisplayName,
  languageIconColor,
  resolveLanguageIcon,
} from '@/utils/service-language'

@Component({})
export default class ServiceTable extends Vue {
  @Prop({ default: {} }) private queryParams!: any;

  public $refs!: {
    listTable: any;
  };

  private hasLanguageIcon = hasLanguageIcon
  private languageDisplayName = languageDisplayName
  private languageIconColor = languageIconColor
  private resolveLanguageIcon = resolveLanguageIcon

  get tableQueryParams () {
    return {
      ...this.queryParams,
      showFields: [...this.showFields],
    }
  }

  private columnConfig: any = [
    { field: 'name', label: i18n.t('modules.views.alarmCenter.alarm.s_8f3747c0') as string, labelKey: 'modules.views.alarmCenter.alarm.s_8f3747c0', slot: 'column-name', minWidth: 150 },
    { field: 'service', prop: 'service', label: i18n.t('modules.views.appMonitor.external.s_5c3acce8') as string, labelKey: 'modules.views.appMonitor.external.s_5c3acce8', minWidth: 120, defaultShow: false },
    { field: 'language', prop: 'language', label: i18n.t('modules.components.s_295bb704') as string, labelKey: 'modules.components.s_295bb704', slot: 'column-language', minWidth: 100, defaultShow: true },
    { field: 'callCnt', prop: 'callCnt', label: i18n.t('modules.views.appMonitor.cache.s_8bc42b53') as string, labelKey: 'modules.views.appMonitor.cache.s_8bc42b53', unit: 'count', minWidth: 100, defaultShow: true, type: 'progress', sortable: true, defaultSort: 'desc', },
    { field: 'reqRate', prop: 'reqRate', label: i18n.t('modules.views.appMonitor.external.s_c0283020') as string, labelKey: 'modules.views.appMonitor.external.s_c0283020', unit: 'count', lessZeroOneKey: 'callCnt', minWidth: 100, defaultShow: true, suffix: i18n.t('modules.views.appMonitor.database.s_40b291ad') as string, type: 'progress', sortable: true },
    { field: 'lastMinReqRate', prop: 'lastMinReqRate', label: i18n.t('modules.views.appMonitor.external.s_ce60910b') as string, labelKey: 'modules.views.appMonitor.external.s_ce60910b', unit: 'count', lessZeroOneKey: 'callCnt', minWidth: 150, defaultShow: false, suffix: i18n.t('modules.views.appMonitor.database.s_40b291ad') as string, type: 'progress', sortable: true },
    { field: 'errRate', prop: 'errRate', label: i18n.t('modules.views.appMonitor.cache.s_0c8524d7') as string, labelKey: 'modules.views.appMonitor.cache.s_0c8524d7', unit: 'percent', lessZeroOneKey: 'errCnt', minWidth: 100, defaultShow: true, type: 'progress', progressDirection: 'horizontal', progressType: 'circle', progressStatus: 'exception', progressBarWidth: 3, progressMax: 1, sortable: true },
    { field: 'avgLatency', prop: 'avgLatency', label: i18n.t('modules.views.appMonitor.cache.s_96a0c062') as string, labelKey: 'modules.views.appMonitor.cache.s_96a0c062', unit: 'ns', minWidth: 120, defaultShow: true, type: 'progress', sortable: true },
    { field: 'maxLatency', prop: 'maxLatency', label: i18n.t('modules.views.appMonitor.external.s_3bff553d') as string, labelKey: 'modules.views.appMonitor.external.s_3bff553d', unit: 'ns', minWidth: 120, defaultShow: true, type: 'progress', sortable: true },
  ];

  private queryApi = ApmApi.getServiceList

  private columnFetchFieldMap: any = {
    name: ['name', 'service', 'serviceId', 'type', 'language'],
    service: ['service'],
    language: ['language'],
    callCnt: ['callCnt'],
    reqRate: ['reqRate'],
    lastMinReqRate: ['lastMinReqRate'],
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
      this.refresh()
    }
  }

  private showDetailHandle (row: any) {
    this.$router.push({
      path: '/appMonitor/serviceDetail',
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
  flex: 1;
  overflow: hidden;
}
.monitor-event-info-tip {
  margin-bottom: 5px;
  padding-left: 6px;
}
.language-icon {
  font-size: 16px;
  line-height: 1;
}
.language-cell {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
}
</style>
