<template>
  <div class="comp-cont p-16"
    v-loading='isLoading'>
    <div class="comp-wrapper p-16 bg-color flex-v">
      <div class="comp-header flex-h-jc">
        <div class="comp-header-title flex-h">
          <span v-if='!hasEventCnt' class="bg-green text-white p-5 line-height-1 font-12 br-2 mr-10">{{ $t('modules.components.db-table.s_fd6e80f1') }}</span>
          <span v-if='hasEventCnt' class="bg-red text-white p-5 line-height-1 font-12 br-2 mr-10">{{ $t('modules.components.db-table.s_c195df63') }}</span>
          <span class="db-icon mr-5 font-14">{{ getServiceType | DbIconFilter }}</span>
          <span class="font-16 fw-500 mr-10">{{ serviceDetail.serviceInstance || '-' }}</span>
          <span>{{ $t('modules.views.appMonitor.serviceInstance.s_be464152', { value0: getService }) }}</span>
        </div>
        <div class="comp-header-action">
          <el-button type="primary" size="small" @click="viewAnalysisHandle">
            <i class="db-icon db-icon-metrics font-12"></i>
            {{ $t('modules.views.appMonitor.response.s_598ea178') }}</el-button>
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
        <component :is='activeName' :current='serviceDetail' :serviceInstanceList='serviceInstanceList' :getDatabuffSource='getDatabuffSource' @on-loaded='subCompLoadedHandle' @on-created='subCompCreatedHandle' ref='subComp' />
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
import { serviceNameReg } from '@/utils/regexp';
import ServiceApi from '@/api/service';
import ApmApi from '@/api/apm';
import { toAsyncWait } from '@/utils/common';

@Component({
  components: {
    'tab-relation': TabRelation,
    'tab-baseinfo': TabBaseinfo,
    'tab-alarm': TabAlarm,
    'tab-resource': TabResource,
    'tab-jvm': TabJvm,
    'tab-threadpool': TabThreadpool,
    'tab-sql': TabSql,
  }
})
export default class ServiceDetail extends Vue {

  // 监听上下游服务点击事件，重新获取服务详情
  @Watch('$route.query.si')
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

  get getSeriviceMapInfo () {
    return this.$store.state.Service.basicServiceMap?.[this.serviceDetail.serviceId] || {}
  }

