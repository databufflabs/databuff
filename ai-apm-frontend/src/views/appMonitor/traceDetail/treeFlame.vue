<template>
  <div class="trace-tree-flame-wrapper">
    <div class="trace-tree-flame flex-h">
      
      <div class="trace-tree-flame-body" ref="flameCont">
        <div class="trace-tree-flame-header">
          <el-input
            v-model="localQuery"
            @change="queryNodeHandle"
            clearable size="small"
            maxlength="100"
            class="border-none"
            prefix-icon="db-icon-search"
            :placeholder="$t('modules.views.appMonitor.traceDetail.s_bbd6842e')"
          />
          <span class="extra-info-title">{{ $t('modules.views.appMonitor.traceDetail.s_6ed40596') }}</span>
        </div>
        <!-- tree结构 -->
        <div class="trace-tree-cont" ref="traceTreeCont">
          <div class="trace-tree-wrapper" ref="traceTreeWrapper" :style="{ left: sliderOffsetX + 'px' }">
            <div
              v-for="id in displayedIds"
              :key="id"
              :class='["trace-tree-node", id === currentSpanId ? "active" : ""]'>
              <div class="trace-tree-node-main"
                :style="{ paddingLeft: `${(spanMapping[id].formatLevel - 1) * 14}px` }">
                <div class="trace-tree-node-main-wrapper">
                  <!-- <span
                    :class="{
                      expanded: !expandTreeIds.includes(id),
                      'is-leaf': !spanMapping[id].hasChildren,
                    }"
                    class="trace-tree-node-icon el-icon-arrow-right cp"
                    @click="toggleTreeExpandedHandle(id)"></span> -->

                  <span class="node-type-icon" :data-type='spanMapping[id].service_type'>
                    <!-- <i :class='["db-icon", "db-icon-" + (spanMapping[id].type || spanMapping[id].service_type)]'></i> -->
                    <i class="db-icon">{{ spanMapping[id].type || spanMapping[id].service_type | DbIconFilter }}</i>
                  </span>
                  <div
                    @click.stop="nodeClickHandle(id)" :class="{ 'not-match': !matchedIds.includes(id), active: id === currentSpanId }" class="trace-tree-node-cont cp">
                    <p
                      :title="spanMapping[id].resource"
                      :class="{
                        error: spanMapping[id].error === 1,
                        slow: spanMapping[id].error === 2,
                        'hot-method': spanMapping[id].hotspot
                      }"
                      class="trace-tree-node-text fw-500 font-13">
                      {{ spanMapping[id].resource }}
                    </p>
                    <p class="trace-tree-node-info font-12">
                      {{ spanMapping[id].service }}
                      <span class="ml-5 mr-5">|</span>
                      {{ spanMapping[id].service_type | ServiceTypeFilter }}
                      <span class="ml-5 mr-5">|</span>
                      {{ spanMapping[id].type || '-' }}{{ spanMapping[id].type === 'line' ? `:${(spanMapping[id].metrics || {})['invoke.line.number'] || '-'}` : '' }}
                    </p>
                  </div>
                </div>
              </div>
            </div>
          </div>
          
        </div>

        <!-- 执行占比 -->
        <div class="trace-tree-percent-cont">
          <div
            v-for="id in displayedIds"
            :key="id"
            :class='["trace-tree-percent-item", id === currentSpanId ? "active" : ""]'>
            <span
              v-if="spanMapping[id].hasInner && spanMapping[id].isIn === 1"
              @click.stop="toggleExpandedStatusHandle(id)"
              class="db-blue cp font-12 fw-normal node-extra-info-btn">{{ $t('modules.views.appMonitor.traceDetail.s_ce50ea0b', { value0: innerExpandedIds.includes(id) ? $t('modules.components.text-expand.s_def9e98b') : $t('modules.components.text-expand.s_e2edde5a') }) }}</span>
            <span
              v-if="spanMapping[id].hasLog"
              @click.stop="viewLogsHandle(id)"
              class="db-blue cp font-12 fw-normal node-extra-log-btn">Logs</span>
            <div :class='["node-extra-info"]'>{{ excutePctMap[id] || '< 0.1%' }}</div>
          </div>
        </div>
        <div class="trace-tree-wrapper-mask">
          <div
            v-for="id in displayedIds"
            :key="id"
            :class='["trace-tree-node-shadow", id === currentSpanId ? "active" : ""]'>
          </div>
        </div>
        <!-- tree对应的flame -->
        <div class="trace-flame-cont">
          <tree-flame-chart
            :sourceMapping="spanMapping"
            :displayedIds="displayedIds"
            :activeId="currentSpanId"
            :chartHeight="calChartHeight"
          />
        </div>

        <div class="chart-legend">
          <div
            v-for="item in chartLegends"
            :key="item"
            :data-type="item"
            class="chart-legend-item">
            <template v-if="item === 'error'">{{ $t('modules.views.appMonitor.traceDetail.s_51a651c3') }}</template>
            <template v-else>{{ item | ServiceTypeFilter }}</template>
          </div>
        </div>
      </div>

      <!-- 横向滚动条 -->
      <div class="trace-tree-slider" :style="{ bottom: calcBottom }" @scroll="sliderTraceTreeHandle" ref="treeSlider">
        <div class="trace-tree-slider-placeholder" :style="{ width: maxSliderWidth + 'px' }"></div>
      </div>

    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator'
