<template>
  <div class="table-list"
    v-loading="queryParams.pageNum === 1 && isLoading">
    <div class="list-head">
      <span class="time-range">{{ $t('modules.views.appMonitor.serviceCallDetail.s_c4023f57', { value0: timeParams.fromTime, value1: timeParams.toTime }) }}</span>
      <span class="list-total describe">{{ $t('modules.views.alarmCenter.alarm.s_9ad9830c', { value0: listTotal }) }}</span>
    </div>

    <div ref="tableWrap" class="list-body">
      <el-table
        ref="table"
        :data="tableList"
        @row-click="showDetailHandle"
        @sort-change="sortChangeHandle"
        :height="tableHeight"
        :empty-text="!isLoading ? $t('modules.views.appMonitor.errorDetail.s_21efd88b') : ' '"
        :default-sort="{ prop: 'start', order: 'descending' }"
        highlight-current-row size="small"
        tooltip-effect="light"
        :row-style="{ cursor: 'pointer' }"
        class="table"
      >
        <el-table-column
          v-for="col in columns.filter(col => !col.group)"
          :key="col.value"
          :label="col.label"
          :prop="col.prop"
          :sortable="col.prop ? 'custom' : false"
          :sort-orders="['descending', 'ascending', null]"
          :min-width="col.minWidth"
          show-overflow-tooltip>
          <template slot-scope="{ row }">
            <template v-if="col.value === 'start'">{{ String(row.start).substring(0, 13) | TimesToDateFilter }}</template>
            <template v-else-if="col.value === 'delay'">{{ row[col.value] | NsFilter }}</template>
            <template v-else>{{ row[col.value] || '-' }}</template>
          </template>
        </el-table-column>

        <el-table-column :label="$t('modules.views.appMonitor.serviceCallDetail.s_9b07d85b')">
          <el-table-column
          v-for="col in columns.filter(col => col.group === '发出端')"
          :key="col.value"
          :label="col.label"
          :prop="col.prop"
          :sortable="col.prop ? 'custom' : false"
          :sort-orders="['descending', 'ascending', null]"
          :min-width="col.minWidth"
          show-overflow-tooltip>
            <template slot-scope="{ row }">
              <template v-if="col.value === 'client.duration'">{{ row[col.value] | NsFilter }}</template>
              <div v-else-if="col.value === 'client.error'" class="flex-h">
                <i v-if='Number(row[col.value]) === 1' class="db-icon db-icon-error-pie font-12 mr-5 db-red vm"></i>
                <i v-else-if='Number(row[col.value]) === 0' class="db-icon db-icon-right-pie font-12 mr-5 db-green vm"></i>
                <i v-else class="el-icon el-icon-warning font-12 mr-5 db-yellow vm"></i>
                <span>{{ Number(row[col.value]) === 1 ? $t('modules.views.appMonitor.service.s_c195df63') : Number(row[col.value]) === 0 ? $t('modules.views.appMonitor.service.s_fd6e80f1') : $t('modules.views.alarmCenter.eventDetail.s_1efeae37')  }}</span>
              </div>
              <template v-else>{{ row[col.value] || '-' }}</template>
            </template>
          </el-table-column>
        </el-table-column>

        <el-table-column :label="$t('modules.views.appMonitor.serviceCallDetail.s_3e2847b3')">
          <el-table-column
          v-for="col in columns.filter(col => col.group === '接收端')"
          :key="col.value"
          :label="col.label"
          :prop="col.prop"
          :sortable="col.prop ? 'custom' : false"
          :sort-orders="['descending', 'ascending', null]"
          :min-width="col.minWidth"
          show-overflow-tooltip>
            <template slot-scope="{ row }">
              <template v-if="col.value === 'server.duration'">{{ row[col.value] | NsFilter }}</template>
              <div v-else-if="col.value === 'server.error'" class="flex-h">
                <i v-if='Number(row[col.value]) === 1' class="db-icon db-icon-error-pie font-12 mr-5 db-red vm"></i>
                <i v-else-if='Number(row[col.value]) === 0' class="db-icon db-icon-right-pie font-12 mr-5 db-green vm"></i>
                <i v-else class="el-icon el-icon-warning font-12 mr-5 db-yellow vm"></i>
                <span>{{ Number(row[col.value]) === 1 ? $t('modules.views.appMonitor.service.s_c195df63') : Number(row[col.value]) === 0 ? $t('modules.views.appMonitor.service.s_fd6e80f1') : $t('modules.views.alarmCenter.eventDetail.s_1efeae37')  }}</span>
              </div>
              <template v-else>{{ row[col.value] || '-' }}</template>
            </template>
          </el-table-column>
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
  @Prop({ default: '' }) private componentType!: string;

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

  private columnsFullLabels = [
    { label: i18n.t('modules.views.alarmCenter.eventDetail.s_592c5958') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_592c5958', value: 'start', prop: 'start', disabled: true, minWidth: 150 },
    { label: i18n.t('modules.views.alarmCenter.eventDetail.s_34cab80c') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_34cab80c', value: 'resource', prop: 'resource', disabled: true, minWidth: 200 },
    { label: 'TraceID', value: 'trace_id', minWidth: 100 },
    // HTTP请求 service.http
    { label: i18n.t('modules.views.alarmCenter.eventDetail.s_ea340b9d') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_ea340b9d', value: 'httpMethod', type: 'service.http', minWidth: 100 },
    { label: 'Url', value: 'url', type: 'service.http', minWidth: 100 },
    // RPC请求 service.rpc
    // { label: i18n.t('modules.views.appMonitor.serviceAnalysis.s_5b26b249') as string, labelKey: 'modules.views.appMonitor.serviceAnalysis.s_5b26b249', value: 'type', type: 'service.rpc', minWidth: 100 },
    // MQ消费 service.mq
    { label: 'Topic', value: 'topic', type: 'service.mq', minWidth: 100 },
    { label: 'ConsumerGroup', value: 'group', type: 'service.mq', minWidth: 100 },
    { label: 'Partition', value: 'partition', type: 'service.mq', minWidth: 100 },
    { label: 'MQ Type', value: 'type', type: 'service.mq', minWidth: 100 },
    { label: 'Broker', value: 'broker', type: 'service.mq', minWidth: 100 },
    { label: i18n.t('modules.views.appMonitor.resourceDetail.s_8877d7fc') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_8877d7fc', value: 'delay', type: 'service.mq', minWidth: 100 },
    // SQL调用 service.db
    { label: i18n.t('modules.views.appMonitor.serviceCallDetail.s_55032721') as string, labelKey: 'modules.views.appMonitor.serviceCallDetail.s_55032721', value: 'dbPort', type: 'service.db', minWidth: 100 },
    { label: i18n.t('modules.views.appMonitor.serviceAnalysis.s_84b916da') as string, labelKey: 'modules.views.appMonitor.serviceAnalysis.s_84b916da', value: 'dbType', type: 'service.db', minWidth: 100 },
    { label: i18n.t('modules.views.alarmCenter.eventDetail.s_de9cc3dd') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_de9cc3dd', value: 'sqlOperation', type: 'service.db', minWidth: 100 },
    { label: i18n.t('modules.views.alarmCenter.eventDetail.s_5ccbbd01') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_5ccbbd01', value: 'sqlDatabase', type: 'service.db', minWidth: 100 },
    { label: i18n.t('modules.views.appMonitor.resourceDetail.s_d181886c') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_d181886c', value: 'updateRows', type: 'service.db', minWidth: 100 },
    { label: i18n.t('modules.views.appMonitor.serviceCallDetail.s_973a6f08') as string, labelKey: 'modules.views.appMonitor.resourceDetail.s_973a6f08', value: 'returnRows', type: 'service.db', minWidth: 100 },
    // 发出端
    { label: i18n.t('modules.views.appMonitor.relationMap.s_207c26c9') as string, labelKey: 'modules.views.appMonitor.relationMap.s_207c26c9', value: 'client.duration', group: i18n.t('modules.views.appMonitor.serviceCallDetail.s_9b07d85b') as string, groupKey: 'modules.views.appMonitor.serviceCallDetail.s_9b07d85b', minWidth: 100 },
    { label: i18n.t('modules.views.alarmCenter.eventDetail.s_3fea7ca7') as string, labelKey: 'modules.views.aiPlatform.experts.s_3fea7ca7', value: 'client.error', group: i18n.t('modules.views.appMonitor.serviceCallDetail.s_9b07d85b') as string, groupKey: 'modules.views.appMonitor.serviceCallDetail.s_9b07d85b', minWidth: 100 },
    { label: i18n.t('modules.views.alarmCenter.eventDetail.s_771d897d') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_771d897d', value: 'client.statusCode', type: 'service.http', group: i18n.t('modules.views.appMonitor.serviceCallDetail.s_9b07d85b') as string, groupKey: 'modules.views.appMonitor.serviceCallDetail.s_9b07d85b', minWidth: 100 },
    { label: i18n.t('modules.views.appMonitor.serviceCallDetail.s_2342d094') as string, labelKey: 'modules.views.appMonitor.serviceCallDetail.s_2342d094', value: 'client.threadName', type: 'service.rpc', group: i18n.t('modules.views.appMonitor.serviceCallDetail.s_9b07d85b') as string, groupKey: 'modules.views.appMonitor.serviceCallDetail.s_9b07d85b', minWidth: 100 },
    // 接收端
    { label: i18n.t('modules.views.appMonitor.relationMap.s_207c26c9') as string, labelKey: 'modules.views.appMonitor.relationMap.s_207c26c9', value: 'server.duration', group: i18n.t('modules.views.appMonitor.serviceCallDetail.s_3e2847b3') as string, groupKey: 'modules.views.appMonitor.serviceCallDetail.s_3e2847b3', minWidth: 100 },
    { label: i18n.t('modules.views.alarmCenter.eventDetail.s_3fea7ca7') as string, labelKey: 'modules.views.aiPlatform.experts.s_3fea7ca7', value: 'server.error', group: i18n.t('modules.views.appMonitor.serviceCallDetail.s_3e2847b3') as string, groupKey: 'modules.views.appMonitor.serviceCallDetail.s_3e2847b3', minWidth: 100 },
    { label: i18n.t('modules.views.alarmCenter.eventDetail.s_771d897d') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_771d897d', value: 'server.statusCode', type: 'service.http', group: i18n.t('modules.views.appMonitor.serviceCallDetail.s_3e2847b3') as string, groupKey: 'modules.views.appMonitor.serviceCallDetail.s_3e2847b3', minWidth: 100 },
    { label: i18n.t('modules.views.appMonitor.serviceCallDetail.s_2342d094') as string, labelKey: 'modules.views.appMonitor.serviceCallDetail.s_2342d094', value: 'server.threadName', type: 'service.rpc', group: i18n.t('modules.views.appMonitor.serviceCallDetail.s_3e2847b3') as string, groupKey: 'modules.views.appMonitor.serviceCallDetail.s_3e2847b3', minWidth: 100 },
  ];
  get columns () {
    return this.columnsFullLabels.filter((item) => {
      // 动态隐藏 RPC 调用详情中始终为空的「线程名」列：
      // 当前列表所有行的 threadName 均为空时不展示该列
      if (item.value === 'client.threadName' || item.value === 'server.threadName') {
        const hasValue = this.tableList.some((row: any) => {
          const v = row[item.value]
          return v !== undefined && v !== null && v !== ''
        })
        if (!hasValue) {
          return false
        }
      }
      if (Array.isArray(item.type)) {
        return item.type.includes(this.componentType)
      } else {
        return !item.type || item.type === this.componentType
      }
    })
  }

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
      fromTime, toTime,
      offset: (page - 1) * pageSize,
      size: pageSize,
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
    const { result, error } = await toAsyncWait(ServiceApi.getServiceCallSpans(params));
    this.isLoading = false;
    if (!error) {
      if (page === 1 && this.scrollContainer) {
        // 滚动区域 scrollTop 置为 0
        this.scrollContainer.scrollTop = 0
      }
      const data = result.data || []
      const list = data.map((item: any) => {
        Object.entries(item.client || {}).forEach(([key, value]: any) => {
          item[`client.${key}`] = value
        })
        Object.entries(item.server || {}).forEach(([key, value]: any) => {
          item[`server.${key}`] = value
        })
        return item
      })
      this.listTotal = result.total || 0
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

  // 链路详情
  private showDetailHandle (row: any) {
    const spanId = row['client.span_id'] || row['server.span_id']
    const spanStart = String(row?.start).substring(0, 13);
    const spanEnd = String(row?.end).substring(0, 13);
    this.$router.push({
      path: '/appMonitor/traceDetail',
      query: {
        spid: encodeURIComponent(spanId),
        tid: encodeURIComponent(row.trace_id),
        ft: `${+spanStart}`,
        tt: `${+spanEnd}`,
      }
    })
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
}
</script>

<style lang="scss" scoped>
.table-list {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background-color: var(--bg-color);
  padding: 16px 20px 20px;
  .list-head {
    margin-bottom: 10px;
    font-size: 13px;
    line-height: 22px;
    .time-range {
      margin-right: 10px;
      color: var(--color-text-regular);
    }
  }
  .list-body {
    height: calc(100% - 32px);
    .table {
      height: 100%;
      overflow: visible;
    }
  }
}
</style>
