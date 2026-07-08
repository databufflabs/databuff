<template>
  <div
    v-loading='chartLoading'
    class="topo-cont-wrapper">
    <div class="topo-cont-top">
      <search-group
        ref="searchGroup"
        :serviceList="entryPointServices"
        :srcServiceInstanceList="srcServiceInstanceList"
        :resourceList="resourceList"
        :entryPointsMapping="entryPointsMapping"
        @on-change="searchChangeHandle" />
    </div>
    <div class="topo-cont-bottom">
      <div :class='["topo-wrap", { "topo-wrap-hidden": showEmpty || chartLoading }]'
        @click='hideMainGroupPopover'>
        <div id='serviceFlowDiv' class="topo"></div>
        <div v-if='showEmpty' class="empty-show describe">
          {{ queryParams.entrypointPathId ? $t('modules.views.appMonitor.errorDetail.s_21efd88b') : $t('modules.views.appMonitor.serviceFlow.s_b8ea9401')  }}
        </div>
      </div>
    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Watch } from 'vue-property-decorator';
import i18n from '@/i18n';
import { v4 as uuidv4 } from 'uuid';
import ServiceApi from '@/api/service';
import { toAsyncWait } from '@/utils/common'
import { FlowChart } from '../flowChart'
import deepClone from 'lodash/cloneDeep';
import { EventBus, MinNumZore } from '@/utils/common';
import { debounce } from '@/utils/common'
import SearchGroup from './search-group.vue';
import ServiceFilter, { ServiceFilterPayload } from './service-filter.vue'
import { FormatedSelected } from '@/components/query-filter/types/index.types';

let flowChartInstance: any = null;

@Component({
  components: {
    SearchGroup,
    // ServiceFilter,
  }
})

export default class TopoGraph extends Vue {
  @Watch('globalTimeV2', { deep: true })
  private watchGlobalTime() {
    this.durationChangeHandle()
  }

  public $refs!: {
    searchGroup: SearchGroup
  }

  get showEmpty() {
    const { nodes = [], edges = [] } = this.serviceSource || {};
    return !nodes.length && !edges.length && !this.chartLoading;
  }

  private timeParams = {
    fromTime: '',
    toTime: '',
  }

  private queryParams: any = {}

  private entryPointServices: any[] = []; // 入口服务列表
  private entryPointsMapping: any = {};
  private srcServiceInstanceList: any[] = []; // 入口服务实例列表
  private resourceList: any[] = []; // 请求列表

  private filterChain: ServiceFilterPayload[] = []

  private lastQueryFilterChain: ServiceFilterPayload[] = []

  // 根服务列表相关
  // { serviceId1: topoSource, serviceId2: topoSource, ... }
  private choosedServiceModel = ''

  private chartLoading = true;

  private serviceSource: any = {};

  private backupFlowData: any = {};

  private pathIdsGroup: string[][] = [];

  get getBasicServiceMap () {
    return this.$store.getters['Service/basicServiceMap']
  }

  private created () {
    const { sid } = this.$route.query
    if (sid) {
      this.choosedServiceModel = decodeURIComponent(String(sid));
    }
  }

  private mounted () {
    // 监听全局的手动刷新事件，created中会有不触发的情况
    // 子组件绑定会被覆盖
    this.$eventBus.$on('GlobalRefresh', this, () => {
      this.durationChangeHandle()
    });
    this.resizeHandler = debounce(() => {
      if (flowChartInstance) {
        this.flowChartResize()
      }
    }, 100)
    window.addEventListener('resize', this.resizeHandler)

    EventBus.$on('flow-node-filter', (data: any[]) => {
      const newFilterChain = data.map((i) => ({
        name: i.name, id: i?.serviceInfo?.serviceId, typeIcon: i?.serviceInfo?.serviceType || 'default', filters: [],
        resources: i?.serviceInfo?.resources || [],
        uid: i?.serviceInfo?.uid,
        serviceInstances: i?.serviceInfo?.serviceInstances || [],
      }));
      const newChain: ServiceFilterPayload[] = [];
      this.lastQueryFilterChain = deepClone([...this.filterChain]);
      newFilterChain.forEach((n, idx) => {
        const oldChainItem = this.lastQueryFilterChain[idx]
        if ( oldChainItem && oldChainItem.id === n.id && oldChainItem.uid === n.uid ) {
          newChain.push({ ...oldChainItem });
        } else {
          newChain.push(n)
        }
      });
      this.filterChain = newChain;
      this.formatLocalTreeByRemoteData();
    });

    this.durationChangeHandle()
  }