import deepClone from 'lodash/cloneDeep';
import TreeFlameChart from './treeFlameChart.vue';
import { PercentFilter } from '@/utils/filters/number';
import { Util as G6Util } from '@antv/g6';
@Component({
  components: {
    TreeFlameChart,
  }
})
export default class TreeFlame extends Vue {
  @Prop({ default: () => [] }) private source!: any[]
  @Prop({ default: () => [] }) private spanListData!: any[]
  @Prop({ default: () => ({}) }) private initSpan!: any;
  @Prop({ default: 0 }) private totalExectime!: any;
  @Prop({ default: 0 }) private totalDuration!: any;

  @Watch('initSpan', { deep: true })
  private onInitSpanChange (newVal: any) {
    if (newVal?.span_id) {
      this.currentSpanId = newVal.span_id
      this.ensureSpanVisibleAndScroll()
    }
  }

  @Watch('source', { deep: true, immediate: true })
  private onSourceChange (newVal: any[]) {
    if (!newVal || !Array.isArray(newVal)) {
      return
    }
    if (this.initSpan?.span_id) {
      this.currentSpanId = this.initSpan.span_id
    }
    if (Array.isArray(newVal)) {
      this.originalData = deepClone(newVal);
      this.initTreeData();
      this.$nextTick(() => {
        this.ensureSpanVisibleAndScroll()
      })
    }
  }

  @Watch('displayedIds', { immediate: true })
  private onDisplayedIds (newVal: string[]) {
    this.$nextTick(() => {
      if (this.$refs.traceTreeWrapper && this.calChartHeight) {
        const wrapperWidth = this.$refs.traceTreeWrapper.getBoundingClientRect().width;
        this.maxSliderWidth = wrapperWidth
      }
    })

  }

  public $refs!: {
    flameCont: HTMLDivElement,
    traceTreeCont: HTMLDivElement,
    traceTreeWrapper: HTMLDivElement,
    treeSlider: HTMLDivElement,
  }

  // 原始数据
  private originalData: any[] = []

  // 搜索关键字
  private localQuery = '';
  // chart高度
  private calChartHeight = 40;
  private treeFlameContHeight = 0;

  // trace tree横向滚动条宽度
  private maxSliderWidth = 400;
  private sliderOffsetX = 0;

  get calcBottom () {
    return `${this.calChartHeight > this.treeFlameContHeight - 40 ? 20 : this.treeFlameContHeight - this.calChartHeight + 19}px`
  }

