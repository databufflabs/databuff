<template>
  <div class="table-list"
    v-loading="queryParams.pageNum === 1 && isLoading">
    <div class="list-head">
      <span class="list-total describe">{{ $t('modules.views.alarmCenter.alarm.s_9ad9830c', { value0: listTotal }) }}</span>
    </div>

    <div ref="tableWrap" class="list-body">
      <el-table
        ref="table"
        :data="tableList"
        @row-click="showTraceDetailHandle"
        @sort-change="sortChangeHandle"
        :height="tableHeight"
        :empty-text="!isLoading ? $t('modules.views.appMonitor.errorDetail.s_21efd88b') : ' '"
        :row-style='{ cursor: "pointer" }'
        highlight-current-row size="small"
        tooltip-effect="light"
        class="table"
      >

        <el-table-column :label="$t('modules.views.alarmCenter.eventDetail.s_34cab80c')" :min-width="200">
          <template slot-scope="{row}">
            <span class="db-blue cphu">
              {{ row.resource || '-' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="$t('modules.views.appMonitor.errorDetail.s_13f7745f')" :min-width="150">
          <template slot-scope="{row}">
            <span class="">
              {{ String(row.start).substring(0, 13) | TimesToDateFilter }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="$t('modules.views.alarmCenter.eventDetail.s_f700c855')" :min-width="150">
          <template slot-scope="{row}">
            <span class="">
              <i class="db-red db-icon-warning mr-5"></i>
              <span>{{ row.errorType || '-' }}</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column
          v-for="col in columns"
          :key="col.value"
          :label="col.label"
          :prop="col.prop"
          :sortable="col.prop ? 'custom' : false"
          :sort-orders="['descending', 'ascending', null]"
          :min-width="col.minWidth"
          show-overflow-tooltip>
          <template slot-scope="{ row }">
            <template>{{ row[col.value] || '-' }}</template>
          </template>
        </el-table-column>

        <div v-if="queryParams.pageNum > 1" slot="append" class="table-load-tips">
          <template v-if="!noMore">
            <i class="el-icon-loading"></i> {{ $t('modules.views.appMonitor.errorDetail.s_f09b1233') }}
          </template>
          <template v-else>{{ $t('modules.views.appMonitor.errorDetail.s_37c38521') }}</template>
        </div>
      </el-table>
    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import i18n from '@/i18n';
import { Table } from 'element-ui'
import { debounce } from '@/utils/common';
import ServiceApi from '@/api/service';
import { toAsyncWait } from '@/utils/common';

@Component
export default class TableList extends Vue {
  @Prop({ default: () => ({}) }) private query!: any;
  @Prop({ default: () => ({}) }) private timeParams!: any;

  public $refs!: {
    tableWrap: HTMLDivElement,
    table: Table,
  }

  private timer: any = null;
  private scrollContainer: any = null;
  private scrollHandle: any = null;
  private tableHeight: number = 360;

  private queryParams: any = {
    pageNum: 1,
    pageSize: 50,
    sortField: 'start',
    sortOrder: 'desc',
  }

  private isLoading = false
  private listTotal = 0
  private tableList: any[] = []
  get noMore () {
    return this.tableList.length >= this.listTotal
  }

  private columns = [
    // { label: i18n.t('modules.views.alarmCenter.eventDetail.s_34cab80c') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_34cab80c', value: 'resource', minWidth: 200 },
    // { label: i18n.t('modules.views.appMonitor.errorDetail.s_13f7745f') as string, labelKey: 'modules.views.appMonitor.errorDetail.s_13f7745f', value: 'start', minWidth: 150 },
    // { label: i18n.t('modules.views.alarmCenter.eventDetail.s_f700c855') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_f700c855', value: 'meta.error.type', minWidth: 150 },
    { label: i18n.t('modules.views.alarmCenter.alarm.s_47d68cd0') as string, labelKey: 'modules.views.alarmCenter.alarm.s_47d68cd0', value: 'service', minWidth: 150 },
    { label: i18n.t('modules.views.alarmCenter.alarm.s_71673bab') as string, labelKey: 'modules.utils.filters.s_71673bab', value: 'serviceInstance', minWidth: 150 },
    { label: i18n.t('modules.views.appMonitor.errorDetail.s_ef4c0fd6') as string, labelKey: 'modules.views.appMonitor.errorDetail.s_ef4c0fd6', value: 'hostName', prop: 'hostName', minWidth: 150 },
  ];

  private mounted () {
    this.getTableHeight()
    window.addEventListener('resize', this.getTableHeight);
  }

  private beforeDestroy () {
    if (this.timer) {
      window.clearTimeout(this.timer);
      this.timer = null;
    }
    if (this.scrollContainer) {
      this.scrollContainer.removeEventListener('scroll', this.scrollHandle)
    }
    window.removeEventListener('resize', this.getTableHeight);
  }

  public async getData () {
    this.getTableList()
  }

  private async getTableList(page = 1) {
    if (this.isLoading || (page !== 1 && this.noMore)) {
      return;
    }
    this.queryParams.pageNum = page
    const { fromTime, toTime } = this.timeParams
    const { pageSize, sortOrder, sortField } = this.queryParams
    const params: any = {
      ...this.query,
      offset: (page - 1) * pageSize,
      size: pageSize,
      fromTime,
      toTime,
    }
    Object.entries(params).forEach(([key, value]) => {
      if (value === '') {
        delete params[key]
      }
    })
    if (sortOrder) {
      params.sortOrder = sortOrder
      params.sortField = sortField
    }
    this.isLoading = true;
    const { result, error } = await toAsyncWait(ServiceApi.getResourceErrorSpanList(params));
    this.isLoading = false;
    if (!error) {
      if (page === 1 && this.scrollContainer) {
        // 滚动区域 scrollTop 置为 0
        this.scrollContainer.scrollTop = 0
      }
      const responseData = result.data || {}
      const rawList = Array.isArray(responseData)
        ? responseData
        : (responseData.list || [])
      const list = rawList.map((item: any) => {
        const _meta: any = {};
        Object.entries(item.meta || {}).forEach(([key, value]: any) => {
          _meta[`meta.${key}`] = value
        })
        // 错误类型以后端统一计算的 errorType 为准（含 resource fallback），勿用可能为空的 meta['error.type'] 覆盖
        return { ...item, meta: item.meta || {}, ..._meta }
      })
      this.listTotal = responseData.total || result.total || 0
      this.tableList = page === 1 ? list : Array.from(this.tableList).concat(list);
      this.$nextTick(() => {
        if (!this.scrollContainer) {
          this.loop();
        }
      })
    }
  }

  // 列表排序
  private sortChangeHandle (data: any) {
    const { prop, order } = data
    this.queryParams.sortOrder = order === 'ascending' ? 'asc' : order === 'descending' ? 'desc' : ''
    this.queryParams.sortField = order ? prop : ''
    this.getTableList()
  }

  // 滚动加载相关
  private loop () {
    if (this.timer) {
      window.clearTimeout(this.timer);
      this.timer = null;
    }
    this.timer = setTimeout(() => {
      const scrollContainer = this.$refs.tableWrap.querySelector('.el-table__body-wrapper');
      if (!scrollContainer) {
        this.loop();
      } else {
        this.scrollContainer = scrollContainer;
        // 滚动到底加载更多
        this.scrollHandle = debounce(() => {
          const { scrollHeight, scrollTop, clientHeight } = scrollContainer
          if (!this.noMore && !this.isLoading && scrollHeight - clientHeight - scrollTop < 50) {
            this.getTableList(this.queryParams.pageNum + 1)
          }
        }, 17)
        scrollContainer.addEventListener('scroll', this.scrollHandle)
      }
    }, 100)
  }
  private getTableHeight () {
    this.$nextTick(() => {
      const { clientHeight } = this.$refs.tableWrap
      this.tableHeight = clientHeight
    })
  }

  // 查看链路详情
  private showTraceDetailHandle (row: any) {
    if (!row?.trace_id || !row?.span_id) {
      this.$message.warning(i18n.t('modules.views.appMonitor.errorDetail.s_774dc606') as string)
      return
    }
    const spanStart = String(row.start).substring(0, 13);
    const spanEnd = String(row.end).substring(0, 13);
    this.$router.push({
      path: '/appMonitor/traceDetail',
      query: {
        spid: encodeURIComponent(row.span_id),
        tid: encodeURIComponent(row.trace_id),
        ft: `${+spanStart}`,
        tt: `${+spanEnd}`,
      }
    })
  }
}
</script>

<style lang="scss" scoped>
.table-list {
  padding: 0;
  background: var(--bg-color);
  .list-head {
    display: flex;
    align-items: center;
    margin-bottom: 10px;
    font-size: 13px;
    line-height: 22px;
  }
  .list-body {
    height: calc(100% - 32px);
    .table {
      height: 100%;
      overflow: visible;
      .action-disabled {
        color: var(--color-text-secondary);
        pointer-events: none;
      }
    }
  }
}
</style>
