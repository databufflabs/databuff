<template>
  <div class="scroll-el-table">
    <div v-if='showTotal' class="scroll-el-table-header flex-h-jc">
      <slot name="total" v-bind="{ total: tableTotal }">
        <div class="describe">{{ $t('modules.components.db-table.s_fc4d75f6') }} {{ new Intl.NumberFormat().format(tableTotal) }} {{ $t('modules.components.db-table.s_f932eff5') }}</div>
      </slot>
      <!-- 设置表头展示列 -->
      <el-popover
        v-if="showSetting"
        placement="bottom-end"
        width="160"
        trigger="click">
        <div class="table-columns-setting">
          <div class="table-column-list">
            <el-checkbox
              v-for="item in columnConfig" :key="item.field"
              v-model="columnsModels[item.field]"
              :disabled="item.disabled"
              @change="toggleColumnsVisibleHandle($event, item.field)"
              class="table-column-item">{{ resolveColumnText(item, 'group') + resolveColumnText(item, 'label') }}</el-checkbox>
          </div>
          <div
            @click="resetTableColumns"
            class="table-column-reset font-12 lh-18 tc blue cp">{{ $t('modules.components.db-table.s_7468f3e5') }}</div>
        </div>
        <db-icon-button slot="reference" icon='table-setting'></db-icon-button>
      </el-popover>
    </div>
    <div class="scroll-el-table-body" :key="tableSortableKey" ref="wrapper">
      <el-table
        :data='tableSource'
        :size='size'
        :width='width'
        :height='getHeight'
        :maxHeight='maxHeight'
        :fit='fit'
        :stripe='stripe'
        :border='border'
        :rowKey='rowKey'
        :context='context'
        :showHeader='showHeader'
        :showSummary='showSummary'
        :sumText='sumTextKey ? $t(sumTextKey) : sumText'
        :summaryMethod='summaryMethod'
        :rowClassName='rowClassName'
        :rowStyle='rowStyle'
        :cellClassName='cellClassName'
        :cellStyle='cellStyle'
        :headerRowClassName='headerRowClassName'
        :headerRowStyle='headerRowStyle'
        :headerCellClassName='headerCellClassName'
        :headerCellStyle='headerCellStyle'
        :highlightCurrentRow='highlightCurrentRow'
        :currentRowKey='currentRowKey'
        :empty-text="queryLoading ? ' ' : (emptyTextKey ? $t(emptyTextKey) : (emptyText || $t('common.noData')))"
        :expandRowKeys='expandRowKeys'
        :defaultExpandAll='defaultExpandAll'
        :defaultSort='defaultSort || tableDefaultSort'
        :tooltipEffect='tooltipEffect'
        :spanMethod='spanMethod'
        :selectOnIndeterminate='selectOnIndeterminate'
        :indent='indent'
        :treeProps='treeProps'
        :lazy='lazy'
        :load='load'
        ref='scrollTable'
        @select='selectHandle'
        @select-all='selectAllHandle'
        @selection-change='selectionChangeHandle'
        @cell-mouse-enter='cellMouseEnterHandle'
        @cell-mouse-leave='cellMouseLevelHandle'
        @cell-click='cellClickHandle'
        @cell-dblclick='cellDblclickHandle'
        @row-click='rowClickHandle'
        @row-contextmenu='rowContextmenuHandle'
        @row-dblclick='rowDblclickHandle'
        @header-click='headerClickHandle'
        @header-contextmenu='headerContextmenuHandle'
        @sort-change='sortChangeHandle'
        @filter-change='filterChangeHandle'
        @current-change='currentChangeHandle'
        @header-dragend='headerDragendHandle'
        @expand-change='expandChangeHandle'
        v-loading='queryLoading'
      >
        <template>
          <el-table-column v-if='showSelection' type='selection' :selectable="selectableFunc" width="50" align="center" header-align="center" ></el-table-column>

          <slot name="prefix"></slot>

          <el-table-column
            v-for='item in Array.isArray(tableColumns) ? tableColumns : []'
            :key='item.field'
            :prop='item.prop' :label="resolveColumnText(item, 'label')"
            :min-width="item.minWidth || null" :width="item.width || null"
            :fixed='item.fixed'
            :sortable="item.sortable ? 'custom' : false"
            :sort-orders="['ascending', 'descending', null]"
            :show-overflow-tooltip="item.showOverflowTooltip !== false">

            <template v-if='!!(item.headerDescribe || item.headerDescribeKey)' slot="header">
              <span>
                {{ resolveColumnText(item, 'label') }}
                <el-tooltip effect="light" :enterable="false" :content="resolveColumnText(item, 'headerDescribe')" placement="top">
                  <i class="el-icon el-icon-warning-outline grey"></i>
                </el-tooltip>
              </span>
            </template>

            <template slot-scope="{ row }">
              <!-- 特殊处理 TABLE_CELL_EMPTY 值，显示空 -->
              <template v-if="row[item.field] === 'TABLE_CELL_EMPTY'"></template>

              <!-- column slot -->
              <template v-else-if="item.slot">
                <slot :name="item.slot" v-bind="{ column: item, row }"></slot>
              </template>

              <!-- 默认table cell -->
              <div v-else-if='!item.type || item.type === "default"' :class='["scroll-el-table-row ell", item.extraClass]'>
                <!-- label前置图标（db-icon字体图标）： prefixIcon -->
                <template v-if='!row[item.field] && !item.unit'>
                  <span>-</span>
                </template>
                <template v-else>
                  <span v-if='item.prefixIcon' class="db-icon mr-5">{{ item.prefixIcon | DbIconFilter }}</span>
                  <span v-if='item.handleClick && typeof item.handleClick === "function" && (item.canClick === undefined || item.canClick(row)) && (row[item.field] && row[item.field] !== "-")' @click.stop="item.handleClick(row)" class="table-item-with-action blue cphu">
                    {{ item.prefix }}{{ row[item.field] | getFilterByUnit(item.unit, item.lessZeroOneKey ? !!row[item.lessZeroOneKey] : item.lessZeroOne, item.zeroIgnore) }}{{ item.suffix }}
                  </span>
                  <span v-if='!item.handleClick || (item.canClick && !item.canClick(row))'>{{ item.prefix }}{{ row[item.field] | getFilterByUnit(item.unit, item.lessZeroOneKey ? !!row[item.lessZeroOneKey] : item.lessZeroOne, item.zeroIgnore) }}{{ item.suffix }}</span>
                  <!-- label后置图标（db-icon字体图标）： suffixIcon -->
                  <span v-if='item.suffixIcon' class="db-icon ml-5">{{ item.suffixIcon | DbIconFilter }}</span>
                </template>
              </div>

              <!-- service table cell: 可以前置服务图标<serviceType, service_type, type> -->
              <div v-else-if='item.type === "service"' class="scroll-el-table-row ell">
                <template v-if='!row[item.field] && !item.unit'>
                  <span>-</span>
                </template>
                <template v-else>
                  <!-- label前置图标（db-icon字体图标）： prefixIcon -->
                  <span v-if='(row[item.field] && row[item.field] !== "-")' :class='["db-icon mr-5 vm", !!item.handleClick ? "db-blue" : ""]'>{{ ( row.type || row.language || row.service_type || row.serviceType || 'default') | DbIconFilter }}</span>
                  <span v-if='item.handleClick && (row[item.field] && row[item.field] !== "-")' @click.stop="item.handleClick(row)"  class="db-blue cphu ell">{{ item.prefix }}{{ row[item.field] || '-' }}{{ item.suffix }}</span>
                  <span v-else class="ell">{{ item.prefix }}{{ row[item.field] || '-' }}{{ item.suffix }}</span>
                </template>
              </div>

              <div v-else-if='item.type === "alarmLevel"' class="scroll-el-table-row ell">
                <i class="alarm-level-dot" :data-level='row[item.field]'></i>
                <span class="vm">{{ item.prefix }}{{ row[item.field] | AlarmStatusFilter }}{{ item.suffix }}</span>
              </div>
              <div v-else-if='item.type === "alarmStatus"' class="scroll-el-table-row ell">
                <i v-if='row[item.field] === 0' class="db-icon db-icon-more-pie icon-vm db-yellow mr-5"></i>
                <i v-if='row[item.field] === 2' class="db-icon db-icon-dealing icon-vm db-blue mr-5"></i>
                <i v-if='[3, 4].includes(row[item.field])' class="db-icon db-icon-right-pie icon-vm db-green mr-5"></i>
                <span>{{ item.prefix }}{{ row[item.field] | AlarmDealStatusFilter }}{{ item.suffix }}</span>
              </div>

              <!-- progress table cell: 百分比或数值进度条 -->
              <div v-else-if='item.type === "progress"' :class='["scroll-el-table-row lh-20", item.progressDirection === "horizontal" ? "flex-h" : "flex-v"]'>
                <el-progress v-if='item.progressType === "circle"' type="circle" :width='20'
                  :percentage="row.progressValue && row.progressValue[item.field] || 0" :stroke-width="item.progressBarWidth || 2"
                  :show-text="false" stroke-linecap='butt'
                  class="vm mr-5" 
                  :color='item.progressColor || null' :status='item.progressStatus || row.progressStatus && row.progressStatus[item.field] || null'></el-progress>
                <span>{{ item.prefix }}{{ row[item.field] | getFilterByUnit(item.unit, item.lessZeroOneKey ? !!row[item.lessZeroOneKey] : item.lessZeroOne, item.zeroIgnore) }}{{ item.suffix }}</span>
                <div v-if='item.progressType !== "circle"' style='width: 54px;'>
                  <el-progress :width='54'
                    :percentage="row.progressValue && row.progressValue[item.field] || 0" :stroke-width="item.progressBarWidth || 2"
                    :show-text="false" stroke-linecap='butt'
                    :class='["vm", item.progressDirection === "horizontal" ? "ml-5" : "" ]'
                    :status='item.progressStatus || row.progressStatus && row.progressStatus[item.field] || null'></el-progress>
                </div>
              </div>

              <!-- health bar table cell 评分类进度条 -->
              <div v-else-if='item.type === "healthBar"' :class='["scroll-el-table-row", "flex-h", item.extraClass]'>
                <div style='width: 80px;'>
                  <el-progress
                    :percentage="row.progressValue && row.progressValue[item.field] || 0" :stroke-width="item.progressBarWidth || 5"
                    :show-text="false" stroke-linecap='square'
                    class="vm mr-8"
                    :status='item.progressStatus || row.progressStatus && row.progressStatus[item.field] || null'></el-progress>
                </div>
                <span>{{ item.prefix }}{{ row[item.field] | getFilterByUnit(item.unit, item.lessZeroOneKey ? !!row[item.lessZeroOneKey] : item.lessZeroOne, item.zeroIgnore) }}{{ item.suffix }}</span>
              </div>

              <!-- 正常异常状态 -->
              <div v-else-if='item.type === "healthStatus"' :class='["scroll-el-table-row", "flex-h", item.extraClass]'>
                <i v-if='Number(row[item.field]) === 1' class="db-icon db-icon-error-pie font-12 mr-5 db-red vm"></i>
                <i v-else-if='Number(row[item.field]) === 0' class="db-icon db-icon-right-pie font-12 mr-5 db-green vm"></i>
                <i v-else class="el-icon el-icon-warning font-12 mr-5 db-yellow vm"></i>
                <span>{{ resolveHealthStatusText(item, row) }}</span>
              </div>
            </template>
          </el-table-column>

          <slot></slot>

          <slot name="suffix"></slot>

          <template slot="append">
            <slot name="append"></slot>
          </template>
        </template>
      </el-table>
    </div>
  </div>