  get getDatabuffSource () {
    return String(this.getSeriviceMapInfo?.datasource || this.serviceDetail?.datasource || '')
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

  private serviceInstanceList: any[] = [];

  private tabnavs: any[] = [
    { label: i18n.t('modules.views.appMonitor.serviceDetail.s_718e0b79') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_718e0b79', value: 'tab-relation' },
    { label: i18n.t('modules.views.appMonitor.resourceDetail.s_6ea1fe6b') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_6ea1fe6b', value: 'tab-baseinfo' },
    { label: i18n.t('modules.views.appMonitor.serviceDetail.s_1ff73929') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_1ff73929', value: 'tab-alarm' },
    { label: i18n.t('modules.views.appMonitor.serviceDetail.s_244b8532') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_244b8532', value: 'tab-resource', match: ['web'] },
    { label: i18n.t('modules.views.appMonitor.serviceDetail.s_84d31a52') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_84d31a52', value: 'tab-jvm', match: ['web'] },
    { label: i18n.t('modules.views.appMonitor.serviceDetail.s_5248c536') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_5248c536', value: 'tab-sql', match: ['db'] },
    { label: i18n.t('modules.views.appMonitor.serviceDetail.s_828d5325') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_828d5325', value: 'tab-threadpool', match: ['web'] },
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

  private timeParams = {
    fromTime: '',
    toTime: '',
  }
  

  get getServiceType () {
    return this.serviceDetail?.type || this.serviceDetail?.language || this.serviceDetail?.service_type || 'default';
  }

  get hasEventCnt () {
    return this.serviceDetail?.alarmCount > 0
  }

  get getService () {
    return this.serviceDetail?.name || this.serviceDetail?.service
  }

  get isLoading () {
    return this.detailLoading || this.subCompLoading
  }

  private async created () {
    const { sid } = this.$route.query;
    this.$store.commit('UPDATE_BREADCRUMB', [{
      path: '/appMonitor/serviceDetail',
      query: {
        ...this.getRouteTimeOrRange,
        sid
      },
    }]);
  }

  private async mounted () {
    this.$eventBus.$on('GlobalRefresh', this, () => {
      this.refresh();
    });
    await this.getServiceDetail();
    const { activeName } = this.$route.query;
    const { fromTime, toTime } = this.getGlobalTimeV2()
    this.timeParams = { fromTime, toTime };
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
    const { sid, sn, si } = this.$route.query;
    const serviceId = decodeURIComponent(String(sid));
    const serviceName = decodeURIComponent(String(sn));
    const serviceInstance = decodeURIComponent(String(si));
    const params = {
      start: new Date(fromTime).valueOf(),
      end: new Date(toTime).valueOf(),
      serviceId: String(sid),
      serviceInstance: String(si)
    };
    const { error, result } = await toAsyncWait(ServiceApi.getServiceInstance(params));
    if (!error && Array.isArray(result.data) && result.data.length) {
      this.serviceInstanceList = result.data || [];
      const [_si] = result.data;
      const service = _si?.traceServiceEntity || {}
      const _hostIp = _si?.hostIp !== 'unknown' ? _si.hostIp : ''
      const _hostName = _si?.hostName !== 'unknown' ? _si.hostName : ''

      this.serviceDetail = {
        ...service,
        ..._si,
        type: service.type,
        service_type: service.service_type,
        hostIp: _hostIp,
        hostName: _hostName,
        serviceId,
        serviceName,
        alarmCount: _si.alarmCount || 0,
      };
    } else {
      this.$confirm(i18n.t('modules.views.appMonitor.serviceInstance.s_741705a2') as string, i18n.t('common.hint') as string, {
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

  // 服务改名
  private editNameHandle (serviceName: any) {
    this.$prompt(i18n.t('modules.views.appMonitor.service.s_1d13d01c', { value0: serviceName }) as string, i18n.t('modules.views.appMonitor.service.s_4fbb4d92') as string, {
      customClass: 'edit-prompt-cont',
      confirmButtonText: i18n.t('modules.views.alarmCenter.alarmDetail.s_38cf16f2') as string, confirmButtonTextKey: 'modules.views.alarmCenter.alarmDetail.s_38cf16f2',
      cancelButtonText: i18n.t('modules.views.appMonitor.service.s_625fb26b') as string, cancelButtonTextKey: 'modules.views.appMonitor.service.s_625fb26b',
      inputValidator: (val) => {
        if (!val || !(val.trim())) {
          return i18n.t('modules.views.appMonitor.service.s_56540654') as string
        }
        if (val.length > 100 || val.length < 4) {
          return i18n.t('modules.views.appMonitor.service.s_2da0d8da') as string
        }
        if (val && !serviceNameReg.test(val)) {
          return i18n.t('modules.views.appMonitor.serviceInstance.s_f6542f67') as string
        }
        return true
      },
      inputPlaceholder: i18n.t('modules.views.appMonitor.service.s_d314caa1') as string, inputPlaceholderKey: 'modules.views.appMonitor.service.s_d314caa1',
      showClose: false,
      beforeClose: (action: string, instance: any, done: any) => {
        if (action === 'confirm') {
          instance.confirmButtonLoading = true;
          instance.confirmButtonText = i18n.t('modules.views.appMonitor.service.s_cf0cffe9') as string;
          const _params: any = {
            serviceId: this.serviceDetail?.serviceId,
            name: instance.inputValue,
          }
          // temp 异步
          ServiceApi.updateServiceName(_params).then((result: any) => {
            const { status, message = '' } = result
            if (status === 200 && message.toLowerCase() === 'success') {
              this.serviceDetail.name = instance.inputValue
              this.$message.success(i18n.t('modules.views.appMonitor.service.s_55aa6366') as string)
              done()
            } else {
              if (message !== 'interrupt') {
                this.$message.error(i18n.t('modules.views.appMonitor.service.s_ef74211b') as string)
              }
            }
          })
          .catch((err: any) => {
            if (err.message !== 'interrupt') {
              this.$message.error(i18n.t('modules.views.appMonitor.service.s_ef74211b') as string)
            }
          })
          .finally(() => {
            instance.confirmButtonText = i18n.t('modules.views.alarmCenter.alarmDetail.s_38cf16f2') as string;
            instance.confirmButtonLoading = false;
          })
        } else {
          done()
        }
      },
      callback: (action: string, instance: any) => {
        instance.confirmButtonLoading = false;
      }
    }).then(({ value }: any) => {
      // console.log(value)
    })
    .catch((err: any) => {
      console.log(err)
    })
  }

  // 查看日志分析
  private viewLogsAnalysisHandle () {
    const serviceId = this.serviceDetail?.serviceId || decodeURIComponent(String(this.$route.query.sid || ''));
    const serviceInstance = this.serviceDetail?.serviceInstance || decodeURIComponent(String(this.$route.query.si || ''));
    if (!serviceId) {
      return
    }
    const query: Record<string, string> = {
      ...this.getRouteTimeOrRange,
      serviceIds: encodeURIComponent(serviceId),
    };
    if (serviceInstance) {
      query.serviceInstances = encodeURIComponent(serviceInstance);
    }
    this.$router.push({
      path: '/appMonitor/logs',
      query,
    })
  }

  // 查看详细分析
  private viewAnalysisHandle () {
    this.$router.push({
      path: '/appMonitor/serviceAnalysis',
      query: {
        ...this.getRouteTimeOrRange,
        sn: encodeURIComponent(this.serviceDetail.name || this.serviceDetail.service),
        sid: encodeURIComponent(this.serviceDetail.serviceId),
      },
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
  padding-top: 18px;
}
</style>