  private beforeDestroy () {
    // 清除监听全局刷新事件
    this.$eventBus.$off('GlobalRefresh')
    // 清空拓扑参数
    flowChartInstance?.destroy();
    flowChartInstance = null;
    EventBus.$off('flow-node-filter');
    window.removeEventListener('resize', this.resizeHandler)
  }

  // 时间范围改变
  private async durationChangeHandle () {
    this.regetGlobalTime();
    this.chartLoading = true;
    // 获取服务流的入口
    const params: any = { ...this.timeParams }
    // 根据下钻过来的服务获取入口
    const serviceId = this.$route.query.serviceId
    if (serviceId) {
      params.serviceId = decodeURIComponent(String(serviceId))
    }
    const resource = this.$route.query.resource
    if (resource) {
      params.resource = decodeURIComponent(String(resource))
    }
    const service = this.$route.query.service
    if (service) {
      params.service = decodeURIComponent(String(service))
    }
    const componentType = this.$route.query.componentType
    if (componentType) {
      params.componentType = decodeURIComponent(String(componentType))
    }
    const { error, result: endpointResult } = await toAsyncWait(ServiceApi.getServiceFlowEndpoint(params))
    if (error) {
      this.chartLoading = false;
      return
    }
    const entryPoints = ((endpointResult || {}).data || {}).entryPoints || []
    const entryPointsMapping: any = {}
    const entryPointServices: any[] = []
    entryPoints.forEach((item: any) => {
      entryPointServices.push({
        label: item.service,
        value: item.serviceId,
      })
      entryPointsMapping[item.serviceId] = {
        service: item.service,
        serviceId: item.serviceId,
        entrypointPathId: item.entrypointPathId,
      }
    });
    this.entryPointsMapping = entryPointsMapping;
    this.entryPointServices = entryPointServices;
    this.$nextTick(() => {
      this.$refs.searchGroup.init().then(async (data: any) => {
        this.queryParams = { ...data }
        this.$nextTick(() => {
          // 入口服务为空，不请求服务流数据
          if (!this.queryParams.entrypointPathId) {
            this.chartLoading = false
            this.clearFlowSource();
            this.$refs.searchGroup.setInitOver()
            return
          }
          this.getFlowData(true)
        })
      }).catch(() => {
        this.chartLoading = false;
      })
    })
  }

  // 搜索组件change
  private searchChangeHandle (data: any, sid?: string) {
    if (JSON.stringify(data) === JSON.stringify(this.queryParams)) {
      return
    }
    if (data.entrypointPathId !== this.queryParams.entrypointPathId) {
      this.filterChain = [];
      this.pathIdsGroup = [];
    }
    this.queryParams = { ...data }
    const isSidChange = this.choosedServiceModel !== sid;
    this.choosedServiceModel = sid || '';
    this.$nextTick(() => {
      // 入口服务为空，不请求服务流数据
      if (!this.queryParams.entrypointPathId) {
        this.clearFlowSource();
        return
      }
      this.getFlowData(isSidChange)
    })
  }

  private async getFlowData (resetFilter: boolean = false) {
    const params: any = {
      ...this.queryParams,
      ...this.timeParams,
    }
    if (this.filterChain && this.filterChain.length) {
      const _filterParams: any[] = [];
      this.filterChain.forEach((i) => {
        i.filters.forEach((f) => {
          _filterParams.push({
            pathId: i.uid,
            filterType: f.field,
            filterValue: f?.value[0],
          })
        });
      });
      params.filters = _filterParams;
      this.lastQueryFilterChain = deepClone([...this.filterChain]);
      // console.log([..._filterParams])
    }
    if (this.pathIdsGroup.length && this.pathIdsGroup.length > 1) {
      params.pathIds = [...new Set(this.pathIdsGroup.flat())];
    }
    this.chartLoading = true;
    const { result, error } = await toAsyncWait(ServiceApi.getServiceFlow(params))
    if (!error) {
      this.formatResultData(result.data || {}, resetFilter);
    } else {
      if (error.message !== 'interrupt') {
        this.$message.error(error.message || i18n.t('modules.views.appMonitor.serviceFlow.s_e05c1ca3') as string)
      }
    }
    this.chartLoading = false;
  }

