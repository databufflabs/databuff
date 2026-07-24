<template>
  <div class="comp-cont p-16"
    v-loading='isLoading'>
    <div class="comp-wrapper p-16 bg-color flex-v">
      <div class="comp-header flex-h-jc">
        <div class="comp-header-title flex-h">
          <span class="font-16 fw-500 mr-10">{{ resourceDetail.resource || '-' }}</span>
        </div>
        <div class="comp-header-action">
          <el-button v-if="hasProfiling && isDatabuffSource" type="primary" size="small" @click="jumpProfilingHandle">
            <i class="db-icon db-icon-metrics font-12"></i>
            {{ $t('modules.views.appMonitor.resourceDetail.s_1db9cad7') }}</el-button>
          <el-button v-if="!isEbpfSource" type="primary" size="small" @click="viewFlowHandle">
            <i class="db-icon db-icon-layout-horizontal font-12"></i>
            {{ $t('modules.views.appMonitor.resourceDetail.s_005dfdd0') }}</el-button>
        </div>
      </div>

      <div class="mt-10">
        <db-tabnav v-model='activeName' :tabnavs='tabnavByDatasource' @on-change='tabChange'></db-tabnav>
      </div>

      <!-- tab body -->
      <div class="comp-body">
        <component
          :is='activeName'
          :queryParams='queryParams'
          :componentType='componentType'
          :current='resourceDetail'
          :isEbpfSource='isEbpfSource'
          @on-loaded='subCompLoadedHandle'
          @on-created='subCompCreatedHandle'
          ref='subComp' />
      </div>
    </div>
  </div>
</template>
<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import i18n from '@/i18n';

import TabRelation from './tab-relation.vue';
import TabBaseinfo from './tab-baseinfo.vue';
import TabLog from './tab-log.vue';
import TabSlow from './tab-slow.vue';
import TabError from './tab-error.vue';
import TabAlarm from './tab-alarm.vue';
import ServiceApi from '@/api/service';
import ApmApi from '@/api/apm';
import { toAsyncWait, decodeRouteQuery } from '@/utils/common';

@Component({
  components: {
    'tab-relation': TabRelation,
    'tab-baseinfo': TabBaseinfo,
    'tab-log': TabLog,
    'tab-slow': TabSlow,
    'tab-error': TabError,
    'tab-alarm': TabAlarm,
  }
})
export default class ResourceDetail extends Vue {
  
  @Watch('globalTimeV2', { deep: true })
  private watchGlobalTime() {
    this.refresh();
  }

  @Watch('$route.query')
  private onEndpoint (newVal: any, oldVal: any) {
    if (newVal.endpoint !== oldVal.endpoint || newVal.sid !== oldVal.sid) {
      this.resourceDetail = {};
      this.init();
      this.$nextTick(() => {
        this.refresh();
      })
    }
  }

  get serviceIdNameMapping () {
    const mapping: any = {}
    const basicServiceMap = this.$store.getters['Service/basicServiceMap']
    Object.keys(basicServiceMap).forEach((t: string) => {
      mapping[t] = basicServiceMap[t].name
    });
    return mapping
  }
  get serviceIdMapping () {
    const mapping: any = {}
    const basicServiceMap = this.$store.getters['Service/basicServiceMap']
    Object.keys(basicServiceMap).forEach((t: string) => {
      mapping[t] = basicServiceMap[t]
    });
    return mapping
  }

  get getSeriviceMapInfo () {
    const { sid } = this.$route.query;
    return sid && this.$store.state.Service.basicServiceMap?.[sid as string] || {}
  }

  get isDatabuffSource () {
    const datasource = String(this.getSeriviceMapInfo?.datasource || '').toLowerCase();
    const virtual_service = this.getSeriviceMapInfo?.virtual_service;
    return (datasource === 'df-javaagent' || datasource === 'databuff') && !virtual_service;
  }

  get isEbpfSource () {
    const datasource = String(this.getSeriviceMapInfo?.datasource || '').toLowerCase();
    return datasource.toLowerCase() === 'df-ebpf';
  }

  public $refs!: {
    subComp: any;
  }

  private detailLoading = false;
  private subCompLoading = false;
  
  private resourceDetail: any = {};

  private queryParams: any = {
    serviceId: '',
    url: '',
    resource: '',
  }
  private queryCode = ''
  private componentType = ''

