<template>
  <div class="table-list"
    v-loading="queryParams.pageNum === 1 && isLoading">
    <div class="list-head flex-h-jc">
      <db-radio v-model='currType' :options='chartTypes' @change='getTableList()'></db-radio>
      <span class="list-total describe">{{ $t('modules.views.alarmCenter.alarm.s_9ad9830c', { value0: listTotal }) }}</span>
    </div>

    <div ref="tableWrap" class="list-body">
      <el-table
        ref="table"
        :data="tableList"
        @sort-change="sortChangeHandle"
        :height="tableHeight"
        :empty-text="!isLoading ? $t('modules.views.appMonitor.errorDetail.s_21efd88b') : ' '"
        highlight-current-row size="small"
        tooltip-effect="light"
        class="table"
      >
        <el-table-column :label="currType | nameFilter" :prop="currType" :min-width="200" show-overflow-tooltip row-class-name="copy-ss">
          <template slot-scope="{ row }">
            <div class="copy-box">
              <i class="db-icon-warning db-red mr-5"></i>
              <span @click="viewDetailHandle(row)" class="db-blue cphu">{{ row[currType] || '-' }}</span>
              <i @click.stop="copyNameHandle(row[currType] || '-')" class="db-icon-copy copy-btn"></i>
            </div>
          </template>
        </el-table-column>

        <el-table-column :label="$t('modules.views.appMonitor.errors.s_8731f2a8')" prop="errCnt" :min-width="100" show-overflow-tooltip>
          <template slot-scope="{ row }">
            <span>{{ row.errCnt | NumberFilter }}</span>
            <div style='width: 54px;'>
              <el-progress :width='54'
                :percentage="row.progressValue && row.progressValue.errCnt || 0" :stroke-width="2"
                :show-text="false" stroke-linecap='butt'
                class="vm"></el-progress>
            </div>
          </template>
        </el-table-column>

        <el-table-column :label="$t('modules.views.appMonitor.errors.s_693890e7')" prop="percent" :min-width="100" show-overflow-tooltip>
          <template slot-scope="{ row }">
            <el-progress type="circle" :width='20'
              :percentage="row.percentage || 0" :stroke-width="3"
              :show-text="false" stroke-linecap='butt'
              class="vm mr-5" status='exception'>
            </el-progress>
            <span>{{ row.percent | PercentFilter(!!row.errCnt) }}</span>     
          </template>
        </el-table-column>

        <el-table-column key="actions" :label="$t('modules.views.aiPlatform.experts.s_2b6bc0f2')" :width="140">
          <template slot-scope="{ row }">
            <span
              @click.stop="addQueryHandle(row)"
              :class="{ 'action-disabled': queryName && row[currType] === queryName }"
              class="blue cp">{{ $t('modules.views.appMonitor.errors.s_ab1a54fb') }}</span>
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
import { copy } from '@/utils/common'
import { debounce } from '@/utils/common';
import ServiceApi from '@/api/service';
import { toAsyncWait } from '@/utils/common';

@Component({
  filters: {
    nameFilter (type: string) {
      switch (type) {
        case 'exceptionName':
          return i18n.t('modules.views.appMonitor.errorDetail.s_2fc597a3') as string;
        case 'serviceInstance':
          return i18n.t('modules.views.appMonitor.errors.s_8124816e') as string;
        case 'rootResource':
          return i18n.t('modules.views.alarmCenter.eventDetail.s_34cab80c') as string;
        default:
          return type || '--';
      }
    }
  }
})
export default class TableList extends Vue {
  @Prop({ default: () => ({}) }) private query!: any;
  @Prop({ default: () => ({}) }) private timeParams!: any;

  public $refs!: {
    tableWrap: HTMLDivElement,
    table: Table,
  }

  private currType: string = 'exceptionName';
  private allChartTypes = [
    { label: i18n.t('modules.views.appMonitor.errors.s_6784b167') as string, labelKey: 'modules.views.appMonitor.errors.s_6784b167', value: 'exceptionName' },
    { label: i18n.t('modules.views.appMonitor.errors.s_820b696e') as string, labelKey: 'modules.views.appMonitor.errors.s_820b696e', value: 'rootResource' },
    { label: i18n.t('modules.views.alarmCenter.alarm.s_71673bab') as string, labelKey: 'modules.utils.filters.s_71673bab', value: 'serviceInstance', type: 'service' },
  ]
  get chartTypes () {
    const serviceId = this.query.serviceId
    return this.allChartTypes.filter(item => item.type !== 'service' || serviceId)
  }
  @Watch('chartTypes', { immediate: true })
  private watchChartTypes () {
    if (!this.chartTypes.find(t => t.value === this.currType)) {
      this.currType = this.chartTypes[0].value
    }
  }

  private timer: any = null;
  private scrollContainer: any = null;
  private scrollHandle: any = null;
  private tableHeight: number = 360;

  private queryParams: any = {
    pageNum: 1,
    pageSize: 50,
    sortField: 'errCnt',
    sortOrder: 'desc',
  }

  private isLoading = false
  private listTotal = 0
  private tableList: any[] = []
  get noMore () {
    return this.tableList.length >= this.listTotal
  }