  // 清空服务流数据
  private clearFlowSource () {
    this.srcServiceInstanceList = [];
    this.resourceList = [];
    // 格式化原数据
    this.formatFlowSource([]);
    // 格式化原数据
    this.formatOriginSource([])
  }

  // 处理服务流接口返回的数据
  private formatResultData (data: any, resetFilter: boolean = false) {
    const serviceFlows = data.serviceFlows || {}
    const firstSn = this.entryPointServices.map((i: any) => i.label)[0]
    if (!firstSn) {
      this.clearFlowSource();
      return
    }
    let targetServiceName = (this.entryPointServices.find((i: any) => i.value === this.choosedServiceModel) || {}).label
    // 如果是链路筛选后没数据，可能会清楚上次没数据的筛选项后重新获取到值，此时this.choosedServiceModel已经为空，需要手动还原
    if (!targetServiceName && this.filterChain.length && Object.keys(serviceFlows).length) {
      const [firstFilter] = this.filterChain
      const { id } = firstFilter;
      const _targetService = this.entryPointServices.find((i: any) => i.value === id)
      if (_targetService) {
        targetServiceName = _targetService.label;
        // this.choosedServiceModel = id;
      }
    }
    if (this.filterChain.length && targetServiceName && serviceFlows[targetServiceName]) {
      this.formatFilterChain(serviceFlows[targetServiceName])
    }
    const flowSource = [serviceFlows[targetServiceName] || serviceFlows[firstSn]].filter(t => !!t)
    flowSource.forEach((root: any) => {
      this.assignFlowNodeUuid(root)
    })
    this.applyFlowFilterItems(flowSource[0] || {}, resetFilter)
    // 格式化原数据
    this.formatFlowSource(deepClone(flowSource));
    // 格式化原数据
    this.formatOriginSource(flowSource)
    
  }

  private formatOriginSource (data: any[]) {
    // 解藕tree型数据
    // 子节点中可能存在同节点，需手动设置唯一id，否则会回流
    // 递归组装
    // data中可能存在多个根节点
    data.forEach((root) => {
      this.setUUIDDeep(root)
    })
    const _multiServiceSource: any = {};
    const level = 0;
    data.forEach((root, idx) => {
      const links: any[] = [];
      const nodes: any[] = [];
      root.isRoot = true;
      root.level = level;
      const durationInfo = root.serviceDurationRange || [];
      _multiServiceSource[root.serviceId] = this.deepFormat(root, links, nodes, durationInfo)
    })
    const [first]: any[] = Object.values(_multiServiceSource)
    const [firstId]: any[] = Object.keys(_multiServiceSource)
    if (first) {
      this.choosedServiceModel = firstId
      this.formatServiceSource(first as any)
    } else {
      this.choosedServiceModel = '';
      this.formatServiceSource({ nodes: [], links: [], origin: {} })
    }
  }

  private setUUIDDeep (node: any) {
    const _nodeKey = uuidv4();
    node.uuid = _nodeKey
    if (node.children) {
      node.children.forEach((child: any) => {
        this.setUUIDDeep(child);
      })
    }
  }

  private deepFormat (node: any, links: any[], nodes: any[], durationInfo: any[]) {
    node.id = node.uuid;
    node.key = node.uuid;
    node.label = node.name || node.service;

    nodes.push({
      ...node,
      key: node.uuid,
      id: node.uuid,
    })
    if (node.children) {
      node.children.forEach((child: any) => {
        child.isRoot = false;
        child.level = node.level + 1
        links.push({
          source: node.uuid,
          target:  child.uuid,
          id: uuidv4(),
          callPct: MinNumZore(child.callPct),
          avgSrcCall: MinNumZore(child.avgCall),
        });
        this.deepFormat(child, links, nodes, durationInfo)
      })
    }
    return {
      links,
      nodes,
      origin: node
    }
  }

  private assignFlowNodeUuid (node: any) {
    node._uuid = uuidv4();
    if (node.children) {
      node.children.forEach((child: any) => {
        this.assignFlowNodeUuid(child)
      })
    }
  }