  // 节点详细数据的映射
  private spanMapping: any = {}
  // 节点层级映射，用于计算缩进
  private levelMapping: any = {}

  // 当前选中span的id
  private currentSpanId: string = '';

  // 是否展开内部调用
  private innerExpandedIds: string[] = []
  private expandedIds: string[] = []
  // 可见节点的ids
  private displayedIds: string[] = [];
  // 用户tree折叠的ids数组，每次操作内部调用后需要重新赋值为displayIds
  private displayedIdsForTreeBackup: string[] = [];
  private expandTreeIds: string[] = [];

  // 执行占比
  private excutePctMap: any = {};

  private chartLegends = ['web', 'db', 'cache', 'mq', 'custom', 'error'];


  // 是否有性能剖析
  get hasProfiling () {
    const _currentSpan = this.currentSpanId ? (this.spanMapping[this.currentSpanId] || {}) : {};
    return +_currentSpan?.onCpu > 0
  }

  // 搜索匹配到的ids
  get matchedIds () {
    const _localQuery = this.localQuery.toLowerCase()
    return this.displayedIds.filter(id => {
      const span = this.spanMapping[id]
      const resourceMatch = span.resource.toLowerCase().indexOf(_localQuery) > -1
      const serviceMatch = span.service.toLowerCase().indexOf(_localQuery) > -1
      return resourceMatch || serviceMatch
    })
  }

  private mounted () {
    const { height } = this.$refs.flameCont.getBoundingClientRect();
    this.treeFlameContHeight = height;
  }

  // 初始化
  private initTreeData () {
    // 入口Span：isIn=1，无论展开/收起都要展示
    // 展开/收起的范围：当前节点与子孙节点中入口Span之间部分，入口Span的内部不做处理
    const displayedIds: string[] = [];
    const formatData = (data: any[], pLevel: number = 1, pids: string[]) => {
      data.forEach((item: any) => {
        const hasChildren = Array.isArray(item?.children) && item.children.length > 0
        const hasInner = hasChildren && item.children.some((c: any) => c.isIn === 0);
        
        item.pLevel = pLevel;
        item.formatLevel = pLevel;
        if (pLevel === 1) {
          item.isIn = 1;
        }

        item.pids = [...pids]
        
        // 计算span的执行占比
        this.excutePctMap[item.span_id] = PercentFilter(item.exectime / this.totalExectime, true);

        this.spanMapping[item.span_id] = {
          ...item,
          hasInner,
          hasChildren,
          children: null,
        }
        if (item.isIn === 1) {
          displayedIds.push(item.span_id);
        }
        if (hasChildren) {
          formatData(item.children, pLevel + 1, [...item.pids, item.span_id])
        }
      });
    }
    formatData(this.originalData, 1, []);

    this.displayedIds = displayedIds;
    // console.log('displayedIds', displayedIds, displayedIds.length);
    // 处理层级
    this.reformatTreeLevel();
    // console.log('displayedIds', deepClone(this.originalData));
    this.calChartHeight = displayedIds.length * 47 + 40;
  }

  // 计算展示层级 --- 仅供内部调用的逻辑使用
  private reformatTreeLevel () {
    const levels: Array<[string, number]> = []
    this.displayedIds.forEach((id) => {
      const _pids: string[] = this.spanMapping[id].pids;
      const showStatusPath = _pids.map((pid) => this.displayedIds.includes(pid));
      const formatLevel = showStatusPath.filter((s) => s).length + 1;
      this.spanMapping[id].formatLevel = formatLevel;
      levels.push([id, formatLevel]);
    });
  }


