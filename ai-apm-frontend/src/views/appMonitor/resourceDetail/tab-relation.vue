<template>
  <div class="relation-cont">
    <div class="service-chain-info flex-h">
      <div class="prev-service flex-v" v-if='prevServices.length'>
        <div class="prev-service-wrapper flex-v">
          <div v-for='(value,key) in prevServiceTypeList' :key='key'
            @click="showServiceTypeList('prev', key)"
            :data-type="key"
            :class="['service-card-item', serviceListType === 'prev' && prevServiceListType === key ? 'active' : '']">
            <p>{{ value }}</p>
            <p class="ell">{{ key | serviceRequestTypeFilter }}</p>
            <span :class='["db-icon"]'>{{ getTypeIcon(key) }}</span>
          </div>
        </div>
      </div>
      <div class="service-chain-arrow" v-if='prevServices.length'></div>
      <div class="curr-service flex-v">
        <!-- <div class="request-overview mb-10">
          <div class="request-overview-info font-13 fw-500">
            <span class="mr-5">{{ avgRequest | NumberFilter }} req/s</span>
            <span class="">{{ $t('modules.views.appMonitor.resourceDetail.s_60d035cc') }}</span>
          </div>
          <div class="request-overview-chart">
            <ellipse-chart
              v-if='showEllipseChart'
              :source='requestCountSource'
              :colors="['#5582FF']"
            />
          </div>
        </div> -->
        <div :class="['curr-service-info cp', serviceListType === 'curr' ? 'active' : '']"
          @click="showServiceTypeList('curr')">
          <p class="current-service-label">{{ $t('modules.views.appMonitor.resourceDetail.s_59671839') }}</p>
        </div>
      </div>
      <div class="service-chain-arrow" v-if='nextServices.length'></div>
      <div class="next-service" v-if='nextServices.length'>
        <div class="next-service-wrapper flex-v">
          <div v-for='(value,key) in nextServiceTypeList' :key='key'
            @click="showServiceTypeList('next', key)"
            :data-type="key"
            :class="['next-service-item service-card-item', serviceListType === 'next' && nextServiceListType === key ? 'active' : '']">
            <p>{{ value }}</p>
            <p class="ell">{{ key | serviceRequestTypeFilter }}</p>
            <span :class='["db-icon"]'>{{ getTypeIcon(key) }}</span>
          </div>
        </div>
      </div>
    </div>

    <div class="service-list-info">
      <db-table
        :data='serviceTypeList'
        :columnConfig='getColumnConfig'
        tableKey='APM_RESOURCE_DETAIL_LIST'
        ref='listTable'>
        <template slot='service' slot-scope="{ row }">
          <span class="db-icon mr-5 vm">{{ (row.typeIcon || 'default') | DbIconFilter }}</span>{{ row.service || '-' }}
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

        <template slot="suffix" v-if='serviceListType !== "curr"'>
          <el-table-column :label="$t('modules.views.aiPlatform.experts.s_2b6bc0f2')">
            <template slot-scope="{ row }">
              <span @click.stop="viewServiceCallHandle(row)" class="db-blue cphu">{{ $t('modules.views.appMonitor.resourceDetail.s_9fd2acba') }}</span>
            </template>
          </el-table-column>
        </template>

      </db-table>
    </div>
  </div>
</template>

<script lang='ts'>
import { toAsyncWait } from '@/utils/common';
import i18n from '@/i18n';
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import EllipseChart from '@/views/appMonitor/serviceDetail/bar-stack.vue';
import ServiceApi from '@/api/service';
import ApmApi from '@/api/apm';
import { decodePortalServiceId } from '@/utils/portal-service-query';
import { ServiceTypeFilter, RequestTypeFilter } from '@/utils/filters/service';
import dayjs from 'dayjs';
import { DbIconFilter } from '@/utils/filters/common';
import { StringIsEmpty } from '@/utils/common';
 