  private formatServiceSource(source: { nodes: any[], links: any[], origin: any }) {
    if (!source || (!source.nodes && !source.links)) {
      return;
    }
    const { nodes, links, origin } = source;
    const graphNodes: any = [];
    nodes.forEach((node) => {
      graphNodes.push({
        ...node,
        key: node.id,
        id: node.id,
        name: node.name,
        ovalue: node.name,
        olabel: node.name,
        level: node.level,
        hostName: node.hostName || '',
        processName: node.processName || '',
        type: node.type || 'default',
        requestCount: MinNumZore(node.call) || 0,
        responseCount: MinNumZore(node.avgDuration) || 0,
        resPercent: MinNumZore(node.durationCvPct) || 0,
        isRoot: node.isRoot || false,
      });
    });

    const graphEdges: any = [];

    links.forEach((link) => {
      const graphLink: any = {
        ...link,
        id: link.id,
        source: link.source,
        target: link.target,
      };
      graphEdges.push({
        ...graphLink
      });
    });

    this.serviceSource = {
      nodes: graphNodes,
      edges: graphEdges,
    };
  }

  private formatFlowSource (source: any[]) {
    // 子节点中可能存在同节点，需手动设置唯一id，否则会回流
    // 递归组装
    // data中可能存在多个根节点
    // try {
      const _multiFlowChartSource: any = {};
      const level = 0;
      source.forEach((root) => {
        root.isRoot = true;
        root.level = level;
        _multiFlowChartSource[root.serviceId] = this.deepFormatFlowSource(root);
      })
      const [first]: any[] = Object.values(_multiFlowChartSource)
      if (!first || !Object.keys(first).length) {
        return
      }
      if (first) {
        const flowSource = first;
        this.backupFlowData = deepClone(first);
        if (!flowChartInstance) {
          // 获取dom宽高
          const { width, height } = document.querySelector('#serviceFlowDiv')!.getBoundingClientRect();
          flowChartInstance = new FlowChart({
            domId: 'serviceFlowDiv',
            source: flowSource,
            width,
            height,
          })
        } else {
          flowChartInstance.updateChart(flowSource);
        }
      } else {
        flowChartInstance.updateChart({id: ''});
      }
    // } catch (err) {
    //   this.$message.error(i18n.t('modules.views.appMonitor.serviceFlow.s_f2504503') as string)
    // }
    
  }

  private deepFormatFlowSource (source: any) {
    const {
      service, serviceId, avgCall = 0, outCall = 0, callPct = 0,  avgDuration = 0, call = 0, error = 0, durationCvPct = 0,
      uid, children, resources = [], serviceInstances = [], serviceInstanceMap = {}, pathIds = [] } = source;
    const { service_type, type, language } = (this.getBasicServiceMap || {})[serviceId] || {}
    const _source: any = {
      id: uuidv4(), name: service, key: uid,
      viewData: {
        reqCnt: MinNumZore(call), // 请求数
        response: MinNumZore(avgDuration), // 平均响应时间
        contribution: MinNumZore(durationCvPct), // 响应贡献度
        failCnt: MinNumZore(error), // 失败请求数
        outCnt: MinNumZore(outCall), // 外发请求数
        avgReq: MinNumZore(avgCall), // n次调用/请求
        callPct: MinNumZore(callPct),
        _uuid: source._uuid || uuidv4(),
      },
      serviceInfo: {
        service, serviceId,
        serviceType: type || language || service_type || 'default',
        uid,
        resources,
        serviceInstances,
        serviceInstanceMap,
        pathIds,
      }
    }
    if (children && children.length) {
      // 子元素先根据响应贡献度排序
      _source.children =
        children.sort(
          (a: any, b: any) => (b.durationCvPct || 0) - (a.durationCvPct || 0)
        ).map((child: any) => this.deepFormatFlowSource(child));
    }
    return _source
  }

  // 用于手动关闭main group popover
  private hideMainGroupPopover () {
    //
  }

  private resizeHandler: any = null;
  private flowChartResize () {
    this.$nextTick(() => {
      // 调用图表resize事件
      const container = document.getElementById('serviceFlowDiv');
      if (!flowChartInstance || !flowChartInstance.graph || flowChartInstance.graph.get('destroyed')) {
        return
      };
      if (!container || !container.clientWidth || !container.clientHeight) {
        return
      };
      const { clientWidth, clientHeight } = container
      flowChartInstance.graph.changeSize(clientWidth, clientHeight);
    })
  }

  private regetGlobalTime () {
    const { fromTime, toTime } = this.getGlobalTimeV2()
    this.timeParams = { fromTime, toTime }
  }