  // 内部调用展开/折叠
  private toggleInnerHandle (collapsed: boolean = false, spanId: string) {
    let target: any = null;
    this.originalData.forEach((top) => {
      G6Util.traverseTree(top, (item: any) => {
        if (item.span_id === spanId) {
          target = item;
          return false;
        }
      })
    });
    // console.log('target', target);
    // 查找当前层级到任意childSpan isIn=1 的所有spanId
    const innerChildIds: string[] = [];
    const findInner = (childrens: any[] = []) => {
      childrens.forEach((child) => {
        if (child.isIn !== 1) {
          innerChildIds.push(child.span_id);
          if (Array.isArray(child?.children)) {
            findInner(child.children);
          }
        }
      })
    }
    findInner(target?.children);
    let _displayedIds = [...this.displayedIds];
    // needDelete or needAdd
    if (collapsed) {
      _displayedIds = _displayedIds.filter((id) => !innerChildIds.includes(id));
    } else {
      _displayedIds = [..._displayedIds, ...innerChildIds];
    }
    // 重新获取ids，层级和顺序无法通过直接操作displayedIds设置
    const sortDisplayIdsByData = (ids: string[], data: any[]) => {
      const _ids: string[] = []
      data.forEach(item => {
        if (ids.includes(item.span_id)) {
          _ids.push(item.span_id);
        }
        if (Array.isArray(item?.children)) {
          _ids.push(...sortDisplayIdsByData(ids, item.children));
        }
      })
      return _ids;
    }
    this.displayedIds = sortDisplayIdsByData(_displayedIds, this.originalData)
    this.calChartHeight = this.displayedIds.length * 47 + 40;
    this.reformatTreeLevel();
  }

  // 点击
  private nodeClickHandle (id: string) {
    this.currentSpanId = id
    this.$emit('on-change', { ...this.spanMapping[id] })
  }

  private viewLogsHandle (id: string) {
    this.currentSpanId = id
    this.$emit('on-change', { ...this.spanMapping[id] })
    this.$emit('view-logs', { ...this.spanMapping[id] })
  }
  // 切换展开/收起
  private toggleExpandedStatusHandle (id: string) {
    if (!this.innerExpandedIds.includes(id)) {
      // 展开
      this.innerExpandedIds.push(id);
      this.toggleInnerHandle(false, id)
    } else {
      // 收起
      this.innerExpandedIds = this.innerExpandedIds.filter((t: string) => t !== id);
      this.toggleInnerHandle(true, id)
    }
  }

  // 树层级展开/收起
  private toggleTreeExpandedHandle (id: string) {
    const levels: Array<[string, number]> = [];
    let startIndex = -1;
    let startLevel = -1;
    this.displayedIds.forEach((spanId, index) => {
      const formatLevel = this.spanMapping[spanId].formatLevel;
      levels.push([spanId, formatLevel]);
      if (id === spanId) {
        startIndex = index + 1;
        startLevel = formatLevel;
      }
    });
    
    // for (let i = startIndex, len = levels.length; i < len; i++) {
    //   //
    // }
    const endIndex = [...levels].splice(startIndex).findIndex(i => i[1] <= startLevel);
    if (!this.expandTreeIds.includes(id)) {
      this.displayedIds.splice(startIndex, endIndex === -1 ? this.displayedIds.length : endIndex);
      this.expandTreeIds.push(id);
    } else {
      // 展开
    }
    this.calChartHeight = this.displayedIds.length * 47 + 40;
  }

  // 展开内部调用并滚动到目标 span
  private ensureSpanVisibleAndScroll () {
    const spanId = this.currentSpanId
    if (!spanId) {
      return
    }
    if (!this.displayedIds.includes(spanId)) {
      const span = this.spanMapping[spanId]
      if (span?.pids) {
        for (const pid of span.pids) {
          const parent = this.spanMapping[pid]
          if (parent?.hasInner && !this.innerExpandedIds.includes(pid)) {
            this.innerExpandedIds.push(pid)
            this.toggleInnerHandle(false, pid)
          }
        }
      }
    }
    this.$nextTick(() => {
      this.scrollToSpan(spanId)
    })
  }