  private tabnavs: any[] = [
    { label: i18n.t('modules.views.appMonitor.resourceDetail.s_dc93171c') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_dc93171c', value: 'tab-relation' },
    { label: i18n.t('modules.views.appMonitor.resourceDetail.s_6ea1fe6b') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_6ea1fe6b', value: 'tab-baseinfo' },
    { label: i18n.t('modules.views.appMonitor.resourceDetail.s_7c71041e') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_7c71041e', value: 'tab-alarm' },
    { label: i18n.t('modules.views.appMonitor.resourceDetail.s_f446f220') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_f446f220', value: 'tab-log' },
    { label: i18n.t('modules.views.appMonitor.resourceDetail.s_9190f12c') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_9190f12c', value: 'tab-slow' },
    { label: i18n.t('modules.views.appMonitor.resourceDetail.s_83fca45a') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_83fca45a', value: 'tab-error' },
  ];

  get tabnavByDatasource () {
    if (this.isEbpfSource) {
      return this.tabnavs.filter(t => t.value !== 'tab-log' && t.value !== 'tab-slow');
    }
    return this.tabnavs;
  }

  get hasProfiling () {
    return ['service.http', 'service.rpc', 'service.mq'].includes(this.componentType);
  }

  private activeName = '';

  get isLoading () {
    return this.detailLoading || this.subCompLoading
  }

  private async created () {
    this.init()
  }

  private async init () {
    const { sid, si, srcSid, endpoint, componentType } = this.$route.query
    if (!sid) {
      this.$message.error(i18n.t('modules.views.appMonitor.resourceDetail.s_d463bc98') as string)
      this.$router.replace({
        path: '/appMonitor/serviceAnalysis',
      })
      return
    }
    const _sid = decodeURIComponent(String(sid || ''))
    const _sn = this.serviceIdNameMapping[_sid] || ''
    const _si = decodeURIComponent(String(si || ''))
    const _srcSid = decodeURIComponent(String(srcSid || ''))
    const _srcSn = this.serviceIdNameMapping[_srcSid] || ''
    // 设置面包屑
    const instancePart = _si ? (i18n.t('modules.views.appMonitor.resourceDetail.s_bf9ac785', { value0: _si }) as string) : '';
    const sourcePart = _srcSid && _srcSn ? (i18n.t('modules.views.appMonitor.resourceDetail.s_dd79c7bc', { value0: _srcSn }) as string) : '';
    this.$store.commit('UPDATE_BREADCRUMB', [{
      name: i18n.t('modules.views.appMonitor.resourceDetail.s_3945cb83', { value0: instancePart, value1: _sn, value2: sourcePart }) as string,
      path: '/appMonitor/resourceDetail',
    }]);
    this.queryCode = decodeRouteQuery(endpoint as string);
    this.componentType = componentType as string

    // 筛选区及列表参数：HTTP 用 url（无 host 路径），其他组件用 resource
    this.queryParams = {
      serviceId: _sid,
      ...(this.componentType === 'service.http'
        ? { url: this.queryCode }
        : { resource: this.queryCode }),
    }
  }

  private async mounted () {
    this.$eventBus.$on('GlobalRefresh', this, () => {
      this.refresh();
    });
    await this.getResourceDetail();
    this.$nextTick(() => {
      const { activeName } = this.$route.query;
      const _activeName = decodeURIComponent(String(activeName));
      const hasActiveName = !!(this.tabnavByDatasource.find((i) => i.value === _activeName));
      this.activeName = activeName && hasActiveName  ? _activeName : 'tab-relation';
      if (!hasActiveName) {
        this.$router.replace({ query: { ...this.$route.query, activeName: 'tab-relation' } })
      }
    });
  }

  private beforeDestroy () {
    // 清除监听全局刷新事件
    this.$eventBus.$off('GlobalRefresh');
  }

  private async refresh () {
    await this.getResourceDetail();
    if (this.$refs.subComp?.refresh) {
      this.$refs.subComp?.refresh();
    }
  }

  private subCompCreatedHandle () {
    this.subCompLoading = true;
  }
  private subCompLoadedHandle () {
    this.subCompLoading = false;
  }

  // 服务详情
  private async getResourceDetail () {
    this.detailLoading = true;
    const { fromTime, toTime } = this.getGlobalTimeV2();
    const params = {
      // isIn: this.queryParams.isIn,
      serviceId: this.queryParams.serviceId,
      ...(this.componentType === 'service.http'
        ? { url: this.queryParams.url }
        : { resource: this.queryParams.resource }),
      componentType: this.componentType,
      fromTime,
      toTime,
    }
    const { result, error } = await toAsyncWait(ServiceApi.getResourceDetail(params))
    if (!error) {
      if (result.data?.resource && result.data?.serviceId) {
        this.resourceDetail = result.data || {};
      } else {
        const { service, service_type } = this.serviceIdMapping[String(this.$route.query.sid)];
        this.resourceDetail = {
          resource: decodeRouteQuery(String(this.$route.query.endpoint)),
          serviceId: decodeURIComponent(String(this.$route.query.sid)),
          service, service_type
        }
      }
    } else {
      this.$message.error(i18n.t('modules.views.appMonitor.resourceDetail.s_6e019641') as string);
    }
    this.detailLoading = false;
  }

  // tab change
  private tabChange (option: any) {
    const { activeName } = this.$route.query;
    const { value } = option;
    if (decodeURIComponent(String(activeName)) !== value) {
      this.$router.replace({
        query: { ...this.$route.query, activeName: value }
      })
    }
  }

  private jumpProfilingHandle () {
    const query: any = {
      sn: this.$route.query.sn,
      sid: this.$route.query.sid,
      resource: this.$route.query.endpoint,
    }
    if (this.$route.query.si) {
      query.si = this.$route.query.si
    }
    this.$router.push({
      path: '/appMonitor/hotMethods',
      query
    })
  }

  // 查看服务流
  private viewFlowHandle () {
    if (!this.resourceDetail) {
      return
    }
    const query: any = {
      serviceId: this.$route.query.sid,
      resource: this.$route.query.endpoint,
      service: this.$route.query.sn,
      componentType: this.$route.query.componentType
    }
    if (this.$route.query.si) {
      query.srcServiceInstance = this.$route.query.si
    }
    this.$router.push({
      path: '/appMonitor/serviceFlow',
      query
    })
  }

}
</script>
<style lang="scss" scoped>
.comp-cont {
  flex: 1;
  height: 100%;
  overflow: hidden;
  position: relative;
}
.comp-wrapper {
  height: 100%;
}
.comp-header {
  align-items: flex-start;
}
.comp-body {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding-top: 8px;
}
</style>