  private applyFlowFilterItems (flowData: any, resetFilter: boolean = false) {
    // 服务实例列表、请求列表由服务流接口返回（v2.8.4+）
    if (flowData && Object.keys(flowData).length && resetFilter) {
      const { resources = [] } = flowData || {};
      this.resourceList = (resources || []).map((r: any) => ({ label: r, value: r }));
    }

    this.$refs.searchGroup.setInitOver()
  }

  private itemFilterChange ({ payload, filters, chainIndex }: { payload: ServiceFilterPayload, filters: FormatedSelected[], chainIndex: number }) {
    const target = this.filterChain[chainIndex];
    if (target) {
      target.filters = filters
    }
    this.$nextTick(() => {
      this.regetGlobalTime();
      this.getFlowData();
    })
  }

  private itemRemoveHandle ({ chainIndex }: { chainIndex: number}) {
    const deleteFilters = this.filterChain.splice(chainIndex <= 1 ? 0 : chainIndex);
    this.pathIdsGroup.splice(chainIndex);
    this.lastQueryFilterChain = deepClone(this.filterChain)
    // 判断被删除的部分是否有条件，如果有，则重新获取数据
    // const shouldRefetch = deleteFilters.some((i) => i.filters.length > 0);
    const shouldRefetch = deleteFilters.length > 0;
    if (shouldRefetch) {
      this.regetGlobalTime();
      this.getFlowData();
    }
  }

  private formatFilterChain (flowData: any) {
    const _level = 0;
    const _chain: any[] = [];
    const _maxLevel = this.filterChain.length;
    const _filterChain = [...this.filterChain];
    const deepFormatByFlowData = (row: any, level: number, maxLevel: number, contain: any[], chain: any[]) => {
      if (level > maxLevel) {
        return;
      }
      const { resources = [], serviceInstances = [], serviceId, uid } = row;
      const targetChain = chain[level];
      if (targetChain) {
        contain.push({
          ...targetChain,
          resources: resources || [],
          serviceInstances: serviceInstances || [],
        });
        if (row && row.children && chain[level + 1]) {
          const nextChainItem = chain[level + 1]
          const targetChild = row.children.find((c: any) => c.serviceId === nextChainItem.id && c.uid === nextChainItem.uid)
          if (targetChild) {
            deepFormatByFlowData(targetChild, level + 1, maxLevel, contain, chain);
          }
        }
      }
    }
    deepFormatByFlowData(flowData, _level, _maxLevel, _chain, _filterChain);
    this.filterChain.forEach((f) => {
      const targetItem = _chain.find((_f) => _f.uid === f.uid);
      f.resources = targetItem ? (targetItem?.resources || []) : [];
      f.serviceInstances = targetItem ? (targetItem?.serviceInstances || []) : [];
    });
    // this.filterChain = _chain
    // this.pathIdsGroup.splice(_chain.length);
  }

