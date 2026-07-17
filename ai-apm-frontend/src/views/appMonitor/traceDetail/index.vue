<template>
  <div class="span-detail flex-v">
    <div class="span-detail-wrapper"
      v-loading='graphLoading'>
      <div class="trace-info">
        <div class="trace-baseinfo flex-h">
          <i class="db-icon db-icon-trace mr-10 fw-500 flex-none"></i>
          <span v-if='isError' class="bg-red text-white p-5 line-height-1 font-12 br-2 mr-10 flex-none">{{ $t('modules.components.db-table.s_c195df63') }}</span>
          <span v-else-if='isSlow' class="bg-yellow text-white p-5 line-height-1 font-12 br-2 mr-10 flex-none">{{ $t('modules.utils.filters.s_1efeae37') }}</span>
          <span v-else class="bg-green text-white p-5 line-height-1 font-12 br-2 mr-10 flex-none">{{ $t('modules.components.db-table.s_fd6e80f1') }}</span>
          <span class="fw-500 font-16 ell">{{ traceInfo.resource }}</span>
          <span class="split-line-vertical flex-none"></span>
          <span class="ell font-16 fw-500">{{ traceInfo.service || '-' }}</span>
        </div>
        <div class="trace-time-info flex-h mt-15">
          <div class="tag-title mr-5">{{ $t('modules.views.appMonitor.traceDetail.s_3c2bfda7') }} </div>
          <span class="sub-describe">{{ traceInfo.startTime | TimesToDateFilter }}</span>
          <div class="tag-title mr-5 ml-20">{{ $t('modules.views.appMonitor.traceDetail.s_f0099442') }} </div>
          <span class="sub-describe">{{ traceInfo.duration | NsFilter }}</span>
          <span class="tag-title mr-5 ml-20">TraceID: </span>
          <span class="sub-describe">
            {{ traceInfo.trace_id || '-' }}
            <i @click.stop="copyNameHandle(traceInfo.trace_id || '-')" class="db-icon-copy font-12 blue cp ml-5"></i>
            <span
              v-if="traceInfo.trace_id"
              @click.stop="viewLogsAnalysisHandle"
              class="db-blue cp font-12 ml-10">日志分析</span>
          </span>
          <el-alert v-if="spanListOversize" :title="$t('modules.views.appMonitor.traceDetail.s_c605e33a')" type="error" :closable="false" class="oversize-info" />
        </div>
      </div>
      <div class="tarce-tab-nav">
        <db-tabnav :tabnavs='tabnavs' v-model='activeName' class="mb-10" />
      </div>

      <div class="split-pane-wrapper flex-h" ref='flameChartWrapper'>
        <div v-if='activeName === "sort"' class="tree-flame-wrapper flex-1">
          <tree-flame :source='traceSource' :initSpan='currentSpan' :totalExectime='traceInfo.exectime' :totalDuration='traceInfo.duration'
            ref='treeFlameComp' :spanListData='spanListData'
            @on-change='changeSelectedHandle'
            @view-logs='viewSpanLogsHandle'></tree-flame>
        </div>
        <div v-if='activeName === "graph"' class="tree-flame-wrapper flex-1">
           <flame-chart
              v-loading='graphLoading'
              :show='activeName === "graph"'
              :serviceInfoList='serviceInfoList'
              :spanTypes='spanTypes'
              :spanParents='spanParents'
              :selectedSpan='selectedSpan'
              @on-change='changeSelectedHandle'
              class="flame-graph-wrapper" />
        </div>
        <div v-if='activeName === "list"' class="span-list-wrapper flex-1">
          <SpanList :source='spanListData' :totalExectime='traceInfo.exectime' @on-change='changeSelectedHandle' />
        </div>
        <div v-if='activeName === "flow"' class="flow-wrapper flex-1">
          <span-flow />
        </div>
        <span-aside v-if='(activeName === "sort" || activeName === "graph") && selectedSpan'
          :currentSpan="selectedSpan"
          :spanParents='spanParents'
          :totalExectime='traceInfo.exectime' :totalDuration='traceInfo.duration'
          class="span-aside-wrapper  flex-0"
          ref='spanAside'></span-aside>
      </div>
    </div>

  </div>
</template>

