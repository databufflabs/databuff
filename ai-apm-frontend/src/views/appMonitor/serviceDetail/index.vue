<template>
  <div class="comp-cont p-16"
    v-loading='isLoading'>
    <div class="comp-wrapper p-16 bg-color flex-v">
      <div class="comp-header flex-h-jc">
        <div class="comp-header-title flex-h">
          <span v-if='!hasEventCnt' class="bg-green text-white p-5 line-height-1 font-12 br-2 mr-10">{{ $t('modules.components.db-table.s_fd6e80f1') }}</span>
          <span v-if='hasEventCnt' class="bg-red text-white p-5 line-height-1 font-12 br-2 mr-10">{{ $t('modules.components.db-table.s_c195df63') }}</span>
          <span
            class="db-icon mr-5 font-16 service-header-icon"
            :class="{ 'is-language': hasLanguageIcon(serviceDetail.language) }"
            :style="serviceHeaderIconStyle"
          >{{ getServiceType | DbIconFilter }}</span>
          <span class="font-16 fw-500 mr-10">{{ serviceDetail.name || serviceDetail.service || '-' }}</span>
        </div>
        <div class="comp-header-action">
          <el-button type="primary" size="small" @click="viewAnalysisHandle">
            <i class="db-icon db-icon-metrics font-12"></i>
            {{ $t('modules.views.appMonitor.response.s_598ea178') }}</el-button>
          <el-button
            v-if="!isEbpfSource"
            type="primary" size="small" @click="viewFlowHandle">
            <i class="db-icon db-icon-layout-horizontal font-12"></i>
            {{ $t('modules.views.appMonitor.response.s_54c1cb4b') }}</el-button>
          <el-button type="primary" size="small" @click="viewLogsAnalysisHandle">
            <i class="db-icon db-icon-log font-12"></i>
            {{ $t('modules.views.appMonitor.serviceDetail.s_40907441') }}</el-button>
        </div>
      </div>

      <div class="mt-10">
        <db-tabnav v-model='activeName' :tabnavs='tabnavByServiceType' @on-change='tabChange'></db-tabnav>
      </div>

      <!-- tab body -->
      <div class="comp-body">
        <component :is='activeName' :current='serviceDetail' @on-loaded='subCompLoadedHandle' :getDatabuffSource='getDatabuffSource' @on-created='subCompCreatedHandle' ref='subComp' />
      </div>
    </div>
  </div>
</template>
<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import i18n from '@/i18n';

import TabRelation from './tab-relation.vue';
import TabBaseinfo from './tab-baseinfo.vue';
import TabAlarm from './tab-alarm.vue';
import TabResource from './tab-resource.vue';
import TabJvm from './tab-jvm.vue';
import TabThreadpool from './tab-threadpool.vue';
import TabSql from './tab-sql.vue';
import TabLog from './tab-log.vue';
import TabNetwork from './tab-network.vue';
import ApmApi from '@/api/apm';
import { toAsyncWait } from '@/utils/common';
import { Getter } from 'vuex-class';
import {
  hasLanguageIcon,
  languageIconColor,
  resolveServiceDisplayIcon,
} from '@/utils/service-language';

@Component({
  components: {
    'tab-relation': TabRelation,
    'tab-baseinfo': TabBaseinfo,
    'tab-alarm': TabAlarm,
    'tab-resource': TabResource,
    'tab-jvm': TabJvm,
    'tab-threadpool': TabThreadpool,
    'tab-sql': TabSql,
    'tab-log': TabLog,
    'tab-network': TabNetwork,
  }
})
export default class ServiceDetail extends Vue {
  @Getter('User/hasNetworkMenu') public hasNetworkMenu!: boolean;

  private hasLanguageIcon = hasLanguageIcon

  // 监听上下游服务点击事件，重新获取服务详情
  @Watch('$route.query.sid')
  private async onServiceRouteQueryChange (newSid: string, oldSid: string) {
    if (!oldSid) {
      return
    }
    this.getServiceDetail();
    this.activeName = 'tab-relation';
  }
  @Watch('globalTimeV2', { deep: true })
  private watchGlobalTime() {
    this.refresh();
  }

  public $refs!: {
    subComp: any;
  }

  private detailLoading = true;
  private subCompLoading = true;
  
  private serviceDetail: any = {
    serviceId: '',
    name: '',
  };