  private scrollToSpan (spanId: string) {
    const index = this.displayedIds.indexOf(spanId)
    if (index > -1 && this.$refs.flameCont) {
      const itemHeight = 47
      const containerHeight = this.$refs.flameCont.clientHeight
      const targetScroll = itemHeight * index - containerHeight / 2 + itemHeight / 2
      this.$refs.flameCont.scrollTop = Math.max(0, targetScroll)
    }
  }

  // 滚动到第一个搜索到的节点
  private queryNodeHandle () {
    if (!this.localQuery || !this.matchedIds.length) {
      return
    }
    this.scrollToSpan(this.matchedIds[0])
  }

  private sliderTraceTreeHandle (e: MouseEvent) {
    this.sliderOffsetX = -this.$refs.treeSlider.scrollLeft
  }

}
</script>

<style lang='scss' scoped>
$itemHeight: 47px;

.trace-tree-flame-wrapper {
  height: 100%;
  overflow: hidden;

  .trace-tree-flame {
    height: 100%;
    position: relative;
    border-top: 1px solid var(--border-color-light);

    .trace-tree-flame-header {
      position: absolute;
      top: 4px;
      // width: calc( 43.5% - 65px );
      width: 43.5%;
      display: flex;
      justify-content: space-between;

      .extra-info-title {
        display: inline-block;
        width: 65px;
        line-height: 1;
        text-align: center;
        padding-top: 16px;
        font-size: 12px;
        flex: 0 0 auto;
        color: var(--color-text-secondary);
      }
    }
    .trace-tree-flame-body {
      height: calc( 100% - 30px );
      margin-bottom: 30px;
      flex: 1;
      overflow-y: auto;
      overflow-x: hidden;
      position: relative;
    }

    .trace-tree-cont {
      width: calc( 43.5% - 65px );
      height: calc( 100% - 40px );
      position: absolute;
      left: 0;
      top: 40px;
    }
    .trace-tree-wrapper {
      min-width: 100%;
      border-top: 1px solid var(--border-color-light);
      position: absolute;
      top: 0;
    }
    .trace-tree-percent-cont {
      width: 65px;
      // min-height: 100%;
      position: absolute;
      right: 56.5%;
      top: 40px;
      z-index: 2;
    }
    .trace-flame-cont {
      width: 56.5%;
      min-height: 100%;
      position: absolute;
      right: 0;
      top: 0;
      z-index: 2;
    }
    
  }
}

.trace-tree-node {
  height: $itemHeight;
  display: flex;
  align-items: center;
  &.active {
    background-color: var(--background-color-base);
  }

  .trace-tree-node-main {
    width: 100%;
    height: 100%;
    border-bottom: 1px solid var(--border-color-light);
    overflow: hidden;
  }
  .trace-tree-node-main-wrapper {
    height: 100%;
    position: relative;
    padding-left: 48px;
  }

  .trace-tree-node-icon {
    position: absolute;
    left: 0;
    top: calc( $itemHeight / 2 - 13px );
    padding: 6px;
    font-size: 14px;
    font-weight: bold;
    color: var(--color-text-regular);
    transition: all 0.3s;
    &.expanded {
      transform: rotate(90deg);
    }
    &.is-leaf {
      opacity: 0;
      pointer-events: none;
    }
  }
  .node-type-icon {
    position: absolute;
    left: 26px;
    top: calc( $itemHeight / 2 - 11px );
    width: 22px;
    height: 22px;
    border-radius: 4px;
    line-height: 22px;
    text-align: center;
    .db-icon {
      font-size: 16px;
      vertical-align: middle;
      color: var(--color-white);
    }

    &[data-type="web"], &[data-type="browser"] {
      background: #5180FF;
    }
    &[data-type="db"] {
      background: #2BB9F1;
    }
    &[data-type="cache"] {
      background: #8F77FA;
    }
    &[data-type="mq"] {
      background: #35C8C0;
    }
    &[data-type="custom"] {
      background: #F79F46;
    }
    &[data-type="error"] {
      border: 2px solid var(--color-danger);
    }
  }
}
.trace-tree-wrapper-mask {
  position: absolute;
  width: 56.5%;
  right: 0;
  top: 40px;
  z-index: 1;
}
.trace-tree-node-shadow {
  height: $itemHeight;
  display: flex;
  align-items: center;
  background-color: var(--bg-color);
  &.active {
    background-color: var(--background-color-base);
  }
}