<script lang="ts">
import { Vue, Component, Watch } from 'vue-property-decorator';
import i18n from '@/i18n';
import { Getter, State } from 'vuex-class';
import dayjs from 'dayjs'
import deepClone from 'lodash/cloneDeep';
import { max } from 'd3-array';
import { copy } from '@/utils/common'
import { toAsyncWait } from '@/utils/common';
import { Splitpanes, Pane } from 'splitpanes';
import 'splitpanes/dist/splitpanes.css'
import SpanList from './span-list.vue';
import TreeFlame from './treeFlame.vue'
import ApmApi from '@/api/apm';
import SpanFlow from './span-flow.vue'
import FlameChart from './flame-chart.vue'
import SpanAside from './spanAside.vue'

@Component({
  components: {
    Splitpanes,
    Pane,
    SpanList,
    TreeFlame,
    SpanFlow,
    FlameChart,
    SpanAside
  },
})
export default class SpanDetail extends Vue {

  @Watch('globalTimeV2', { deep: true })
  private watchGlobalTime() {
    this.durationChangeHandle()
  }

  private activeName = 'sort';
  private tabnavs = [
    { label: i18n.t('modules.views.appMonitor.traceDetail.s_3712849d') as string, labelKey: 'modules.views.appMonitor.traceDetail.s_3712849d', value: 'sort' },
    { label: i18n.t('modules.views.appMonitor.traceDetail.s_709bc06a') as string, labelKey: 'modules.views.appMonitor.traceDetail.s_709bc06a', value: 'graph' },
    { label: i18n.t('modules.views.appMonitor.traceDetail.s_f69f06d7') as string, labelKey: 'modules.views.appMonitor.traceDetail.s_f69f06d7', value: 'list' },
    { label: i18n.t('modules.views.appMonitor.traceDetail.s_54c1cb4b') as string, labelKey: 'modules.views.appMonitor.response.s_54c1cb4b', value: 'flow' },
  ];

  private graphLoading = true;

  // 当前Span信息
  private currentSpan: any = {}

  // 链路信息
  private traceInfo = {
    trace_id: '',
    resource: '',
    service: '',
    startTime: 0,
    duration: 0,
    exectime: 0,
  }

  // span数量是否超出1000条
  private spanListOversize = false;

  // 调用次序
  private traceSource: any = [];

  // 耗时统计
  private spanListData: any = [];

  // 选中Span的详细信息
  private selectedSpan: any = null;

  // 火焰图
  private serviceInfoList: any[] = [];
  private spanParents: any[] = [];
  private spanTypes: any[] = [];
  get isNormal () {
    return this.currentSpan?.error === 0
  }
  get isError () {
    return this.currentSpan?.error === 1
  }
  get isSlow () {
    return this.currentSpan?.error === 2
  }

  private mounted () {
    // 监听全局的手动刷新事件，created中会有不触发的情况
    // 子组件绑定会被覆盖
    // this.$eventBus.$on('GlobalRefresh', this, () => {
    //   this.durationChangeHandle()
    // });
    this.durationChangeHandle()
  }

  private beforeDestroy () {
    // 清除监听全局刷新事件
    // this.$eventBus.$off('GlobalRefresh')
  }

  private async durationChangeHandle () {
    const { pid } = this.$route.query
    console.log('durationChangeHandle pid', pid)
    if (!pid) {
      this.getSpanChainList();
    } else {
      await this.getCurrentSpan();
      if (!this.currentSpan || !this.currentSpan.start) {
        this.$message.error(i18n.t('modules.views.appMonitor.traceDetail.s_d45ee381') as string)
        return
      }
      this.getSpanChainList();
    }
  }