const RequestTypeMapping: any = {
  'service.http': i18n.t('modules.views.appMonitor.resourceDetail.s_669262cd') as string,
  'service.rpc': i18n.t('modules.views.appMonitor.resourceDetail.s_4111408b') as string,
  'service.mq': i18n.t('modules.views.appMonitor.resourceDetail.s_82375db4') as string,
  'service.db': i18n.t('modules.views.appMonitor.resourceDetail.s_b3bf0a2d') as string,
  'service.redis': i18n.t('modules.views.appMonitor.resourceDetail.s_218e2ad9') as string,
  'service.config': i18n.t('modules.views.appMonitor.resourceDetail.s_88bdaf32') as string,
  'service.remote': i18n.t('modules.views.appMonitor.resourceDetail.s_71f31c96') as string,
  'service.other': i18n.t('modules.views.appMonitor.resourceDetail.s_b5667e29') as string,
  'service.browser': i18n.t('modules.views.appMonitor.resourceDetail.s_125606d3') as string,
  'service.ios': i18n.t('modules.views.appMonitor.resourceDetail.s_15999355') as string,
  'service.android': i18n.t('modules.views.appMonitor.resourceDetail.s_f33b9b2e') as string,
}

const getRequestTypeAndCount = (list: any) => {
  const _types: any = {}
  const requestTypes = Object.keys(RequestTypeMapping);
  requestTypes.forEach((type: string) => {
    const count = list.filter((item: any) => item.type === type).length
    if (count) {
      _types[type] = count
    }
  });
  list.filter((item: any) => !requestTypes.includes(item.type)).forEach((item: any) => {
    _types[item.type] = (_types[item.type] || 0) + 1
  });
  return _types;
}

@Component({
  components: {
    EllipseChart,
  },
  filters: {
    serviceRequestTypeFilter (type: string) {
      const _type = ServiceTypeFilter(type)
      if (_type !== type) {
        return _type
      }
      return RequestTypeFilter(type)
    },
  }
})
export default class TabRelation extends Vue {
  @Prop({ default: {} }) private current!: any;
  @Prop({ default: '' }) private componentType!: any;
  @Prop({ default: {} }) private queryParams!: any;

  @Watch('current')
  private onCurrentChange (val: any, oldVal: any) {
    if (val && val.serviceId && val?.serviceId !== oldVal?.serviceId && val?.resource !== oldVal?.resource && this.isMounted) {
      // console.log('current change')
      // this.fetchAllData();
    }
  }

  @Watch('$route.query')
  private onEndpoint (newVal: any, oldVal: any) {
    if (newVal.endpoint !== oldVal.endpoint || newVal.sid !== oldVal.sid) {
      this.serviceListType = 'curr';
      this.prevServiceListType = '';
      this.nextServiceListType = '';
    }
  }
  
  private loading = {
    relation: false,
    trend: false,
    list: false,
  }

  get isLoading () {
    const { relation, trend, list } = this.loading;
    return relation || trend || list;
  }

  @Watch('isLoading')
  private onIsLoading (newVal: boolean) {
    if (!newVal) {
      this.$emit('on-loaded')
    }
  }

  private isMounted = false;


  // prev curr next
  private serviceListType: 'curr'|'prev'|'next'|'' = 'curr';
  private prevServices: any[] = [];
  private prevServiceListType = '';
  private currServices: any[] = [];
  private nextServiceListType = '';
  private nextServices: any[] = [];


  // 近一小时趋势图
  private requestCountSource: any[] = [];

  private serviceTypeListModel = true;

  // 初始化完上下游的数据后再控制缩略图显示
  private showEllipseChart = false;
  // 当前服务平均请求
  private avgRequest = 0;

  get prevServiceTypeList () {
    return getRequestTypeAndCount(this.prevServices)
  }
  get nextServiceTypeList () {
    return getRequestTypeAndCount(this.nextServices)
  }

  get getServiceOriginType () {
    return this.current?.service_type || '-'
  }
  get getServiceType () {
    return this.current?.type || '-'
  }

  get serviceTypeList () {
    switch (this.serviceListType) {
      case 'prev':
        return this.prevServices.filter((item: any) => item.type === this.prevServiceListType);
      case 'next':
        return this.nextServices.filter((item: any) => item.type === this.nextServiceListType);
      case 'curr':
        return this.currServices;
      default:
        return [];
    }
  }