.trace-tree-node-cont {
  overflow: hidden;
  padding: 7px 10px;

  &.active {
    padding: 2px 9px;
    border: 2px solid var(--color-text-link);
    border-radius: 4px;
    background-color: rgba(81, 128, 255, 0.08);
  }
  &.not-match {
    opacity: 0.35;
  }
  .trace-tree-node-text {
    white-space: nowrap;
    line-height: 18px;
    margin: 0;
    color: var(--color-text-primary);
    &.error {
      color: var(--color-danger);
    }
    &.slow {
      color: var(--color-warning);
    }
    &.hot-method {
      padding-right: 70px;
      position: relative;
      span {
        position: absolute;
        right: 0;
        top: 0;
        &:hover {
          text-decoration: underline;
        }
      };
    }
  }
  .trace-tree-node-info {
    white-space: nowrap;
    margin: 0;
    font-size: 12px;
    line-height: 14px;
    color: var(--color-text-secondary);
  }
}
.trace-tree-percent-item {
  border: 1px solid transparent;
  height: $itemHeight;
  border-left: 1px solid var(--border-color-light);
  line-height: 47px;
  border-right: 1px solid var(--border-color-light);
  border-top: 1px solid var(--border-color-light);
  background-color: var(--bg-color);
  &.active {
    background-color: var(--background-color-base);

    .node-extra-info-btn, .node-extra-log-btn {
      background-color: var(--background-color-base);
    }
  }
}
.node-extra-info {
  height: 100%;
  text-align: center;
  font-size: 12px;
  color: var(--color-text-regular);
}
.node-extra-info-btn {
  position: absolute;
  background-color: var(--bg-color);
  width: 76px;
  height: 16px;
  line-height: 16px;
  text-align: center;
  font-size: 12px;
  transform: translate(-77px, 8px);
}
.node-extra-log-btn {
  position: absolute;
  background-color: var(--bg-color);
  width: 54px;
  height: 16px;
  line-height: 16px;
  text-align: center;
  font-size: 12px;
  transform: translate(-55px, 27px);
}
.chart-legend {
  position: fixed;
  bottom: 30px;
  height: 34px;
  line-height: 34px;
  padding: 0 10px;
  // background-image: linear-gradient(to bottom, transparent, var(--color-white));
  background-color: rgba(255, 255, 255, .8);
  backdrop-filter: blur(2px);
  border-radius: 4px;
  right: 26%;
  display: flex;
  flex-wrap: nowrap;
  white-space: nowrap;
  z-index: 10;
  .chart-legend-item {
    display: inline-flex;
    align-items: center;
    line-height: 22px;
    & + .chart-legend-item {
      margin-left: 15px;
    }
    &::before {
      content: '';
      box-sizing: border-box;
      margin-right: 5px;
      display: inline-block;
      width: 10px;
      height: 10px;
      border-radius: 1;
    }
    &[data-type="web"]::before {
      background: #5180FF;
    }
    &[data-type="db"]::before {
      background: #2BB9F1;
    }
    &[data-type="cache"]::before {
      background: #8F77FA;
    }
    &[data-type="mq"]::before {
      background: #35C8C0;
    }
    &[data-type="custom"]::before {
      background: #F79F46;
    }
    &[data-type="error"]::before {
      border: 2px solid var(--color-danger);
    }
  }
}
.trace-tree-slider {
  position: absolute;
  left: 0;
  height: 10px;
  width: calc( 43.5% - 65px );
  overflow-x: auto;
  overflow-y: hidden;
}
.trace-tree-slider-placeholder {
  height: 10px;
}
</style>