  private async getCurrentSpan () {
    this.graphLoading = true
    // ft, tt 为时间戳，此页面无时间组件权限，故时间参数自行传递，不走全局方法
    const { spid, tid, pid, ft, tt } = this.$route.query
    let { fromTime, toTime } = this.getGlobalTime()
    if (ft && tt) {
      fromTime = new Date(+ft as number)
      toTime = new Date(+tt as number)
    }
    console.log('fromTime, toTime', fromTime, toTime, ft, tt)
    const params: any = {
      traceId: decodeURIComponent(tid as string),
      spanId: decodeURIComponent(spid as string),
      fromTime: dayjs(fromTime).format('YYYY-MM-DD HH:mm:ss'),
      toTime: dayjs(toTime).format('YYYY-MM-DD HH:mm:ss'),
      offset: 0,
      size: 50,
    }
    if (pid) {
      params.parentId = pid
      delete params.spanId
    }
    const { result, error } = await toAsyncWait(ApmApi.getSpanList(params))
    if (!error) {
      const data = result.data || []
      if (!pid) {
        const decodedTid = decodeURIComponent(tid as string)
        const decodedSpid = decodeURIComponent(spid as string)
        this.currentSpan = data.find((item: any) => item.trace_id === decodedTid && item.span_id === decodedSpid) || {}
      } else {
        this.currentSpan = data[0] || {}
      }
    }
    this.graphLoading = false
  }

