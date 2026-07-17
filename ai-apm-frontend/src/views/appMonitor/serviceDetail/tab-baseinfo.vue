<template>
  <div class="baseinfo-cont">
    <div class="baseinfo-wrapper">
      <div>
        <db-radio v-model='avgModel' :options='avgOptions' @change='changeAvgHandle'></db-radio>
      </div>
      <div class="chart-group" :class="`chart-group-${avgModel}`">
        <template v-for='value,key in chartGroup'>
          <div :key='`${avgModel}_${key}`' class="chart-item br-4">
            <h3 class="fw-normal font-14 chart-title">{{ key | chartTitleFilter }}</h3>
            <basic-chart
              :showEmpty="!chartGroup[key].loading && !chartGroup[key].source.length"
              :showLegend='true'
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
                <ChartTsSlot v-if='avgModel === "service"' :current='currentTsItem' />
              </template>
            </basic-chart>
          </div>
        </template>
      </div>

      <div class="tag-group mt-20">
        <h3 class="m-0 fw-normal font-14">{{ $t('modules.views.metrics.list.s_1f7be0a9') }}</h3>
        <div class="mt-10 flex-h tag-group-wrapper">
          <span v-for='tag, idx in customTags' :key='tag' @click="handleCopy(tag)" :class='["tag-item br-4", !deleteTags.includes(tag) ? "" : "is-loading"]'>
            <span class="tag-item-label ell">{{ tag }}</span>
            <i @click.stop="deleteTagHandle(tag, idx)" :class='["tag-item-delete cp", !deleteTags.includes(tag) ? "db-icon db-icon-close-pie" : "el-icon el-icon-loading"]'></i>
          </span>
          <el-button @click="showAddLabelHandle" icon='el-icon-plus' size="small" type="primary">{{ $t('modules.views.infrastructure.hostDetail.s_14d34236') }}</el-button>
        </div>
      </div>

      <div class="attribute-group mt-20">
        <h3 class="m-0 fw-normal font-14">{{ $t('modules.views.appMonitor.resourceDetail.s_c5ea2ca1') }}</h3>
        <div class="mt-10 attribute-group-wrapper">
          <div class="attribute-item">
            <label class="attribute-item-label">
              <i></i>
              <span>{{ getServiceShowTitle }}</span>
            </label>
            <span class="">{{ getServiceName || '-' }}</span>
          </div>
          <div class="attribute-item">
            <label class="attribute-item-label">
              <i></i>
              <span>{{ getServiceOriginTitle }}</span>
            </label>
            <span class="">{{ getServiceOriginName || '-' }}</span>
          </div>
          <div class="attribute-item">
            <label class="attribute-item-label">
              <i></i>
              <span>{{ getServiceTypeTitle }}</span>
            </label>
            <span class="">{{ getServiceOriginType | ServiceTypeFilter }}</span>
          </div>
          <div class="attribute-item">
            <label class="attribute-item-label">
              <i></i>
              <span>{{ getServiceTechTitle }}</span>
            </label>
            <span class="">
              <el-tag v-for='tag in getServiceTechnology' :key='tag' type='info' effect="plain" size="mini" class="mr-5 mb-5">{{ tag }}</el-tag>
              <span v-if='!getServiceTechnology.length'>-</span>
            </span>
          </div>
          <div class="attribute-item">
            <label class="attribute-item-label">
              <i></i>
              <span>{{ $t('modules.components.s_295bb704') }}</span>
            </label>
            <span v-if="getLanguage && getLanguage !== '-'" class="language-badge">
              <span
                v-if="hasLanguageIcon(getLanguage)"
                class="db-icon language-icon mr-5"
                :style="{ color: languageIconColor(getLanguage) }"
              >{{ resolveLanguageIcon(getLanguage) | DbIconFilter }}</span>
              <span>{{ languageDisplayName(getLanguage) || getLanguage }}</span>
            </span>
            <span v-else>-</span>
          </div>
          <div class="attribute-item">
            <label class="attribute-item-label">
              <i></i>
              <span>{{ $t('modules.views.appMonitor.serviceDetail.s_36041bb6') }}</span>
            </label>
            <span class="">{{ getRunTime || '-' }}</span>
          </div>
          <div class="attribute-item">
            <label class="attribute-item-label">
              <i></i>
              <span>{{ $t('modules.views.appMonitor.serviceDetail.s_7f1affec') }}</span>
            </label>
            <span class="">{{ getRunTimeVersion || '-' }}</span>
          </div>
          <div v-if="getEnableStatus" class="attribute-item">
            <label class="attribute-item-label">
              <i></i>
              <span>{{ $t('modules.views.alarmCenter.alarm.s_f9d4e244') }}</span>
            </label>
            <span class="">
              <span v-if="domainList.length">
                <span v-for="item, index in domainList" :key="index">
                  <span class="mr-10">{{ item.name || '-' }}</span>
                </span>
              </span>
              <span v-else>-</span>
            </span>
          </div>
          <div class="attribute-item">
            <label class="attribute-item-label">
              <i></i>
              <span>{{ $t('modules.views.appMonitor.service.s_a094e5b7') }}</span>
            </label>
            <span class="">{{ displayDatasource || '-' }}</span>
          </div>
          <div v-if='showMidType' class="attribute-item">
            <label class="attribute-item-label">
              <i></i>
              <span>{{ $t('modules.views.appMonitor.cache.s_0c51881d') }}</span>
            </label>
            <span class="">{{ getMidType || '-' }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 添加标签弹窗 -->
    <tag-dialog
      ref="tagDialog"
      :saveTagApi="saveTagApi"
      :params="{ serviceIds: [current.serviceId] }"
      @on-saved="tagSaveHandle" />
  </div>
</template>

<script lang='ts'>
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import i18n from '@/i18n';
import dayjs from 'dayjs';
import { copy } from '@/utils/common';
import TagDialog from '@/views/infrastructure/host/tag-dialog.vue';
import { toAsyncWait } from '@/utils/common';
import ServiceApi from '@/api/service';
import ChartTsSlot from '@/views/appMonitor/serviceAnalysis/chart-ts-slot.vue';
import {
  hasLanguageIcon,
  languageDisplayName,
  languageIconColor,
  resolveLanguageIcon,
} from '@/utils/service-language';

const formatChartData = (data: any[], params: any, tagKey?: string) => {
  return data.map((item: any) => ({
    ...params,
    name: !tagKey ? params.name || '' : `${tagKey}:${(item?.tags || {})[tagKey] || ''}`,
    data: (item.values || []).map(([timestamp, value]: any) => ({
      key: dayjs(Number(timestamp)).format('YYYY-MM-DD HH:mm'),
      value,
    })),
  }))
}

@Component({
  components: {
    TagDialog, ChartTsSlot
  },
  filters: {
    chartTitleFilter (key: string) {
      switch (key) {
        case 'response':
          return i18n.t('modules.views.appMonitor.relationMap.s_207c26c9') as string;
        case 'error':
          return i18n.t('modules.views.appMonitor.cache.s_0c8524d7') as string;
        case 'request':
          return i18n.t('modules.views.appMonitor.cache.s_8bc42b53') as string;
      }
    }
  }
})
export default class TabBaseinfo extends Vue {
  @Prop({ default: {} }) private current!: any;
  @Prop() private getDatabuffSource!: string;

  public $refs!: {
    tagDialog: TagDialog
  }

  private hasLanguageIcon = hasLanguageIcon
  private languageDisplayName = languageDisplayName
  private languageIconColor = languageIconColor
  private resolveLanguageIcon = resolveLanguageIcon

  get getEnableStatus () {
    return this.$store.getters['User/getGroupEnabled']
  }

  @Watch('current', { immediate: true })
  private onCurrentChange (val: any, oldVal: any) {
    if (val && val?.serviceId !== oldVal?.serviceId && this.isMounted) {
      this.fetchAllData();
    }
    this.customTags = [...new Set(val?.tags?.custom || [])].filter(t => !!t) as string[]
  }

  private isMounted = false;

  private timeParams = {
    fromTime: '',
    toTime: '',
    interval: 60,
  }

  private avgOptions = [
    { label: i18n.t('modules.views.appMonitor.serviceDetail.s_7fa16e94') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_7fa16e94', value: 'service' },
    { label: i18n.t('modules.views.appMonitor.serviceDetail.s_1d0fd439') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_1d0fd439', value: 'top' },
  ]
  private avgModel = 'service';

  private chartGroup: any = {
    response: {
      loading: true,
      source: [],
      tsSource: [],
    },
    request: {
      loading: true,
      source: [],
      tsSource: [],
    },
    error: {
      loading: true,
      source: [],
      tsSource: [],
    },
  }

  private currentTsItem: any = null;

  get getServiceName () {
    return this.current && this.current.name || '-'
  }
  get getServiceOriginName () {
    return this.current && this.current.service || '-'
  }

  get getServiceType () {
    return this.current && this.current.type || '-'
  }

  get getServiceOriginType () {
    return this.current && this.current.service_type || '-'
  }

  get getServiceShowTitle () {
    switch (this.getServiceOriginType) {
      case 'db':
        return i18n.t('modules.views.appMonitor.serviceDetail.s_7f231057') as string
      case 'mq':
        return i18n.t('modules.views.appMonitor.serviceDetail.s_3752d7d6') as string
      default:
        return i18n.t('modules.views.appMonitor.serviceDetail.s_651d43b5') as string
    }
  }

  get getServiceOriginTitle () {
    switch (this.getServiceOriginType) {
      case 'db':
        return i18n.t('modules.views.appMonitor.serviceDetail.s_ddf57b8b') as string
      case 'mq':
        return i18n.t('modules.views.appMonitor.serviceDetail.s_0252e983') as string
      default:
        return i18n.t('modules.views.appMonitor.serviceDetail.s_3a06d4a1') as string
    }
  }

  get getServiceTypeTitle () {
    switch (this.getServiceOriginType) {
      case 'db':
      case 'mq':
        return i18n.t('modules.views.aiPlatform.experts.s_226b0912') as string
      default:
        return i18n.t('modules.views.appMonitor.service.s_924f67de') as string
    }
  }

  get getServiceTechTitle () {
    switch (this.getServiceOriginType) {
      case 'db':
      case 'mq':
        return i18n.t('modules.views.appMonitor.serviceDetail.s_a95dd3e1') as string
      default:
        return i18n.t('modules.views.appMonitor.serviceDetail.s_9fa3d3ad') as string
    }
  }

  get getServiceTechnology () {
    return this.current && this.current.technology && typeof this.current.technology === 'string' ? this.current.technology.split(',') : []
  }

  get domainList () {
    return (this.current || {}).domainManager || []
  }

  get displayDatasource () {
    return this.getDatabuffSource || this.current?.datasource || ''
  }

  get getLanguage () {
    return this.current?.language || '-'
  }
  get getRunTime () {
    return this.current?.processRuntimeName || '-'
  }
  get getRunTimeVersion () {
    return this.current?.processRuntimeVersion || '-'
  }
  get showMidType () {
    return ['db', 'mq', 'cache'].includes(this.getServiceOriginType)
  }
  get getMidType () {
    return this.current?.type || '-'
  }

  get isLoading () {
    return this.chartGroup.response.loading || this.chartGroup.request.loading || this.chartGroup.error.loading
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
    Object.entries(this.chartGroup).forEach(([key, item]: any) => {
      item.source = []
    });
    this.resetTimeParams();
    this.fetchResponseSource();
    this.fetchErrorSource();
    this.fetchRequestSource();
  }

  private changeAvgHandle () {
    this.fetchAllData();
  }

  // 响应时间
  private async fetchResponseSource () {
    const serviceId = this.current?.serviceId || decodeURIComponent(String(this.$route.query.sid));
    const _params: any = {
      serviceId,
      startTime: this.timeParams.fromTime,
      endTime: this.timeParams.toTime,
      interval: this.timeParams.interval,
      type: this.avgModel,
    }
    this.chartGroup.response.loading = true;
    // 需请求两个指标
    const avgTimeRst = await toAsyncWait(ServiceApi.getServiceRequestMetric({ ..._params, metric: 'avgTime' }))
    const avgTimeData = (avgTimeRst?.result?.data || []).filter((t: any) => t?.values?.length)
    let reqCntData: any[] = [];
    if (_params.type !== 'top') {
      const reqCntRst = await toAsyncWait(ServiceApi.getServiceRequestMetric({ ..._params, metric: 'reqCount' }))
      reqCntData = (reqCntRst?.result?.data || []).filter((t: any) => t?.values?.length)
    }
    if (!avgTimeData.length && !reqCntData.length) {
      this.chartGroup.response.source = []
    } else {
      this.chartGroup.response.source = [
        ...formatChartData(avgTimeData, {
          name: i18n.t('modules.views.appMonitor.relationMap.s_207c26c9') as string, nameKey: 'modules.views.appMonitor.relationMap.s_207c26c9',
          type: 'line',
          area: true,
          unit: 'ns',
        }, _params.type !== 'top' ? '' : 'serviceInstance'),
        ...formatChartData(reqCntData, {
          name: i18n.t('modules.views.appMonitor.relationMap.s_ae1e7b60') as string, nameKey: 'modules.views.appMonitor.relationMap.s_ae1e7b60',
          type: 'bar',
        }, _params.type !== 'top' ? '' : 'serviceInstance'),
      ]
    }
    // v2.9.1 ++
    if (_params.type === 'service' && (avgTimeData?.length || reqCntData?.length)) {
      const avgRootDetails = (avgTimeData || []).map((i: any) => i?.rootDetails || []).filter((i: any) => i.length).flat();
      const reqRootDetails = (reqCntData || []).map((i: any) => i?.rootDetails || []).filter((i: any) => i.length).flat();
      const rootDetails =  avgRootDetails.length ? avgRootDetails : reqRootDetails.length ? reqRootDetails : [];
      const tsSource = rootDetails.filter((i: any) => i.abnormalStartTime && i.abnormalEndTime).map((i: any) => {
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
    this.chartGroup.response.loading = false;
  }
  // 错误率
  private async fetchErrorSource () {
    const serviceId = this.current?.serviceId || decodeURIComponent(String(this.$route.query.sid));
    const _params: any = {
      serviceId,
      startTime: this.timeParams.fromTime,
      endTime: this.timeParams.toTime,
      interval: this.timeParams.interval,
      type: this.avgModel,
    }
    this.chartGroup.error.loading = true;
    // 需请求两个指标
    const errorRateRst = await toAsyncWait(ServiceApi.getServiceRequestMetric({ ..._params, metric: 'errRate' }))
    const errorRateData = errorRateRst?.result?.data || []
    let typeErrorData: any[] = [];
    if (_params.type !== 'top') {
      const typeErrorRst = await toAsyncWait(ServiceApi.getServiceRequestMetric({ ..._params, metric: 'typeErrCount' }))
      typeErrorData = typeErrorRst?.result?.data || []
    }
    if (!typeErrorData.length && !errorRateData.length) {
      this.chartGroup.error.source = []
    } else {
      this.chartGroup.error.source = [
        ...formatChartData(typeErrorData, {
          type: 'bar',
          stack: 'total',
        }, 'errorType').filter(t => ({ ...t, name: t.name.split(':').slice(1).join(':') })),
        ...formatChartData(errorRateData, {
          name: i18n.t('modules.views.appMonitor.cache.s_0c8524d7') as string, nameKey: 'modules.views.appMonitor.cache.s_0c8524d7',
          type: 'line',
          area: true,
          unit: '%',
        }, _params.type !== 'top' ? '' : 'serviceInstance'),
      ]
    }
    // v2.9.1 ++
    if (_params.type === 'service' && (errorRateData?.length)) {
      const rootDetails = (errorRateData || []).map((i: any) => i?.rootDetails || []).flat();
      const tsSource = rootDetails.filter((i: any) => i.abnormalStartTime && i.abnormalEndTime).map((i: any) => {
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
    this.chartGroup.error.loading = false;
  }
  // 请求数
  private async fetchRequestSource () {
    const serviceId = this.current?.serviceId || decodeURIComponent(String(this.$route.query.sid));
    const _params: any = {
      serviceId,
      startTime: this.timeParams.fromTime,
      endTime: this.timeParams.toTime,
      interval: this.timeParams.interval,
      type: this.avgModel,
    }
    this.chartGroup.request.loading = true;
    // 需请求两个指标
    let successData: any[] = []
    let failedData: any[] = []
    let reqCntData: any[] = []
    if (_params.type !== 'top') {
      const successRst = await toAsyncWait(ServiceApi.getServiceRequestMetric({ ..._params, metric: 'succReqCount' }))
      successData = successRst?.result?.data || []
      const failedRst = await toAsyncWait(ServiceApi.getServiceRequestMetric({ ..._params, metric: 'errReqCount' }))
      failedData = failedRst?.result?.data || []
    } else {
      const reqCntRst = await toAsyncWait(ServiceApi.getServiceRequestMetric({ ..._params, metric: 'reqCount' }))
      reqCntData = (reqCntRst?.result?.data || []).filter((t: any) => t?.values?.length)
    }
    if (!successData.length && !failedData.length && !reqCntData.length) {
      this.chartGroup.request.source = []
    } else {
      this.chartGroup.request.source = [
        ...formatChartData(successData, {
          name: i18n.t('modules.views.alarmCenter.notice.s_330363df') as string, nameKey: 'modules.utils.filters.s_330363df',
          type: 'bar',
          stack: 'total',
          color: '#2962FF',
        }, _params.type !== 'top' ? '' : 'serviceInstance'),
        ...formatChartData(failedData, {
          name: i18n.t('modules.views.alarmCenter.notice.s_acd5cb84') as string, nameKey: 'modules.utils.filters.s_acd5cb84',
          type: 'bar',
          stack: 'total',
          color: '#F37370',
        }, _params.type !== 'top' ? '' : 'serviceInstance'),
        ...formatChartData(reqCntData, {
          type: 'bar',
          stack: 'total',
        }, _params.type !== 'top' ? '' : 'serviceInstance'),
      ]
    }
    // v2.9.1 ++
    if (_params.type === 'service' && (successData?.length || failedData?.length)) {
      // const rootDetails = new Array().concat((successData || []).map((i: any) => i?.rootDetails || []), (failedData || []).map((i: any) => i?.rootDetails || [])).flat()
      const avgRootDetails = (successData || []).map((i: any) => i?.rootDetails || []).filter((i: any) => i.length).flat();
      const reqRootDetails = (failedData || []).map((i: any) => i?.rootDetails || []).filter((i: any) => i.length).flat();
      const rootDetails =  avgRootDetails.length ? avgRootDetails : reqRootDetails.length ? reqRootDetails : [];
      const tsSource = rootDetails.filter((i: any) => i.abnormalStartTime && i.abnormalEndTime).map((i: any) => {
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
      this.chartGroup.request.tsSource = tsSource;
    } else {
      this.chartGroup.request.tsSource = [];
    }
    this.chartGroup.request.loading = false;
  }

  // 复制标签
  private handleCopy (tag: string) {
    copy(tag);
  }

  private tagLoading = false
  private saveTagApi = ServiceApi.addServicesLabel
  private customTags: string[] = []
  private deleteTags: string[] = []
  // 删除自定义标签
  private async deleteTagHandle (tag: string, index: number) {
    if (this.deleteTags.includes(tag)) {
      return
    }
    this.deleteTags.push(tag);
    const bacTags = [...this.customTags]
    bacTags.splice(index, 1);
    this.tagLoading = true;
    const { result, error } = await toAsyncWait(ServiceApi.addServicesLabel({
      cover: 1, // 覆盖模式
      serviceIds: [this.current.serviceId],
      tags: [...bacTags],
    }));
    this.tagLoading = false
    if (!error) {
      this.$message.success(i18n.t('modules.views.appMonitor.serviceDetail.s_a6d082e4') as string);
      this.deleteTags = this.deleteTags.filter(t => t !== tag);
      this.customTags = bacTags;
      this.current.tags = {
        ...this.current.tags,
        custom: [...this.customTags],
      }
    } else {
      this.$message.error(error.message || i18n.t('modules.views.appMonitor.serviceDetail.s_8a3997a3') as string);
    }
  }
  private showAddLabelHandle () {
    this.$refs.tagDialog.showHandle();
  }
  private tagSaveHandle (data: any) {
    this.customTags = [...new Set([...this.customTags, ...data.tags])]
    this.current.tags = {
      ...this.current.tags,
      custom: [...this.customTags],
    }
  }

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
    width: calc( 50% - 16px );
    height: 208px;
    overflow: hidden;
    margin: 8px 4px;
    padding: 35px 5px 15px;
    border: 1px solid var(--border-color-base);
    position: relative;

    &:nth-child(odd) {
      margin-right: 12px;
    }
  }
  .chart-title {
    position: absolute;
    top: 15px;
    left: 15px;
    line-height: 1;
    margin: 0;
  }

  &.chart-group-top .chart-item:nth-child(n+3) {
    width: calc((100% - 48px) / 3);
    margin-right: 12px;
  }
  &.chart-group-top .chart-item:last-child {
    margin-right: 4px;
  }
}
.tag-group {
  margin-left: 4px;
}
.tag-group-wrapper {
  flex-wrap: wrap;
}
.tag-item {
  padding: 8px 10px;
  line-height: 1;
  margin: 5px 10px 5px 0;
  box-shadow: 0px 2px 8px 0px rgba(139, 142, 147, 0.26);
  position: relative;
  font-size: 0;
  overflow: hidden;
  display: flex;
  flex: 0 1 auto;
  transition: box-shadow .3s ease;
  cursor: pointer;

  .tag-item-label {
    display: inline-block;
    width: 100%;
    font-size: 13px;
    line-height: 16px;
    transition: width .2s ease;
  }
  .tag-item-delete {
    font-size: 14px;
    position: absolute;
    top: 9px;
    right: -15px;
    opacity: 0;
    transition: all .3s ease;
    color: var(--color-text-secondary);
  }

  &:hover, &.is-loading {
    box-shadow: 0px 2px 8px 0px rgba(41, 98, 255, .3);

    .tag-item-label {
      width: calc( 100% - 12px );
    }
    .tag-item-delete {
      right: 8px;
      opacity: 1;

      &:hover {
        color: var(--color-danger);
      }
    }
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
    flex: none;
    margin-bottom: 12px;
  }
}
.language-badge {
  display: inline-flex;
  align-items: center;
}
.language-icon {
  font-size: 16px;
  line-height: 1;
}
.system-span {
  display: inline-block;
  line-height: 18px;
  &:not(:last-child) {
    margin-right: 10px;
  }
}
</style>