  // 获取服务列表columns
  get getColumnConfig () {
    let _columns: any[] = [
      { field: 'rate', label: i18n.t('modules.views.appMonitor.resourceDetail.s_acccc0cc') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_acccc0cc', slot: 'rate', width: 120, },
      { field: 'service', label: i18n.t('modules.views.alarmCenter.alarm.s_8f3747c0') as string, labelKey: 'modules.views.alarmCenter.alarm.s_8f3747c0', slot: 'service', minWidth: 120, },
      { field: 'allCnt', label: i18n.t('modules.views.appMonitor.cache.s_8bc42b53') as string, labelKey: 'modules.views.appMonitor.cache.s_8bc42b53', unit: 'count', minWidth: 100, },
      { field: 'reqRate', label: i18n.t('modules.views.appMonitor.external.s_c0283020') as string, labelKey: 'modules.views.appMonitor.external.s_c0283020', unit: 'count', lessZeroOneKey: 'callCnt', suffix: i18n.t('modules.views.appMonitor.resourceDetail.s_bdf1a79c') as string, minWidth: 100, },
      { field: 'slowRate', label: i18n.t('modules.views.appMonitor.resourceDetail.s_16d61488') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_16d61488', unit: '%', minWidth: 100, },
      { field: 'errRate', label: i18n.t('modules.views.appMonitor.cache.s_0c8524d7') as string, labelKey: 'modules.views.appMonitor.cache.s_0c8524d7', unit: '%', minWidth: 100, },
      { field: 'avgTime', label: i18n.t('modules.views.appMonitor.cache.s_96a0c062') as string, labelKey: 'modules.views.appMonitor.cache.s_96a0c062', unit: 'ns', minWidth: 110, },
      { field: 'maxTime', label: i18n.t('modules.views.appMonitor.external.s_3bff553d') as string, labelKey: 'modules.views.appMonitor.external.s_3bff553d', unit: 'ns', minWidth: 110, },
    ];
    switch (this.serviceListType) {
      case 'prev':
        _columns.unshift({ field: 'resource', label: i18n.t('modules.views.appMonitor.resourceDetail.s_180de757') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_180de757', minWidth: 200, handleClick: this.viewOtherServiceHandle });
        break;
      case 'curr':
        _columns.unshift({ field: 'resource', label: i18n.t('modules.views.alarmCenter.eventDetail.s_34cab80c') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_34cab80c', minWidth: 200 });
        break;
      case 'next':
        _columns.unshift({ field: 'resource', label: i18n.t('modules.views.appMonitor.resourceDetail.s_23f39326') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_23f39326', minWidth: 200, handleClick: this.viewOtherServiceHandle });
        break;
      default:
        return [];
    }
    return _columns
  }

  private created () {
    this.$emit('on-created');
    const { group: routeGroup = 'curr', gtype: routeGtype = '' } = this.$route.query;
    this.showServiceTypeList((String(routeGroup) as any) || 'curr', routeGtype as any || '')
  }
  private async mounted () {
    if (this.current?.serviceId ) {
      this.refresh();
    }
    this.isMounted = true;
  }

  public refresh () {
    this.$nextTick(() => {
      this.fetchAllData();
    })
  }

  private async fetchAllData () {
    this.fetchServiceRelate();
    this.fetchRequestEllipseSource();
  }

  private async fetchServiceRelate () {
    this.loading.relation = true;
    this.loading.list = true;
    const { fromTime, toTime } = this.getGlobalTime();
    const params = {
      componentType: this.componentType,
      serviceId: decodePortalServiceId(this.queryParams.serviceId),
      ...(this.componentType === 'service.http'
        ? { url: this.queryParams.url }
        : { resource: this.queryParams.resource }),
      start: fromTime.valueOf(),
      end: toTime.valueOf(),
    }
    const { result, error } = await toAsyncWait(ApmApi.slowApiRelation(params));
    if (!error) {
      const { data = {} } = result;
      const { reqCnt = 0, upFlowResources = {}, downFlowResources = {}, currentResources = {} } = data
      this.avgRequest = reqCnt || 0;
      const basicServiceMap = this.$store.getters['Service/basicServiceMap']
      const getServices = (list: any) => {
        const typesList = Object.values(list || {}).flat();
        typesList.forEach((r: any) => {
          const basicService = basicServiceMap[r.serviceId] || {}
          r.type = r.componentType;
          r.normalCnt = r.allCnt - r.slowCnt - r.errCnt;
          r.normalPercent = r.allCnt ? ((r.normalCnt / r.allCnt) * 100).toFixed(2) : 0;
          r.slowPercent = r.allCnt ? ((r.slowCnt / r.allCnt) * 100).toFixed(2) : 0;
          r.errPercent = r.allCnt ? ((r.errCnt / r.allCnt) * 100).toFixed(2) : 0;
          r.typeIcon = basicService.type || basicService.language || basicService.service_type;
        })
        return typesList;
      }
      this.prevServices = getServices(upFlowResources);
      this.nextServices = getServices(downFlowResources);
      this.currServices = getServices(currentResources);
    }
    this.$nextTick(() => {
      this.showEllipseChart = true;
      this.loading.relation = false;
      this.loading.list = false;
    })
  }

  // 获取请求趋势缩略图
  private async fetchRequestEllipseSource (params: any = {}) {
    this.loading.trend = true
    const _params: any = {
      ...this.queryParams,
      componentType: this.componentType,
      fromTime: dayjs(+new Date() - 1000 * 3660).format('YYYY-MM-DD HH:mm') + ':00',
      toTime: dayjs(+new Date() - 1000 * 60).format('YYYY-MM-DD HH:mm') + ':00',
      interval: 60,
      graphStats: ['callCnts'],
    }
    const { result, error } = await toAsyncWait(ApmApi.getServiceGraph(_params))
    if (!error) {
      const { data = {} } = result
      const { callCnts = {} } = data || {}
      this.requestCountSource = [{
        name: i18n.t('modules.views.appMonitor.relationMap.s_ae1e7b60') as string, nameKey: 'modules.views.appMonitor.relationMap.s_ae1e7b60',
        data: Object.entries(callCnts).map(([timestamp, value]) => ({
          key: dayjs(Number(timestamp)).format('YYYY-MM-DD HH:mm'),
          value
        }))
      }]
    }
    this.loading.trend = false
  }

  private getTypeIcon (type: string) {
    let _type = ServiceTypeFilter(type);

    if (_type !== type) {
      _type = type;
    } else {
      _type = RequestTypeFilter(type);
    }
    console.log(_type)
    return DbIconFilter(_type);
  }

  // 展示服务链列表
  private showServiceTypeList (group: 'curr'|'prev'|'next'|'', serviceType: string) {
    const query = { ...this.$route.query };
    const { group: routeGroup, gtype: routeGtype } = this.$route.query;
    
    if (this.serviceListType !== group) {
      this.serviceListType = group;

      if (serviceType) {
        this.nextServiceListType = serviceType;
        this.prevServiceListType = serviceType;
      }
      this.serviceTypeListModel = true;
    } else {
      if ((this.serviceListType === 'prev' && serviceType !== this.prevServiceListType) ||
        (this.serviceListType === 'next' && serviceType !== this.nextServiceListType)) {
        this.nextServiceListType = serviceType;
        this.prevServiceListType = serviceType;
        this.serviceTypeListModel = true;
      }
    }

    if (routeGroup !== group || routeGtype !== serviceType ) {
      query.group = group;
      query.gtype = serviceType;
      if (group === 'curr') {
        delete query.gtype
      }
      this.$router.replace({ query: { ...query } });
    }
    
  }

  // 查看调用分析
  private viewServiceCallHandle (row: any) {
    const query: any = {
      ...this.getRouteTimeOrRange,
      componentType: row.componentType,
    }
    if (this.serviceListType === 'prev') {
      query.srcSid = encodeURIComponent(row.serviceId)
      query.srcSn = encodeURIComponent(row.service)
      query.srcSt = row.serviceType
      query.sid = encodeURIComponent(this.current.serviceId)
      query.sn = encodeURIComponent(this.current.name || this.current.service)
      query.st = this.current.service_type
      query.serviceInstance = encodeURIComponent(this.current.serviceInstance)
      query.resourceQuery = this.$route.query.endpoint
    } else {
      query.srcSid = encodeURIComponent(this.current.serviceId)
      query.srcSn = encodeURIComponent(this.current.name || this.current.service)
      query.srcSt = this.current.service_type
      query.sid = encodeURIComponent(row.serviceId)
      query.sn = encodeURIComponent(row.service)
      query.st = row.serviceType
      query.srcServiceInstance = encodeURIComponent(this.current.serviceInstance)
      query.rootResourceQuery = this.$route.query.endpoint
    }
    for (const key in query) {
      if (StringIsEmpty(query[key]) || query[key] === 'undefined') {
        delete query[key]
      }
    }
    this.$router.push({
      path: '/appMonitor/serviceCall',
      query: { ...query }
    });
  }

  // 查看服务详情
  private viewOtherServiceHandle (row: any) {
    this.$router.push({
      path: '/appMonitor/resourceDetail',
      query: {
        ...this.getRouteTimeOrRange,
        sid: encodeURIComponent(row.serviceId),
        endpoint: encodeURIComponent(row.resource),
        componentType: row.componentType
      }
    });
  }

  // 跳转到服务实例详情
  private viewServiceInstanceHandle (row: any) {
    this.$router.push({
      path: '/appMonitor/serviceInstance',
      query: {
        ...this.getRouteTimeOrRange,
        sn: encodeURIComponent(this.current?.service || this.current?.serviceName),
        sid: encodeURIComponent(this.current?.serviceId),
        si: encodeURIComponent(row.serviceInstance),
      }
    });
  }
  // 跳转到主机详情
  private viewHostDetail (row: any) {
    this.$router.push({
      path: '/infrastructure/hostDetail',
      query: { hostName: encodeURIComponent(row.hostName || row.host) }
    })
  }
  // 跳转到进程详情
  private viewProcessDetail (row: any) {
    this.$router.push({
      path: '/infrastructure/processDetail',
      query: {
        processName: encodeURIComponent(row.pname),
        hostName: encodeURIComponent(row.hostName),
      }
    })
  }
  // 跳转到容器详情
  private viewContainerDetail (row: any) {
    this.$router.push({
      path: '/infrastructure/dockerDetail',
      query: {
        containerId: encodeURIComponent(row.containerId || ''),
      }
    })
  }
}
</script>
<style lang='scss' scoped>
$card-bg: #E6ECFC;
$card-active-bg: #2962FF;
$card-icon-color: #5582FF;
$card-icon-active-color: #FFFFFF;
$card-side-width: 200px;
$card-width: 280px;
$card-height: 68px;

.relation-cont {
  overflow: hidden;
  position: relative;
}
.service-chain-info {
  position: relative;
  justify-content: center;
  padding-bottom: 14px;
  height: 316px;
  transition: height .35s ease;
  .prev-service {
    flex: none;
    position: relative;
    max-height: 302px;
    overflow-y: auto;
    overflow-x: hidden;
  }
  .curr-service {
    justify-content: center;
    align-items: center;
    overflow: hidden;
    .curr-service-info {
      width: $card-width;
      height: $card-height;
      border-radius: 4px;
      line-height: 24px;
      font-size: 12px;
      background-color: $card-bg;
      transition: background-color .3s ease;
      position: relative;
      display: flex;
      align-items: center;
      justify-content: center;

      & > p {
        font-size: 13px;
        font-weight: 500;
        // margin: 15px 0 6px 150px;
        text-align: center;
        line-height: 16px;
      }
      

      & > .db-icon {
        font-size: 33px;
        position: absolute;
        left: 100px;
        top: 17px;
        color: $card-icon-color;
      }

      &.active {
        color: #fff;
        background-color: $card-active-bg;
        .db-icon {
          color: $card-icon-active-color;
        }
      }

      p {
        transition: color .3s ease;
      }
    }
    .request-overview {
      background-color: $card-bg;
      border-radius: 4px;
      overflow: hidden;
      height: $card-height;
    }
    .request-overview-info {
      height: 30px;
      line-height: 30px;
      text-align: center;
    }
    .request-overview-chart {
      width: $card-width;
      height: calc( $card-height - 30px );
      padding: 0 3px 3px;
      overflow: hidden;
    }
  }
  .next-service {
    flex: none;
    position: relative;
    max-height: 302px;
    overflow-y: auto;
    overflow-x: hidden;
  }

  .service-card-item {
    width: $card-side-width;
    height: $card-height;
    border-radius: 4px;
    background-color: $card-bg;
    cursor: pointer;
    transition: background-color .3s ease;
    position: relative;

    &:not(:last-of-type) {
      margin-bottom: 10px;
    }

    & > p:first-of-type {
      font-size: 16px;
      font-weight: 500;
      margin: 15px 0 6px 108px;
      line-height: 16px;
    }
    & > p:last-of-type {
      font-size: 13px;
      margin-left: 108px;
      line-height: 14px;
    }

    & > .db-icon {
      font-size: 33px;
      position: absolute;
      left: 60px;
      top: 17px;
      color: $card-icon-color;
    }

    &.active {
      background-color: $card-active-bg;
      & > p {
        color: #fff;
      }
      & > .db-icon {
        color: $card-icon-active-color;
      }
    }

    p {
      transition: color .3s ease;
      margin: 0;
      color: var(--color-text-primary);
    }
  }
  .service-chain-arrow {
    margin: 0 20px;
    width: 0;
    height: 0;
    border-style: solid;
    border: 1px solid transparent;
    border-width: 12px 0 12px 24px;
    border-color: transparent transparent transparent #D4D4D4;
    border-radius: 4px;
  }
}
.service-list-info {
  height: 286px;
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
</style>