  private async getSpanChainList () {
    this.graphLoading = true
    const { spid, tid, pid, ft, tt } = this.$route.query
    let { fromTime, toTime } = this.getGlobalTime()
    if (ft && tt) {
      fromTime = new Date(+ft as number)
      toTime = new Date(+tt as number)
    }

    const params: any = {
      traceId: decodeURIComponent(tid as string),
      fromTime: dayjs(+fromTime - 1000 * 600).format('YYYY-MM-DD HH:mm') + ':00',
      toTime: dayjs(+toTime + 1000 * 600).format('YYYY-MM-DD HH:mm') + ':00',
      offset: 0,
      size: 1000,
    }
    if (pid && this.currentSpan) {
      const _duration = (this.currentSpan.duration || 0) / 1000 / 1000
      const _start = +this.currentSpan.start.substring(0, 13) + Math.floor(_duration)
      params.fromTime = dayjs(_start - 1000 * 3600).format('YYYY-MM-DD HH:mm') + ':00'
      params.toTime = dayjs(_start + 1000 * 3600).format('YYYY-MM-DD HH:mm') + ':00'
      params.traceId = this.currentSpan.trace_id
    }
    const { result, error } = await toAsyncWait(ApmApi.getTraceSpans(params))
    if (!error) {
      const formatData = (list: any[], span: any) => {
        const children = list.filter((item: any) => item.parent_id === span.span_id);
        if (children.length) {
          children.forEach((subItem: any) => {
            formatData(list, subItem)
          })
          span.children = children;
        }
      }

      if (!result.data.length) {
        if (!pid) {
          this.getCurrentSpan();
        } else {
          this.graphLoading = false;
        }
        return
      }

      const spanId = decodeURIComponent(spid as string) || this.currentSpan?.span_id || '';
      const currentIndex = spanId ? result.data.findIndex((item: any) => item.span_id === spanId) : -1;
      if (currentIndex > -1) {
        const currentSpan = result.data[currentIndex];
        if (currentIndex >= 1000) {
          result.data.splice(currentIndex, 1);
          result.data.unshift(currentSpan);
        }
        if (!pid) {
          this.currentSpan = currentSpan;
        }
      } else if (!pid) {
        await this.getCurrentSpan();
      }

      // 只取前1000条span
      this.spanListOversize = (result.total || 0) > 1000
      const rawList = result.data.slice(0, 1000)
      const normalizeDirection = (value: any): number => Number(value) === 1 ? 1 : 0
      const normalizeHttpUrl = (url: string): string => {
        const text = `${url || ''}`.trim()
        if (!text) return ''
        try {
          const parsed = new URL(text)
          return `${parsed.pathname || '/'}${parsed.search || ''}`
        } catch (error) {
          return text
        }
      }
      const displayResource = (item: any): string => {
        const meta = item.meta || {}
        const resource = `${item.resource || ''}`.trim()
        const name = `${item.name || ''}`.trim()
        const method = `${meta['http.method'] || meta.method || ''}`.trim()
        const url = normalizeHttpUrl(meta['http.route'] || meta['http.url'] || meta['url.full'] || meta.url || '')
        const base = resource || name
        const methodOnly = /^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|TRACE)$/i.test(base)
        if (method && url && (!base || base.toLowerCase() === method.toLowerCase())) {
          return `${method.toUpperCase()} ${url}`
        }
        if (methodOnly && url) {
          return `${base.toUpperCase()} ${url}`
        }
        return base
      }
      const parseStartNs = (item: any): number => {
        const startNs = Number(item.startNs)
        if (Number.isFinite(startNs) && startNs > 0) {
          return startNs
        }
        const rawStart = item.start ?? item._start
        if (rawStart != null && rawStart !== '') {
          const n = Number(rawStart)
          if (Number.isFinite(n) && n > 1_000_000_000_000_000) {
            return n
          }
          if (Number.isFinite(n) && n > 1_000_000_000_000) {
            return n * 1_000_000
          }
          if (Number.isFinite(n) && n > 0) {
            return n * 1_000_000
          }
        }
        const startTime = item.startTime
        if (startTime == null || startTime === '') return 0
        if (typeof startTime === 'number') {
          return startTime < 1_000_000_000_000 ? startTime * 1_000_000_000 : startTime * 1_000_000
        }
        const str = String(startTime).trim()
        if (/^\d+$/.test(str)) {
          const n = Number(str)
          return n < 1_000_000_000_000 ? n * 1_000_000_000 : n * 1_000_000
        }
        const parsed = dayjs(str)
        return parsed.isValid() ? parsed.valueOf() * 1_000_000 : 0
      }
      const startNsList = rawList.map((t: any) => parseStartNs(t))
      const validStartNs = startNsList.filter((startNs: number) => startNs > 0)
      const traceStartNs = validStartNs.length ? Math.min(...validStartNs) : 0
      const data: any[] = rawList.map((t: any, index: number) => {
        const startNs = startNsList[index]
        const apiRelativeTime = Number(t.relativeTime)
        const relativeTime = Number.isFinite(apiRelativeTime) && apiRelativeTime >= 0 && (
          apiRelativeTime > 0 || startNs <= traceStartNs
        )
          ? apiRelativeTime
          : (traceStartNs > 0 && startNs > 0 ? startNs - traceStartNs : 0)
        return {
          ...t,
          resource: displayResource(t),
          isIn: normalizeDirection(t.isIn),
          isOut: normalizeDirection(t.isOut),
          _start: startNs ? String(Math.floor(startNs / 1_000_000)) : '',
          startNs: startNs,
          start: relativeTime, // 相对时间，火焰图会用到start字段
          relativeTime,
          _isInBac: normalizeDirection(t.isIn),
          _pid: t.parent_id
        }
      })


      // 以relativeTime排序，最早的排在最前，导致图表无法正常绘制
      data.sort((a: any, b: any) => a.relativeTime - b.relativeTime)

      // 自身耗时 = duration - 直接子 span duration 之和
      const childDurationSum = new Map<string, number>()
      data.forEach((item: any) => {
        const parentId = `${item.parent_id || '0'}`
        if (!parentId || parentId === '0') {
          return
        }
        childDurationSum.set(parentId, (childDurationSum.get(parentId) || 0) + (Number(item.duration) || 0))
      })
      data.forEach((item: any) => {
        const duration = Number(item.duration) || 0
        const childSum = childDurationSum.get(item.span_id) || 0
        item.exectime = Math.max(0, duration - childSum)
      })

      const _data =  deepClone(data);

      // 所有的parentId
      const parentIds: string[] = Array.from(new Set(data.map(item => item.parent_id)));
      // 所有的spanId
      const spanIds: string[] = Array.from(new Set(data.map(item => item.span_id)));
      // 是否有顶层span
      const hasParentTop = parentIds.includes('0');
      // 非顶层parentId，且没有在spanId中
      const subLostParentIds = parentIds.filter((id: string) => id !== '0').filter((id: string) => spanIds.indexOf(id) === -1)

      let parents: any[] = data.filter(item => item.parent_id === '0');
      if (!hasParentTop) {
        // 将缺失上层的span作为顶层
        parents = data.filter(item => subLostParentIds.indexOf(item.parent_id) > -1)
      }

      // 总耗时（整条 Trace 墙钟时间）
      const totalDuration = Number(max(data, (d: any) => d.relativeTime + d.duration));
      const [parentItem] = parents
      if (parentItem) {
        this.traceInfo = {
          trace_id: parentItem.trace_id,
          resource: parentItem.resource,
          service: parentItem.service,
          startTime: +(parentItem._start || '').substring(0, 13),
          duration: totalDuration,
          exectime: totalDuration,
        }
      }

      // 所有的服务名称
      const services = Array.from(new Set(data.map(item => item.service)));
      // 服务执行占比：该服务自身耗时之和 / 总耗时
      this.serviceInfoList = services.map((service) => {
        const list: any[] = data.filter((d: any) => d.service === service);
        const selfSum: number = list.reduce((prev: any, curr: any) => prev + (Number(curr.exectime) || 0), 0)
        return {
          key: service,
          value: totalDuration ? selfSum / totalDuration : '-',
        }
      }).sort((a: any, b: any) => b.value - a.value);

      this.spanTypes = Array.from(new Set(data.map(item => item.type)));

      parents.forEach((item: any) => {
        formatData(data, item)
      })
      // 有顶层span & 有子层span层级缺失
      if (hasParentTop && subLostParentIds.length) {
        const subParents = data.filter(item => subLostParentIds.indexOf(item.parent_id) > -1)
        subParents.forEach((item: any) => {
          formatData(data, item)
        })
        parents.forEach((item: any) => {
          if (!item.children) {
            item.children = subParents
          } else {
            item.children = item.children.concat(subParents)
          }
        })
      }
      this.traceSource = parents
      this.spanParents = parents
      this.spanListData = _data
      this.graphLoading = false;

      // 首次选中点击的span
      const that = this;
      if (this.currentSpan) {
        const { span_id = '' } = this.currentSpan
        this.selectedSpan = data.find((item: any) => item.span_id === span_id)
      }
    } else {
      this.graphLoading = false;
    }
  }

  private changeSelectedHandle (node: any) {
    if (node) {
      this.selectedSpan = node;
    }
  }

  private viewSpanLogsHandle (node: any) {
    this.changeSelectedHandle(node);
    if (!node?.hasLog) {
      return
    }
    this.$nextTick(() => {
      (this.$refs.spanAside as any)?.showLogsTab?.();
    });
  }

  private copyNameHandle (name: string) {
    copy(name)
  }

  private viewLogsAnalysisHandle () {
    if (!this.traceInfo.trace_id) {
      return
    }
    this.$router.push({
      path: '/appMonitor/logs',
      query: {
        ...this.getRouteTimeOrRange,
        traceId: encodeURIComponent(this.traceInfo.trace_id),
      },
    })
  }
}
</script>

