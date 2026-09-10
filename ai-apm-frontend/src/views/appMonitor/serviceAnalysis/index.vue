<template>
  <div
    v-loading="queryLoading"
    class="service-analysis-cont">
    <div class="bg-color p16 service-analysis-wrapper">
      <db-tabnav
        v-model="requestType"
        :tabnavs="requestTypeList"
        @on-change="toggleTabHandle"
        class="tabnav mb-15" />

      <el-button v-if='hasServiceCfgModule' @click="viewSetting" size='small' type="primary" icon="el-icon-plus" class="setting-resource-btn">{{ $t('modules.views.appMonitor.serviceAnalysis.s_7c57a563') }}</el-button>

      <query-filter
        v-model='queryParams'
        :updateRoute='true'
        :filter-list="filterList"
        @on-change="handleChange"
        @on-remove-tag='handleRemoveTag'
        class="" />

      <chart-group
        ref="chartGroup"
        :query="_queryParams"
        :timeParams="timeParams"
        :componentType="requestType"
        class="chart-group"
        @on-refresh='durationChangeHandle' />

      <table-list
        ref="tableList"
        :queryParams="_queryParams"
        :timeParams="timeParams"
        :componentType="requestType"
        @add-query="addQueryHandle"
        class="service-analysis-list" />
    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import i18n from '@/i18n';
import dayjs from 'dayjs';
import { orderBy } from 'lodash';
import QueryFilter from '@/components/query-filter/index.vue';
import ChartGroup from './chart-group.vue';
import TableList from './table-list.vue'
import { ServiceTypeFilter } from '@/utils/filters/service'
import { toAsyncWait } from '@/utils/common';
import ServiceApi from '@/api/service';
import { TagItem, FormatedSelected } from '@/components/query-filter/types/index.types';

const RequestTypeMapping: any = {
  'service.http': i18n.t('modules.views.appMonitor.serviceAnalysis.s_e1e0939f') as string,
  'service.rpc': i18n.t('modules.views.appMonitor.serviceAnalysis.s_3fbcaa0a') as string,
  'service.mq': i18n.t('modules.views.appMonitor.resourceDetail.s_82375db4') as string,
  'service.db': i18n.t('modules.views.appMonitor.resourceDetail.s_b3bf0a2d') as string,
  'service.redis': i18n.t('modules.views.appMonitor.resourceDetail.s_218e2ad9') as string,
  'service.config': i18n.t('modules.views.appMonitor.serviceAnalysis.s_77fb421e') as string,
  'service.remote': i18n.t('modules.views.appMonitor.resourceDetail.s_71f31c96') as string,
  'service.other': i18n.t('modules.views.appMonitor.serviceAnalysis.s_32efd608') as string,
}

@Component({
  components: {
    QueryFilter,
    ChartGroup,
    TableList,
  }
})
export default class ServiceAnalysis extends Vue {

  public $refs!: {
    queryFilter: QueryFilter
    chartGroup: ChartGroup
    tableList: TableList
  }

  @Watch('globalTimeV2', { deep: true })
  private watchGlobalTime() {
    this.durationChangeHandle()
  }

  get chartGroupCanInit () {
    return this.inited && this.$refs.chartGroup?.isMounted
  }

  @Watch('chartGroupCanInit')
  private onChartGroupCanInit (newVal: boolean) {
    if (newVal) {
      this.$refs.chartGroup?.getData();
    }
  }
  get tableListCanInit () {
    return this.inited && this.$refs.tableList?.tableListCanInit
  }

  @Watch('tableListCanInit')
  private onTableListCanInit (newVal: boolean) {
    if (newVal) {
      this.$refs.tableList?.getData();
    }
  }

  private timeParams = {
    fromTime: '',
    toTime: '',
    interval: 3600,
  }

  private queryLoading = false;
  private inited = false;
  private queryParams: any = {
    resourceQuery: '',
    srcSid: '',
    sid: '',
    si: '',
    dbTarget: '',
  }

  get _queryParams () {
    const params: any = {
      ...this.queryParams,
      isIn: 1,
    }
    const selectedService = this.serviceList.find((item) => item.value === params.sid)
    // Remote virtual services are downstream targets; keep sid on the metric's serviceId dimension.
    const isRemoteTarget = this.requestType === 'service.remote' && selectedService?.info?.virtualService
    if (params.dbTarget || isRemoteTarget) {
      params.dbTarget = 1
    } else {
      delete params.dbTarget
    }
    return params
  }