  get queryName () {
    const query = this.query
    if (this.currType === 'exceptionName') {
      return query.exception || ''
    } else if (this.currType === 'rootResource') {
      return query.rootResourceQuery || ''
    }
    return query[this.currType] || ''
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
    if (page === 1) {
      this.tableList = [];
    }
    this.queryParams.pageNum = page
    const { fromTime, toTime } = this.timeParams
    const { pageSize, sortOrder, sortField } = this.queryParams
    const params: any = {
      ...this.query,
      groupBy: this.currType,
      offset: (page - 1) * pageSize,
      size: pageSize,
      fromTime,
      toTime,
    }
    if (this.currType === 'serviceInstance') {
      params.groupBy = 'serviceId,serviceInstance'
    } else if (this.currType === 'rootResource') {
      params.notEmptyFields = 'rootResource' // 列表排除空数据
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
    const { result, error } = await toAsyncWait(ServiceApi.getErrorDistList(params));
    this.isLoading = false;
    if (!error) {
      if (page === 1 && this.scrollContainer) {
        // 滚动区域 scrollTop 置为 0
        this.scrollContainer.scrollTop = 0
      }
      const list = (result.data || []).map((t: any) => ({
        ...t,
        percent: (t.percentage || 0) / 100,
      }));
      const tableList = page === 1 ? list : Array.from(this.tableList).concat(list);
      this.formatThisData(tableList);
      this.listTotal = result.total || 0
      this.tableList = tableList;
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

  private viewDetailHandle (row: any) {
    const { exception, resourceQuery, rootResourceQuery, sid, sn, si, fromTime, toTime, durationRange } = this.$route.query
    const query: any = { exception, resourceQuery, rootResourceQuery, sid, sn, si }
    // 交给 Vue Router 编码，避免 encodeURIComponent 后再编码导致 %252F
    const _name = row[this.currType]
    if (this.currType === 'exceptionName') {
      query.exception = _name
    } else if (this.currType === 'rootResource') {
      query.rootResourceQuery = _name
    } else if (this.currType === 'serviceInstance') {
      query.si = _name
    }
    if (fromTime && toTime) {
      query.fromTime = fromTime
      query.toTime = toTime
    } else if (durationRange) {
      query.durationRange = durationRange
    }
    Object.entries(query).forEach(([key, value]) => {
      if (!value) {
        delete query[key]
      }
    })
    this.$router.push({
      path: '/appMonitor/errorDetail',
      query,
    });
  }

  // 添加到搜索
  private addQueryHandle (row: any) {
    if (this.queryName && row[this.currType] === this.queryName) {
      return
    }
    const query = { ...this.$route.query }
    // 交给 Vue Router 编码，避免 encodeURIComponent 后再编码导致 %252F
    const _name = row[this.currType]
    if (this.currType === 'exceptionName') {
      query.exception = _name
    } else if (this.currType === 'rootResource') {
      query.rootResourceQuery = _name
    } else if (this.currType === 'serviceInstance') {
      query.si = _name
    }
    this.$router.replace({ query });
    this.$emit('add-query');
  }

  // 复制
  private copyNameHandle (name: string) {
    copy(name)
  }

  // 计算数值占比
  private formatThisData (data: any) {
    if (Array.isArray(data)) {
      const cFields = ['percentage', 'errCnt']
      if (cFields.length) {
        const cFieldsMax: any = {};
        cFields.forEach((cf) => {
          const cfMax = Math.max(...data.filter(i => typeof i[cf] === 'number').map((i) => i[cf]));
          cFieldsMax[cf] = isNaN(cfMax) || !isFinite(cfMax) ? 0 : cfMax
        });
        data.forEach((item) => {
          if (!item.progressValue) {
            item.progressValue = {}
          }
          for (const cf in cFieldsMax) {
            if (typeof item.progressValue[cf] !== 'number' && typeof item[cf] === 'number') {
              const calcPct = (item[cf] / cFieldsMax[cf]) * 100;
              item.progressValue[cf] = calcPct < 1 && calcPct > 0 ? 1 : calcPct;
            }
          }
        });
      }
    }
  }
}
</script>

<style lang="scss" scoped>
.table-list {
  background: var(--bg-color);
  .list-head {
    display: flex;
    align-items: center;
    margin-bottom: 16px;
    font-size: 13px;
    line-height: 22px;
  }
  .list-body {
    height: calc(100% - 38px);
    .table {
      height: 100%;
      overflow: visible;
      .action-disabled {
        color: var(--color-text-secondary);
        cursor: not-allowed;
      }

      :deep(.el-table__cell:first-child .cell) {
        line-height: 1;
      }
      :deep(.el-table__cell:hover) {
        .copy-box {
          padding-right: 26px;
        }
        .copy-btn {
          display: block;
        }
      }

      .copy-box {
        display: inline-block;
        vertical-align: top;
        max-width: 100%;
        position: relative;
        text-overflow: ellipsis;
        white-space: nowrap;
        overflow: hidden;
        line-height: 22px;
        .copy-btn{
          display: none;
          padding: 4px 6px;
          cursor: pointer;
          font-size: 12px;
          line-height: 14px;
          position: absolute;
          top: 0;
          right: 0;
          &:hover {
            color: var(--color-text-link);
          }
        }
      }
    }
  }
}
</style>