<style lang="scss" scoped>
.span-detail{
  flex: 1;
  width: 100%;
  height: 100%;
  overflow: hidden;
  padding: 16px;

  .span-detail-wrapper {
    height: 100%;
    display: flex;
    overflow: hidden;
    flex-direction: column;
    background-color: var(--bg-color);
    padding: 16px 0 16px 20px;
  }
  
  .trace-info{
    padding-right: 20px;

    .trace-baseinfo{
      color: var(--color-text-primary);
    }
  }
  .trace-time-info{
    line-height: 20px;
    height: 20px;
    .tag-title{
      font-size: 12px;
      margin-right: 5px;
      color: var(--color-text-secondary)
    }
  }
  .tarce-tab-nav {
    margin-top: 20px;
  }
  .oversize-info {
    display: inline-flex;
    width: auto;
    padding: 0 10px;
    margin-left: 15px;
    line-height: 20px;
  }
  .split-line-vertical{
    display: inline-block;
    border-right: 1px solid var(--color-text-secondary);
    height: 15px;
    margin: 0 12px;
  }

  .split-pane-wrapper{
    flex: 1;
    width: 100%;
    overflow: hidden;

    .span-aside-wrapper {
      width: 25%;
      min-width: 300px;
    }
  }
  .tree-flame-wrapper {
    height: 100%;
  }
  .flame-graph-wrapper{
    height: 100%;
    align-items: flex-start;
    overflow: hidden;
    padding: 10px;
    background: var(--bg-color);
  }
  .span-list-wrapper{
    height: 100%;
    background: var(--bg-color);
    overflow-y: auto;
  }
  .flow-wrapper {
    height: 100%;
    background: var(--bg-color);
    overflow-y: auto;
  }
}
</style>