  private serviceList: any[] = [];
  private srcServiceList: any[] = [];
  private serviceInstanceList: any[] = [];
  private requestType = ''
  private requestTypeList: any[] = [
    { label: i18n.t('modules.views.appMonitor.serviceAnalysis.s_e1e0939f') as string, labelKey: 'modules.views.appMonitor.serviceAnalysis.s_e1e0939f', value: 'service.http', disabled: false },
    { label: i18n.t('modules.views.appMonitor.serviceAnalysis.s_3fbcaa0a') as string, labelKey: 'modules.views.appMonitor.serviceAnalysis.s_3fbcaa0a', value: 'service.rpc', disabled: false },
    { label: i18n.t('modules.views.appMonitor.resourceDetail.s_82375db4') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_82375db4', value: 'service.mq', disabled: false },
    { label: i18n.t('modules.views.appMonitor.resourceDetail.s_b3bf0a2d') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_b3bf0a2d', value: 'service.db', disabled: false },
    { label: i18n.t('modules.views.appMonitor.resourceDetail.s_218e2ad9') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_218e2ad9', value: 'service.redis', disabled: false },
    { label: i18n.t('modules.views.appMonitor.serviceAnalysis.s_77fb421e') as string, labelKey: 'modules.views.appMonitor.serviceAnalysis.s_77fb421e', value: 'service.config', disabled: false },
    { label: i18n.t('modules.views.appMonitor.resourceDetail.s_71f31c96') as string, labelKey: 'modules.utils.filters.s_71f31c96', value: 'service.remote', disabled: false },
    { label: i18n.t('modules.views.appMonitor.serviceAnalysis.s_32efd608') as string, labelKey: 'modules.views.appMonitor.serviceAnalysis.s_32efd608', value: 'service.other', disabled: false },
  ]
  private requestLoading = false;
  private resourceList: any[] = [];
  private serviceMapping: any = {}
  private serviceInstanceMap: any = {} // 服务的实例map
  private resourceMap: any = {} // 服务的实例map

  private filterList: any[] = []

  get filterListInit (): any[] {
    const list = [
      {
        field: 'resourceQuery',
        label: i18n.t('modules.views.alarmCenter.eventDetail.s_34cab80c') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_34cab80c',
        type: 'select',
        likeable: true,
        children: this.resourceList.map(t => ({
          ...t,
          showValue: t.label,
        })),
      },
      {
        field: 'srcSid',
        label: i18n.t('modules.views.alarmCenter.eventDetail.s_e739425d') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_e739425d',
        type: 'select',
        children: this.srcServiceList.map(t => ({
          ...t,
          showValue: t.label,
        })),
      },
      {
        field: 'sid',
        label: i18n.t('modules.views.alarmCenter.alarm.s_47d68cd0') as string, labelKey: 'modules.views.alarmCenter.alarm.s_47d68cd0',
        type: 'select',
        children: this.serviceList.map(t => ({
          ...t,
          showValue: t.label,
        })),
      },
    ]
    if (this.queryParams.sid) {
      list.push({
        field: 'si',
        label: i18n.t('modules.views.alarmCenter.alarm.s_71673bab') as string, labelKey: 'modules.utils.filters.s_71673bab',
        type: 'select',
        children: this.serviceInstanceList.map(t => ({
          ...t,
          showValue: t.label,
        })),
      })
    }
    return list
  }

  get getMenus () {
    return this.$store.getters['User/getMenus']
  }

  get hasServiceCfgModule () {
    return !!this.getMenus?.find((t: any) => t.path === '/config/service')
  }

  private getRouteTabType (): string {
    const { tabType } = this.$route.query;
    if (!tabType) {
      return '';
    }
    const routeTabType = decodeURIComponent(String(tabType));
    return this.requestTypeList.some(item => item.value === routeTabType) ? routeTabType : '';
  }
  
  private created () {
    const { sid } = this.$route.query
    this.regetGlobalTime();
    if (!sid && this.requestTypeList.length) {
      this.requestType = this.requestTypeList[0].value;
    }
  }
  private async mounted () {
    const routeTabType = this.getRouteTabType();
    this.requestType = routeTabType || this.requestTypeList[0]?.value || '';
    // 监听全局的手动刷新事件，created中会有不触发的情况
    // 子组件绑定会被覆盖
    this.$eventBus.$on('GlobalRefresh', this, () => {
      this.durationChangeHandle()
    });
    this.regetGlobalTime();
    await this.queryServiceIdNames();
    await this.init();
  }

  private beforeDestroy () {
    // 清除监听全局刷新事件
    this.$eventBus.$off('GlobalRefresh');
  }

  private async init () {
    // 搜索参数回显
    const routerQuery = this.$route.query
    const query: any = { ...this.queryParams }
    Object.keys(query).forEach(k => {
      if (routerQuery[k]) {
        query[k] = decodeURIComponent(String(routerQuery[k]))
      }
    });
    this.queryParams = {
      ...this.queryParams,
      ...query
    };

    // 实例列表 & 服务类型tabnav
    const routeTabType = this.getRouteTabType();
    if (query.sid) {
      await this.getServiceInstance(query.sid);
      const serviceInfo = this.serviceMapping[query.sid];
      if (serviceInfo) {
        this.serviceInstanceList = [...serviceInfo.serviceInstanceList];
        const _typeList = [...serviceInfo.requestTypeList];

        this.requestTypeList.forEach((t) => {
          t.disabled = !_typeList.some((i) => i.value === t.value) && t.value !== routeTabType;
        });
        if (routeTabType) {
          this.requestType = routeTabType;
        } else {
          const hasType = _typeList.find((i) => i.value === this.requestType);
          if (!hasType && _typeList.length) {
            this.requestType = _typeList[0].value;
          }
        }
      } else if (routeTabType) {
        this.requestType = routeTabType;
      }
    } else {
      this.requestTypeList.forEach((t) => {
        t.disabled = false
      });
      if (routeTabType) {
        this.requestType = routeTabType;
      } else {
        const hasType = this.requestTypeList.find((i) => i.value === this.requestType);
        this.requestType = hasType ? this.requestType : this.requestTypeList[0].value;
      }
    }

    await this.getSrcServiceList();
    // 资源
    await this.getResourceList(query?.sid, query?.si);
    this.filterList = [...this.filterListInit];
    this.inited = true;
  }

  private async durationChangeHandle () {
    this.serviceMapping = {}
    this.serviceInstanceMap = {}
    this.regetGlobalTime();
    // this.queryLoading = true;
    this.addQueryHandle()
  }

  private regetGlobalTime () {
    const { fromTime, toTime, interval } = this.getGlobalTimeV2()
    this.timeParams = {
      fromTime, toTime, interval
    }
  }

  private async queryServiceIdNames () {
    const { result, error } = await toAsyncWait(ServiceApi.getServicesIds({ fromTime: '', toTime: '', ignoreTime: 1 }))
    if (!error) {
      const { data = [] } = result || {};
      const serviceNameIdMap: any = {}
      data.forEach((t: any) => {
        serviceNameIdMap[t.name] = {
          id: t.id,
          type: t.service_type,
          virtualService: t.virtual_service,
        }
      });
      this.serviceList = orderBy(Object.keys(serviceNameIdMap), [t => t.toLocaleLowerCase()], ['asc'])
          .map(t => ({
            label: t,
            value: serviceNameIdMap[t].id,
            info: {
              type: serviceNameIdMap[t].type,
              virtualService: serviceNameIdMap[t].virtualService,
              texts: ['服务类型：' + ServiceTypeFilter(serviceNameIdMap[t].type)],
            },
          }))
    }
  }

  private async getSrcServiceList () {
    const { result, error } = await toAsyncWait(ServiceApi.getSrcServices({
      fromTime: this.timeParams.fromTime, toTime: this.timeParams.toTime, isIn: 1, componentType: this.requestType, field: 'srcService,srcServiceId'
    }))
    if (!error) {
      const { data = {} } = result || {};
      const requestTypeList = (data || {})[this.requestType] || [];
      this.srcServiceList = requestTypeList.map((t: any) => ({
        label: t.srcService || t.srcServiceId,
        value: t.srcServiceId,
      }));
      if (this.queryParams.srcSid && !this.srcServiceList.find((i) => i.value === this.queryParams.srcSid)) {
        this.queryParams.srcSid = ''
      }
    }
  }

  private async getResourceList (sid?: string, si?: string) {
    const { fromTime, toTime } = this.getGlobalTimeV2()
    // 请求列表
    // if (sid && si && this.resourceMap[`${sid}_${si}`]) {
    //   this.resourceList = [...this.resourceMap[`${sid}_${si}`]]
    //   return;
    // }
    const { result: resRst, error: resErr } = await toAsyncWait(ServiceApi.getServiceRequestByCompTypes({
      field: 'resource',
      isIn: 1,
      serviceId: sid || null,
      serviceInstance: sid && si ? si : null,
      componentType: this.requestType || null,
      fromTime, toTime
    }));
    let _resList: any = []
    if (!resErr) {
      const data: any = (resRst || {}).data || {}
      const resGroup = Object.values(data)
      if (Array.isArray(resGroup) && resGroup.length) {
        const resList = resGroup.flat();
        _resList = [...new Set(resList)].map((t: any) => ({
          label: t,
          value: t,
        }));
        this.resourceList = _resList
      } else {
        this.resourceList = []
      }
    } else {
      this.resourceList = []
    }
    this.resourceMap[`${sid}_${si}`] = [..._resList];
  }

  private async getServiceInstance (sid: string) {
    if (this.serviceMapping[sid]) {
      return this.serviceMapping[sid];
    }
    const params = {
      serviceId: sid,
      fromTime: this.timeParams.fromTime,
      toTime: this.timeParams.toTime,
    }
    const { result, error } = await toAsyncWait(ServiceApi.getBasicServiceInstance(params))
    if (!error) {
      const data = result.data || {}
      const list = (data.serviceInstances || []).map((t: any) => ({ label: t.serviceInstance, value: t.serviceInstance }));
      this.$set(this.serviceInstanceMap, sid, list);
      const types = data.componentTypes || [];
      const _requestTypeList = types.map((t: string) => ({ label: RequestTypeMapping[t], value: t }));
      this.serviceMapping[sid] = {
        ...data,
        serviceInstanceList: [...list],
        requestTypeList: [..._requestTypeList],
      }
    }
  }

  private toggleTabHandle(tab: any) {
    const { value } = tab;
    this.requestType = value;
    const query: any = {
      ...this.$route.query,
      tabType: value,
    }
    this.queryParams.resourceQuery = '';
    delete query.resourceQuery;
    this.$router.replace({ query });
    this.init();
    this.$nextTick(() => {
      this.$refs.chartGroup && this.$refs.chartGroup.getData()
      this.$refs.tableList && this.$refs.tableList.getData()
    })
  }

  private addQueryHandle () {
    this.init();
    this.$nextTick(() => {
      this.$refs.chartGroup?.getData();
      this.$refs.tableList?.getData();
    });
  }

  private async handleChange ({ row, selected }: { row: TagItem, selected: FormatedSelected[] }) {
    if (row?.field === 'sid') {
      this.queryParams = {
        ...this.queryParams,
        si: '',
        resourceQuery: ''
      };
      const query = { ...this.$route.query }
      delete query.si;
      delete query.resourceQuery;
      this.$router.replace({ query: {...query}});
    }
    await this.init();

    this.$nextTick(() => {
      this.$refs.chartGroup?.getData();
      this.$refs.tableList?.getData();
    });
  }
  private async handleRemoveTag ({field}: {field: string}) {
    if (field === 'sid') {
      this.queryParams = {
        ...this.queryParams,
        si: '',
        resourceQuery: ''
      };
      const query = { ...this.$route.query }
      delete query.si;
      delete query.resourceQuery;
      this.$router.replace({ query: {...query}});
    }
    await this.init();
    this.$nextTick(() => {
      this.$refs.chartGroup?.getData();
      this.$refs.tableList?.getData();
    });
  }

  private viewSetting () {
    this.$router.push({
      path: '/config/service',
    })
  }
}
</script>

<style lang="scss" scoped>
.service-analysis-cont {
  height: 100%;
  position: relative;
  overflow: hidden;
  padding: 16px;
}
.service-analysis-wrapper {
  flex: 1;
  padding: 20px;
  height: 100%;
  display: flex;
  flex-direction: column;
  color: var(--color-text-regular);
  overflow: auto;
  position: relative;

  .tabnav {
    margin-top: 10px;
    height: 44px;
  }

  .setting-resource-btn {
    position: absolute;
    right: 20px;
    top: 20px;
  }

  .chart-group {
    margin-top: 16px;
  }

  .service-analysis-list {
    margin-top: 16px;
    flex: 1;
    min-height: 400px;
    overflow: hidden;
  }

  :deep(> .el-loading-mask) {
    z-index: 2001;
  }
}
</style>