</template>

<script>
import { debounce } from '@/utils/common'
import { toAsyncWait } from '@/utils/common'
import dayjs from 'dayjs';
import i18n from '@/i18n';
import { StringIsEmpty } from '@/utils/common';
import FilterMap from '@/utils/filters'
import deepClone from 'lodash/cloneDeep';
import sortable from "sortablejs";

export default {
  name: 'scrollElTable',
  props: {
    data: {
      type: Array,
      default() {
        return [];
      }
    },

    size: {
      type: String,
      default: 'small',
    },

    width: [String, Number],

    height: [String, Number],

    maxHeight: [String, Number],

    fit: {
      type: Boolean,
      default: true
    },

    stripe: Boolean,

    border: {
      type: Boolean,
      default: true
    },

    rowKey: [String, Function],

    context: {},

    showHeader: {
      type: Boolean,
      default: true
    },

    showSummary: Boolean,

    sumText: String,
    sumTextKey: String,

    summaryMethod: Function,

    rowClassName: [String, Function],

    rowStyle: [Object, Function],

    cellClassName: [String, Function],

    cellStyle: [Object, Function],

    headerRowClassName: [String, Function],

    headerRowStyle: [Object, Function],

    headerCellClassName: [String, Function],

    headerCellStyle: [Object, Function],

    highlightCurrentRow: {
      type: Boolean,
      default: true,
    },

    currentRowKey: [String, Number],

    emptyText: String,
    emptyTextKey: String,

    expandRowKeys: Array,

    defaultExpandAll: Boolean,

    defaultSort: Object,

    tooltipEffect: {
      type: String,
      default: 'light',
    },

    spanMethod: Function,

    selectOnIndeterminate: {
      type: Boolean,
      default: true
    },

    indent: {
      type: Number,
      default: 16
    },

    treeProps: {
      type: Object,
      default() {
        return {
          hasChildren: 'hasChildren',
          children: 'children'
        };
      }
    },

    lazy: Boolean,

    load: Function,

    scrollMode: {
      type: Boolean,
      default: true,
    },

    total: Number,

    loading: Boolean,

    showSelection: {
      type: Boolean,
      default: false
    },
    selectableFunc: Function,

    queryParams: {
      type: Object,
      default () {
        return {}
      }
    },

    queryApi: Function,

    timeMode: {
      type: Boolean,
      default: true
    },

    autoRefresh: {
      type: Boolean,
      default: true
    },

    offsetMode: {
      type: Boolean,
      default: false
    },

    columnConfig: {
      type: Array,
      default: () => []
    },

    formatFunc: Function,
    tableKey: String,

    showSetting: {
      type: Boolean,
      default: false
    },
    showTotal: {
      type: Boolean,
      default: true
    },
    autoEmitRefresh: {
      type: Boolean,
      default: false
    },
    queryParamsDebounceWait: {
      type: Number,
      default: 300
    },
    tableSortable: {
      type: Boolean,
      default: false
    },
    tableSortableHandle: {
      type: String,
      default: ".table-handler"
    },
    tableSortableAnimate: {
      type: Number,
      default: 100
    }
  },
  filters: {
    getFilterByUnit (value, unit, lessZeroOne, zeroIgnore) {
      if (!unit) {
        return value || '-'
      }
      switch (unit) {
        case 'ns':
          return FilterMap.NsFilter(value, zeroIgnore)
        case 'ms':
          return FilterMap.MsFilter(value, zeroIgnore)
        case 's':
          return FilterMap.SecondFilter(value, zeroIgnore)
        case 'nsDuration':
          return FilterMap.NsDurationFilter(value, zeroIgnore)
        case 'msDuration':
          return FilterMap.MsDurationFilter(value, zeroIgnore)
        case 'sDuration':
          return FilterMap.DurationFilter(value, zeroIgnore)
        case 'b':
          return FilterMap.BytesFilter(value, lessZeroOne)
        case '%':
        case 'percent':
          return FilterMap.PercentFilter(value, lessZeroOne)
        case 'count':
        case '':
          return FilterMap.NumberFilter(value, lessZeroOne)
        case 'time':
          return FilterMap.TimesToDateFilter(value)
        case 'minuteTime':
          return FilterMap.TimesToDateFilter(value, 'YYYY-MM-DD HH:mm')
        case 'alarmType':
          return FilterMap.AlarmTypeFilter(value)
        case 'alarmLevel':
          return FilterMap.AlarmStatusFilter(value)
        case 'alarmStatus':
          return FilterMap.AlarmDealStatusFilter(value)
        case 'processStatus':
          return FilterMap.ProcessStateFilter(value)
        case 'processOriginStatus':
          return FilterMap.ProcessOriginalStateFilter(value)
        case 'clusterType':
          return FilterMap.ClusterTypeFilter(value)
        case 'infraHealth':
          return FilterMap.HealthStatusFilter(value)
        case 'monitorType':
          return FilterMap.MonitorTypeFilter(value)
        case 'monitorMethod':
          return FilterMap.MonitorMethodFilter(value)
        case 'noticeMethod':
          return FilterMap.NoticeMethodFilter(value)
        case 'noticeResult':
          return FilterMap.NoticeResultFilter(value)
        case 'serviceType':
          return FilterMap.ServiceTypeFilter(value)
        case 'serviceRequestType':
          return FilterMap.RequestTypeFilter(value)
        case 'serviceAnalysisType':
          return FilterMap.ServiceAnalysisFilter(value)
        case 'spanStatus':
          return FilterMap.SpanStatusFilter(value)
        case 'firstLetterCapital':
          return FilterMap.FirstLetterCapital(value)
        default:
          return value
      }
    },
  },
  data () {
    return {
      calcScrollHeight: null,
      timer: null,
      scrollContainer: null,
      scrollHandle: null,
      baseQuery: {
        pageSize: 50,
        pageNum: 1,
        
      },
      offsetBaseQuery: {
        offset: 0,
        size: 50,
      },
      sortQuery: {
        sortField: null,
        sortOrder: null,
      },
      tableDefaultSort: {
        prop: null,
        order: null,
      },
      columnsFullLabels: [],
      columnsModels: {},
      tableColumns: [],
      tableSource: [],
      tableTotal: 0,
      queryInited: false,
      queryLoading: false,
      tableSortableKey: 0,
      queryParamsSnapshot: '',
      queryParamsDebounceTimer: null,
    }
  },
  computed: {
    getHeight () {
      return this.scrollMode ? this.calcScrollHeight : this.height
    },
    noMore () {
      return this.tableSource && this.tableTotal && this.tableSource.length >= this.tableTotal
    },
    tableLocalKey () {
      const { path } = this.$route
      const _tableKey = this.tableKey || path
      const localKey = _tableKey ? 'DATABUFF_TABLE_COLUMN_' + _tableKey : null
      return localKey
    }
  },
  watch: {
    queryParams: {
      handler (newVal, oldVal) {
        this.handleQueryParamsChange(newVal, oldVal)
      },
      deep: true,
    },
    '$store.state.globalState.globalTime': {
      handler (newVal) {
        if (this.autoRefresh) {
          this.fetchTableSource(true)
        }
      },
      deep: true,
    },
    'columnConfig': {
      handler (newVal) {
        this.initTableColumns();
        this.doLayout()
      },
      deep: true,
    },
    'data': {
      async handler (newVal) {
        if (!this.queryApi && Array.isArray(newVal)) {
          let _newVal = newVal;
          if (this.formatFunc && typeof this.formatFunc === 'function') {
            _newVal = await this.formatFunc(newVal) || newVal
          }
          this.formatThisData(_newVal);
          this.tableSource = _newVal;
          this.tableTotal = this.total || _newVal?.length || 0;
        }
      },
      immediate: true,
      deep: true,
    },
    tableSortableKey() {
      this.$nextTick(() => {
        this.makeTableSortAble();
        // this.keepWrapperHeight(false);
      });
    }
  },
  methods: {
    resolveI18nText (source, key, fallbackKey) {
      if (key) {
        return i18n.t(key)
      }
      if (source !== undefined && source !== null && source !== '') {
        return source
      }
      return fallbackKey ? i18n.t(fallbackKey) : ''
    },
    resolveColumnText (item, prop) {
      return this.resolveI18nText(item?.[prop], item?.[`${prop}Key`])
    },
    resolveHealthStatusText (item, row) {
      const value = Number(row[item.field])
      if (value === 1) {
        return this.resolveI18nText(item.errorText, item.errorTextKey, 'modules.components.db-table.s_c195df63')
      }
      if (value === 0) {
        return this.resolveI18nText(item.normalText, item.normalTextKey, 'modules.components.db-table.s_fd6e80f1')
      }
      return this.resolveI18nText(item.warningText, item.warningTextKey, 'modules.components.db-table.s_900c70fa')
    },
    clearSelection () {
      this.$refs.scrollTable.clearSelection()
    },
    toggleRowSelection (row, selected) {
      this.$refs.scrollTable.toggleRowSelection(row, selected)
    },
    toggleAllSelection () {
      this.$refs.scrollTable.toggleAllSelection()
    },
    toggleRowExpansion (row, expanded) {
      this.$refs.scrollTable.toggleRowExpansion(row, expanded)
    },
    setCurrentRow (row) {
      this.$refs.scrollTable.setCurrentRow(row)
    },
    clearSort () {
      this.$refs.scrollTable.clearSort()
    },
    clearFilter (columnKeys) {
      this.$refs.scrollTable.clearFilter(columnKeys)
    },
    doLayout () {
      this.$refs.scrollTable.doLayout()
    },
    sort (prop, order) {
      this.$refs.scrollTable.sort(prop, order)
    },
    //
    selectHandle (selection, row) {
      this.$emit('select', selection, row )
    },
    selectAllHandle (selection) {
      this.$emit('select-all', selection)
    },
    selectionChangeHandle (selection) {
      this.$emit('selection-change', selection)
    },
    cellMouseEnterHandle (row, column, cell, event) {
      this.$emit('cell-mouse-enter', row, column, cell, event)
    },
    cellMouseLevelHandle (row, column, cell, event) {
      this.$emit('cell-mouse-leave', row, column, cell, event)
    },
    cellClickHandle (row, column, cell, event) {
      this.$emit('cell-click', row, column, cell, event)
    },
    cellDblclickHandle (row, column, cell, event) {
      this.$emit('cell-dblclick', row, column, cell, event)
    },
    rowClickHandle (row, column, event) {
      this.$emit('row-click', row, column, event)
    },
    rowContextmenuHandle (row, column, event) {
      this.$emit('row-contextmenu', row, column, event)
    },
    rowDblclickHandle (row, column, event) {
      this.$emit('row-dblclick', row, column, event)
    },
    headerClickHandle (column, event) {
      this.$emit('header-click', column, event)
    },
    headerContextmenuHandle (column, event) {
      this.$emit('header-contextmenu', column, event)
    },
    sortChangeHandle ({column, prop, order}) {
      this.sortQuery.sortOrder = order && order === 'descending' ? 'desc' : order === 'ascending' ? 'asc' : null
      this.sortQuery.sortField = order ? prop : null
      this.$emit('sort-change', { column, prop, order })
    },
    filterChangeHandle (filters) {
      this.$emit('filter-change', filters)
    },
    currentChangeHandle (currentRow, oldCurrentRow) {
      this.$emit('current-change', currentRow, oldCurrentRow)
    },
    headerDragendHandle (newWidth, oldWidth, column, event) {
      this.$emit('header-dragend', newWidth, oldWidth, column, event)
    },
    expandChangeHandle (row, expanded) {
      this.$emit('expand-change', row, expanded)
    },
    // 滚动加载相关
    loopGetScrollContainerHandle () {
      if (this.timer) {
        window.clearTimeout(this.timer);
        this.timer = null;
      }
      this.timer = setTimeout(() => {
        const scrollContainer = this.$refs.scrollTable.$el?.querySelector('.el-table__body-wrapper');
        if (!scrollContainer) {
          this.loopGetScrollContainerHandle();
        } else {
          this.scrollContainer = scrollContainer;
          // 滚动到底加载更多
          let scrollX = 0;
          this.scrollHandle = debounce(() => {
            const { scrollHeight, scrollTop, scrollLeft, clientHeight } = scrollContainer
            // 简单的过滤横向滚动
            if (Math.abs(scrollLeft - scrollX) > 0) {
              scrollX = scrollLeft
              return
            }
            if (scrollHeight - clientHeight - scrollTop < 60 && !this.queryLoading && !this.noMore) {
              if (typeof this.queryApi === 'function') {
                this.fetchTableSource()
              } else {
                this.$emit('on-table-scroll')
              }
            }
          }, 17)
          scrollContainer.addEventListener('scroll', this.scrollHandle);
          this.$emit('on-table-inited');
        }
      }, 100)
    },
    // 获取父级高度
    getHeightHandle () {
      const { parentNode, parentElement } = this.$refs.scrollTable.$el
      const parentEl =  parentNode || parentElement
      this.calcScrollHeight = parentEl?.clientHeight;
    },
    clear () {
      this.queryLoading = false
      this.tableSource = []
      this.tableTotal = 0
      this.offsetBaseQuery.offset = 0
      this.offsetBaseQuery.size = 50
      this.baseQuery.pageNum = 1
      this.baseQuery.pageSize = 50
      // 滚动dom需要重置scrollTop为0,避免自动请求
      if (this.scrollContainer) {
        this.scrollContainer.scrollTop = 0;
      }
    },
    refresh () {
      if (!this.queryInited) {
        this.queryInited = true
      }
      this.syncQueryParamsSnapshot()
      this.fetchTableSource(true)
    },
    syncQueryParamsSnapshot () {
      this.queryParamsSnapshot = JSON.stringify(this.queryParams || {})
    },
    isSameQueryParams (params) {
      return JSON.stringify(params || {}) === this.queryParamsSnapshot
    },
    hasStringValueChange (newVal, oldVal) {
      const newP = newVal || {}
      const oldP = oldVal || {}
      const keys = new Set([...Object.keys(newP), ...Object.keys(oldP)])
      for (const key of keys) {
        const nextValue = newP[key]
        const prevValue = oldP[key]
        if (nextValue !== prevValue && (typeof nextValue === 'string' || typeof prevValue === 'string')) {
          return true
        }
      }
      return false
    },
    handleQueryParamsChange (newVal, oldVal) {
      if (!this.autoEmitRefresh) {
        return
      }
      if (!this.queryInited) {
        return
      }
      if (typeof this.queryApi !== 'function') {
        return
      }
      if (this.isSameQueryParams(newVal)) {
        return
      }
      const debounceMs = this.hasStringValueChange(newVal, oldVal) ? this.queryParamsDebounceWait : 0
      window.clearTimeout(this.queryParamsDebounceTimer)
      if (debounceMs > 0) {
        this.queryParamsDebounceTimer = window.setTimeout(() => {
          this.triggerQueryParamsRefresh()
        }, debounceMs)
      } else {
        this.triggerQueryParamsRefresh()
      }
    },
    triggerQueryParamsRefresh () {
      if (!this.autoEmitRefresh || !this.queryInited) {
        return
      }
      if (typeof this.queryApi !== 'function') {
        return
      }
      if (this.isSameQueryParams(this.queryParams)) {
        return
      }
      this.syncQueryParamsSnapshot()
      this.fetchTableSource(true)
    },
    // 如果传入了queryApi，则滚动加载调用
    async fetchTableSource (reset) {
      if (!this.queryInited) {
        return
      }
      if (typeof this.queryApi !== 'function') {
        return;
      }
      if (reset) {
        this.offsetBaseQuery.offset = 0
        this.offsetBaseQuery.size = 50
        this.baseQuery.pageNum = 1
        this.baseQuery.pageSize = 50
        // 滚动dom需要重置scrollTop为0,避免自动请求
        if (this.scrollContainer) {
          this.scrollContainer.scrollTop = 0;
        }
      }
      this.$nextTick(async () => {
        this.queryLoading = true
        const params = {
          ...(this.offsetMode ? this.offsetBaseQuery : this.baseQuery),
          ...this.queryParams,
        }
        if (this.sortQuery.sortOrder) {
          // sortQuery无排序时，将使用queryParams的排序字段（如果有的话）
          params.sortOrder = this.sortQuery.sortOrder
          params.sortField = this.sortQuery.sortField
        }
        if (this.timeMode) {
          const { fromTime, toTime } = this.$store.getters.globalTime && this.$store.getters.globalTime() || {}
          params.fromTime = dayjs(fromTime).format('YYYY-MM-DD HH:mm:ss')
          params.toTime = dayjs(toTime).format('YYYY-MM-DD HH:mm:ss')
        }
        for (const key in params) {
          if (StringIsEmpty(params[key])) {
            delete params[key]
          }
        }
        const { error, result } = await toAsyncWait(this.queryApi(params))
        if (!error) {
          const { data, total } = result || {}
          let resultData = data || [];
          let resultTotal = total || 0;
          if (!Array.isArray(data) && Object.prototype.hasOwnProperty.call(data || {}, 'list')) {
            resultData = data.list || [];
            resultTotal = data.total || 0;
          } else if (!Array.isArray(data) && Object.prototype.hasOwnProperty.call(data || {}, 'data')) {
            resultData = data.data || [];
            resultTotal = data.total || 0;
          }
          if (this.formatFunc && typeof this.formatFunc === 'function') {
            resultData = await this.formatFunc(resultData) || resultData
          }
          const tableSource = reset ? resultData : this.tableSource.concat(resultData)
          this.formatThisData(tableSource)
          this.tableSource = tableSource
          this.tableTotal = resultTotal || this.tableSource.length || 0
          if (this.offsetMode) {
            this.offsetBaseQuery.offset = this.offsetBaseQuery.size + this.offsetBaseQuery.offset
          } else {
            this.baseQuery.pageNum = this.baseQuery.pageNum + 1
          }
        } else if (error.message !== 'interrupt') {
          this.$message.error(error.message);
        }
        this.queryLoading = false;
        this.$emit('on-fetch-end', this.tableSource, this.tableTotal, { ...params });
      })
    },
    toggleColumnsVisibleHandle (val, field) {
      this.columnsModels[field] = val
      if (this.tableLocalKey) {
        this.saveTableLocalModels(this.columnsModels)
        this.tableColumns = this.columnConfig.filter(i => this.columnsModels[i.field])
        this.$emit('on-columns-change', this.tableColumns)
      }
    },
    getTableLocalModels () {
      if (!this.tableLocalKey) {
        return {}
      }
      const tableLocalModels = window.localStorage.getItem(this.tableLocalKey)
      if (!tableLocalModels) {
        return {}
      }
      try {
        const columnModels = JSON.parse(tableLocalModels)
        return columnModels && typeof columnModels === 'object' ? columnModels : {}
      } catch (err) {
        return {}
      }
    },
    saveTableLocalModels (columnModels, options = {}) {
      if (!this.tableLocalKey) {
        return
      }
      const { replace = false } = options
      const nextColumnModels = replace ? { ...columnModels } : {
        ...this.getTableLocalModels(),
        ...columnModels,
      }
      window.localStorage.setItem(this.tableLocalKey, JSON.stringify(nextColumnModels))
    },
    initTableColumns () {
      // column 默认设置处理
      this.columnConfig.forEach(c => {
        c.prop = c.prop || c.field
        // 默认展示 defaultShow 设置
        c.defaultShow = !!c.disabled || (typeof c.defaultShow === 'boolean' ? c.defaultShow : true)
      });
      if (this.showTotal && this.showSetting) {
        // 至少有一列禁止操作，防止列表全部隐藏
        if (!this.columnConfig.find(c => c.disabled)) {
          const column = this.columnConfig.find(c => c.defaultShow)
          column.disabled = true
        }
        // 格式化columnsFullLabels
        const _columnModels = this.getTableLocalModels()
        // 动态列可能会晚于表格初始化返回，这里保留未知 key，避免本地勾选状态被提前清掉。
        this.columnConfig.forEach(c => {
          if (typeof _columnModels[c.field] !== 'boolean' || c.disabled) {
            _columnModels[c.field] = c.defaultShow
          }
        })
        this.columnsModels = _columnModels
        this.saveTableLocalModels(this.columnsModels)
        this.tableColumns = this.columnConfig.filter(i => _columnModels[i.field])
      } else {
        this.tableColumns = this.columnConfig
      }
      this.$emit('on-columns-inited', this.tableColumns)
    },
    resetTableColumns () {
      const _columnModels = {}
      // 把默认展示的项设为true,其他设为false
      this.columnConfig.forEach(i => {
        _columnModels[i.field] = typeof i.defaultShow === 'boolean' ? i.defaultShow : true
      })
      this.columnsModels = _columnModels
      this.tableColumns = this.columnConfig.filter(i => _columnModels[i.field])
      this.saveTableLocalModels(this.columnsModels, { replace: true })
      this.$emit('on-columns-change', this.tableColumns)
    },
    formatThisData (data) {
      if (Array.isArray(data)) {
        const hasProgressColumns = this.columnConfig.filter((c) => c.type === 'progress')
        if (hasProgressColumns.length) {
          const cFieldsMax = {};
          hasProgressColumns.forEach((c) => {
            const cf = c.field;
            const cfMax = c.progressMax || Math.max(...data.filter(i => typeof i[cf] === 'number').map((i) => i[cf]));
            cFieldsMax[cf] = isNaN(cfMax) || !isFinite(cfMax) ? 0 : cfMax
          });
          data.forEach((item) => {
            if (!item.progressValue) {
              item.progressValue = {}
            }
            for (const cf in cFieldsMax) {
              if (typeof item[cf] === 'number') {
                const calcPct = Math.min((item[cf] / cFieldsMax[cf]) * 100, 100);
                item.progressValue[cf] = calcPct < 1 && calcPct > 0 ? 1 : calcPct;
              }
            }
          });
        }
      }
    },
    makeTableSortAble() {
      const table = this.$children[0].$el.querySelector(
        ".el-table__body-wrapper tbody"
      );
      sortable.create(table, {
        handle: this.tableSortableHandle,
        animation: this.tableSortableAnimate,
        onStart: () => {
          this.$emit("drag");
        },
        onEnd: ({ newIndex, oldIndex }) => {
          // this.keepWrapperHeight(true);
          this.tableSortableKey = Math.random();
          const arr = this.$children[0].data;
          const targetRow = arr.splice(oldIndex, 1)[0];
          arr.splice(newIndex, 0, targetRow);
          this.$emit("drop", { targetObject: targetRow, list: arr });
        }
      });
    },
    keepWrapperHeight(keep) {
      // eslint-disable-next-line prefer-destructuring
      const wrapper = this.$refs.wrapper;
      if (keep) {
        wrapper.style.minHeight = `${wrapper.clientHeight}px`;
      } else {
        wrapper.style.minHeight = "auto";
      }
    }
  },
  created () {
    // console.log(this.scrollMode)
    // console.log(this.height)
    this.initTableColumns();
    // 初始化sortQuery参数： 判断columnConfig有无defaultSort的项
    const hasDefaultSort = this.tableColumns?.find(i => i.defaultSort)
    if (hasDefaultSort) {
      this.sortQuery.sortField = hasDefaultSort.prop
      this.sortQuery.sortOrder = hasDefaultSort.defaultSort === 'desc' ? 'desc' : 'asc'
      this.tableDefaultSort.prop = hasDefaultSort.prop
      this.tableDefaultSort.order = hasDefaultSort.defaultSort === 'desc' ? 'descending' : 'ascending'
    }
  },
  mounted () {
    // if (typeof this.scrollMode === 'boolean' && this.scrollMode) {
      // scroll-mode 自动获取父级的高度，
    if (this.$refs.scrollTable && this.$refs.scrollTable.$el) {
      // 获取父级容器
      const { parentNode, parentElement } = this.$refs.scrollTable.$el
      const parentEl =  parentNode || parentElement
      if (parentEl) {
        this.calcScrollHeight = parentEl.clientHeight;
        this.loopGetScrollContainerHandle();
        window.addEventListener('resize', this.getHeightHandle);
      }
    }
    if (this.tableSortable) {
      this.makeTableSortAble();
    }
    // }
  },
  beforeDestroy () {
    if (this.timer) {
      window.clearTimeout(this.timer);
      this.timer = null;
    }
    if (this.queryParamsDebounceTimer) {
      window.clearTimeout(this.queryParamsDebounceTimer);
      this.queryParamsDebounceTimer = null;
    }
    if (this.scrollContainer) {
      this.scrollContainer.removeEventListener('scroll', this.scrollHandle)
      window.removeEventListener('resize', this.getHeightHandle);
    }
  }
}
</script>