  private tabnavs: any[] = [
    { label: i18n.t('modules.views.appMonitor.serviceDetail.s_718e0b79') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_718e0b79', value: 'tab-relation' },
    { label: i18n.t('modules.views.appMonitor.resourceDetail.s_6ea1fe6b') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_6ea1fe6b', value: 'tab-baseinfo' },
    { label: i18n.t('modules.views.appMonitor.serviceDetail.s_1ff73929') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_1ff73929', value: 'tab-alarm' },
    { label: i18n.t('modules.views.appMonitor.serviceDetail.s_244b8532') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_244b8532', value: 'tab-resource', match: ['web'] },
    { label: i18n.t('modules.views.appMonitor.serviceDetail.s_84d31a52') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_84d31a52', value: 'tab-jvm', match: ['web'] },
    { label: i18n.t('modules.views.appMonitor.serviceDetail.s_5248c536') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_5248c536', value: 'tab-sql', match: ['db'] },
    { label: i18n.t('modules.views.appMonitor.serviceDetail.s_828d5325') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_828d5325', value: 'tab-threadpool', match: ['web'] },
    { label: i18n.t('modules.views.appMonitor.serviceDetail.s_7ddbe15c') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_7ddbe15c', value: 'tab-network', match: ['web'] },
    { label: i18n.t('modules.views.appMonitor.serviceDetail.s_5c10b138') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_5c10b138', value: 'tab-log', match: ['web'] },
  ];

  get tabnavByServiceType () {
    const { service_type, technology } = this.serviceDetail || {};
    const datasource = this.getDatabuffSource.toLowerCase();
    let tabnavs = this.tabnavs.filter((i) => !i.match || i.match.includes(service_type));
    if (service_type === 'web') {
      if (datasource.indexOf('df-') !== 0 && datasource !== 'databuff') {
        tabnavs = tabnavs.filter((t) => t.value !== 'tab-resource' && t.value !== 'tab-log');
      }
      if (datasource !== 'df-javaagent' && datasource !== 'databuff') {
        tabnavs = tabnavs.filter((t) => t.value !== 'tab-threadpool');
      }
      if (String(technology || '').toLowerCase().indexOf('jvm') === -1) {
        tabnavs = tabnavs.filter((t) => t.value !== 'tab-jvm');
      }
    }
    return tabnavs;
  }

  private activeName = '';

  get getServiceType () {
    return resolveServiceDisplayIcon(
      this.serviceDetail?.type || this.serviceDetail?.service_type,
      this.serviceDetail?.language,
    )
  }

  get serviceHeaderIconStyle () {
    if (!hasLanguageIcon(this.serviceDetail?.language)) {
      return {}
    }
    return { color: languageIconColor(this.serviceDetail.language) }
  }

  get hasEventCnt () {
    return this.serviceDetail?.alarmCount > 0
  }

  get isLoading () {
    return this.detailLoading || this.subCompLoading
  }

  get getSeriviceMapInfo () {
    return this.$store.state.Service.basicServiceMap?.[this.serviceDetail.serviceId] || {}
  }

  get getDatabuffSource () {
    return String(this.getSeriviceMapInfo?.datasource || this.serviceDetail?.datasource || '')
  }

  get isEbpfSource () {
    return this.getDatabuffSource.toLowerCase() === 'df-ebpf';
  }

  private async created () {
    if (!this.hasNetworkMenu) {
      this.tabnavs = this.tabnavs.filter((t) => t.value !== 'tab-network');
    }
  }

  private async mounted () {
    this.$eventBus.$on('GlobalRefresh', this, () => {
      this.refresh();
    });
    await this.getServiceDetail();
    const { activeName } = this.$route.query;
    this.$nextTick(() => {
      const _activeName = decodeURIComponent(String(activeName));
      const hasActiveName = !!(this.tabnavByServiceType.find((i) => i.value === _activeName));
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

  private refresh () {
    this.getServiceDetail();
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
  private async getServiceDetail () {
    this.detailLoading = true;
    const { fromTime, toTime } = this.getGlobalTimeV2();
    const { sid, sn } = this.$route.query;
    const params = { fromTime, toTime, serviceId: decodeURIComponent(String(sid)) };
    const { error, result } = await toAsyncWait(ApmApi.getServiceDetail(params));
    if (!error && result.data) {
      this.serviceDetail = result?.data || { serviceId: decodeURIComponent(String(sid)), name: decodeURIComponent(String(sn)) };
    } else {
      this.$confirm(i18n.t('modules.views.appMonitor.serviceDetail.s_2ea3c0cd') as string, i18n.t('common.hint') as string, {
        confirmButtonText: i18n.t('modules.views.alarmCenter.alarmDetail.s_38cf16f2') as string, confirmButtonTextKey: 'modules.views.alarmCenter.alarmDetail.s_38cf16f2',
        closeOnClickModal: false,
        showCancelButton: false,
        showClose: false,
        type: 'warning'
      }).then(() => {
        this.$router.replace({
          path: '/appMonitor/service',
          query: { ...this.getRouteTimeOrRange }
        })
      })
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

  // 查看服务流
  private viewFlowHandle () {
    if (!this.serviceDetail?.serviceId) {
      return
    }
    const serviceId = this.serviceDetail.serviceId
    const serviceName = this.serviceDetail.service || this.serviceDetail.name
    this.$router.push({
      path: '/appMonitor/serviceFlow',
      query: {
        ...this.getRouteTimeOrRange,
        serviceId: encodeURIComponent(serviceId),
        sid: encodeURIComponent(serviceId),
        ...(serviceName ? { service: encodeURIComponent(serviceName) } : {}),
      }
    })
  }

  // 查看日志分析
  private viewLogsAnalysisHandle () {
    if (!this.serviceDetail?.serviceId) {
      return
    }
    this.$router.push({
      path: '/appMonitor/logs',
      query: {
        ...this.getRouteTimeOrRange,
        serviceIds: encodeURIComponent(this.serviceDetail.serviceId),
      },
    })
  }

  // 查看详细分析
  private viewAnalysisHandle () {
    const query: any = {
      ...this.getRouteTimeOrRange,
      sn: encodeURIComponent(this.serviceDetail.name || this.serviceDetail.service),
      sid: encodeURIComponent(this.serviceDetail.serviceId),
    }
    if (this.$route.path === '/appMonitor/database/detail') {
      query.tabType = encodeURIComponent('service.db')
      query.dbTarget = encodeURIComponent('1')
    }
    this.$router.push({
      path: '/appMonitor/serviceAnalysis',
      query,
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
.service-header-icon.is-language {
  font-size: 18px;
  line-height: 1;
}
.comp-body {
  flex: 1;
  overflow-y: auto;
  padding-top: 18px;
}
</style>