  private async formatLocalTreeByRemoteData () {
    const _chain = [...this.filterChain];
    const pathIds: string[] = [];
    const pathIdsGroup: string[][] = [];
    const newFlowData = deepClone(this.backupFlowData);
    let level = 1;
    let target = newFlowData;
    if (target?.serviceInfo?.pathIds) {
      pathIds.push(...target.serviceInfo.pathIds);
      pathIdsGroup.push(target.serviceInfo.pathIds);
    }
    const uidMap: any = {};
    uidMap[target.key] = target;
    while (level < _chain.length) {
      const targetChild = target.children.find((i: any) => i.key === _chain[level].uid);
      target.children = targetChild ? [targetChild] : []
      if (targetChild?.serviceInfo?.pathIds) {
        pathIds.push(...targetChild.serviceInfo.pathIds);
      }
      pathIdsGroup.push(targetChild?.serviceInfo?.pathIds || []);

      target = targetChild;
      uidMap[target.key] = target;
      level += 1;
    }
    // 需要重新获取数据，更新旧数据
    const params: any = {
      ...this.queryParams,
      ...this.timeParams,
      pathIds: [...new Set(pathIds)],
    }
    this.pathIdsGroup = pathIdsGroup;
    if (this.filterChain && this.filterChain.length) {
      const _filterParams: any[] = [];
      this.filterChain.forEach((i) => {
        i.filters.forEach((f) => {
          _filterParams.push({
            pathId: i.uid,
            filterType: f.field,
            filterValue: f?.value[0],
          })
        });
      });
      params.filters = _filterParams;
    }
    this.chartLoading = true;
    const { result, error: resError } = await toAsyncWait(ServiceApi.getServiceFlow(params))
    if (!resError) {
      const { data = {} } = result;
      const { serviceFlows = {} } = data || {};
      const [first] = Object.values(serviceFlows || {});
      const _newData = first || {};
      // let newTarget: any = _newData;
      let newLevel = 0;
      let newTarget: any = _newData;
      if (newTarget) {
        // @ts-ignore
        uidMap[newTarget.uid].viewData = {
          reqCnt: MinNumZore(newTarget.call) || null, // 请求数
          response: MinNumZore(newTarget.avgDuration) || null, // 平均响应时间
          contribution: MinNumZore(newTarget.durationCvPct) || null, // 响应贡献度
          failCnt: MinNumZore(newTarget.error) || null, // 失败请求数
          outCnt: MinNumZore(newTarget.outCall) || null, // 外发请求数
          hostIp: newTarget.hostIp || '', // 主机IP
          hostName: newTarget.hostName || '', // 主机名称
          avgReq: MinNumZore(newTarget.avgCall) || null, // n次调用/请求
          callPct: MinNumZore(newTarget.callPct) || null,
          _uuid: newTarget._uuid || uuidv4(),

        }
        while (newLevel < _chain.length) {
          const targetChild = newTarget?.children.find((i: any) => i.key === _chain[newLevel].uid);
          if (targetChild && uidMap[targetChild.uid]) {
            // const { avgCall = 0, outCall = 0, callPct = 0,  avgDuration = 0, call = 0, error = 0, durationCvPct = 0, hostIp = '', hostName = '', } = targetChild
            // @ts-ignore
            uidMap[targetChild.uid].viewData = {
              reqCnt: MinNumZore(targetChild.call) || null, // 请求数
              response: MinNumZore(targetChild.avgDuration) || null, // 平均响应时间
              contribution: MinNumZore(targetChild.durationCvPct) || null, // 响应贡献度
              failCnt: MinNumZore(targetChild.error) || null, // 失败请求数
              outCnt: MinNumZore(targetChild.outCall) || null, // 外发请求数
              hostIp: targetChild.hostIp || '', // 主机IP
              hostName: targetChild.hostName || '', // 主机名称
              avgReq: MinNumZore(targetChild.avgCall) || null, // n次调用/请求
              callPct: MinNumZore(targetChild.callPct) || null,
              _uuid: targetChild._uuid || uuidv4(),
            }
            newTarget = targetChild;
          }
          newLevel += 1;
        }
      }
      
    }
    if (flowChartInstance) {
      flowChartInstance.updateChart(newFlowData);
    }
    this.chartLoading = false;

  }
}
</script>

<style lang="scss" scoped>
.topo-cont-wrapper{
  flex: 1;
  position: relative;
  background-color: var(--bg-color);
  display: flex;
  flex-direction: column;
  overflow: hidden;

  .topo-cont-top {
    flex: none;
    padding: 20px;
    border-bottom: 1px solid var(--border-color-lighter);
  }

  .topo-cont-bottom {
    flex: 1;
    height: calc(100% - 73px);
    display: flex;
    background-color: #FAFAFA;
    position: relative;
    overflow: hidden;
  }

  .topo-wrap {
    user-select: none;
    flex: 1;
    overflow: hidden;
    position: relative;

    &.topo-wrap-hidden .topo {
      opacity: 0;
    }
    .topo {
      width: 100%;
      height: 100%;
    }
    .empty-show{
      width: 100%;
      height: 100%;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 16px;
      position: absolute;
      top: 0;
      left: 0;
      z-index: 2;
    }
  }
}
</style>
<style lang="scss">
.custom-flow-contextmenu {
  background-color: var(--bg-color);
  border-radius: 4px;
  border: solid 1px var(--border-color-base);
  
  .custom-flow-contextmenu-ul {
    padding: 10px 0;
    margin: 0;
    list-style: none;
    li {
      padding: 3px 15px;
      width: 100px;
      text-align: center;
      line-height: 24px;
      color: var(--color-text-primary);
      cursor: pointer;
      transition: color .3s, background-color .3s;
      &:hover {
        color: var(--color-text-link);
        background-color: var(--bg-color03);
      }
    }
  }
}
</style>