<style lang='scss' scoped>
.scroll-el-table {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.scroll-el-table-header {
  padding: 10px 0;
  line-height: 20px;
}
.scroll-el-table-body {
  flex: 1;
  overflow: hidden;
  :deep(.el-table--border .el-table__cell),
  :deep(.el-table__body-wrapper .el-table--border.is-scrolling-left~.el-table__fixed) {
    border-right-color: transparent;
  }
  :deep(.el-table.el-table--border td:first-child),
  :deep(.el-table.el-table--border tr:first-child th:first-child) {
    border-left-color: transparent;
  }
  :deep(.el-table--border::after),
  :deep(.el-table--group::after) {
    background-color: transparent;
  }
}
.scroll-el-table-row {
  display: inline;
}
.table-item-with-action {
  padding: 5px 8px 5px 0;
}
.alarm-level-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  margin-right: 8px;
  vertical-align: middle;

  &[data-level="3"] {
    background-color: var(--color-danger);
  }
  &[data-level="2"] {
    background-color: var(--color-warning);
  }
  &[data-level="4"] {
    background-color: var(--color-info);
  }
  &[data-level="1"] {
    background-color: var(--color-info);
  }
}

.table-columns-setting {
  margin: -12px;
  padding: 10px 4px 0;
  .table-column-reset {
    margin: 0 -4px;
    padding: 6px 12px;
    border-top: 1px solid var(--border-color-lighter);
    border-bottom: 1px solid transparent;
  }
  .table-column-list {
    padding: 0 8px;
    max-height: 196px;
    overflow: auto;
  }
  .table-column-item {
    margin: 0 0 6px;
    padding: 4px;
    display: flex;
    align-items: center;
  }
  :deep(.el-checkbox__inner) {
    display: block;
  }
  :deep(.el-checkbox__input.is-disabled.is-checked .el-checkbox__inner) {
    background-color: var(--color-primary);
    border-color: var(--color-primary);
    opacity: 0.5;
    &::after {
      border-color: #FFFFFF;
    }
  }
  :deep(.el-checkbox__input+.el-checkbox__label) {
    padding-left: 7px;
    color: var(--color-text-regular);
    text-overflow: ellipsis;
    white-space: nowrap;
    overflow: hidden;
    font-size: 12px;
    line-height: 16px;
  }
  :deep(.el-checkbox__input.is-disabled.is-checked+span.el-checkbox__label) {
    color: var(--color-text-regular);
  }
}
</style>
