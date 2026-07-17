<template>
  <div class="service-manage-cont">
    <el-table
      ref="table"
      :data="tableList"
      :empty-text="$t('modules.components.charts.s_21efd88b')"
      highlight-current-row size="small"
      row-key='id'
      @row-click='rowClickHandle'
      tooltip-effect='light'
      :tree-props="{children: 'children'}"
    >
      <el-table-column label='Resource' prop='name' show-overflow-tooltip>
        <span slot-scope="{ row }" :class='[row.error === 1 ? "custom-tree-error-span" : ""]'>{{ row.nameKey ? $t(row.nameKey) : row.name }}</span>
      </el-table-column>
      <el-table-column :label="$t('modules.views.appMonitor.traceDetail.s_5b1d6e57')">
        <div slot-scope="{ row }">{{ row.spanLength | NumberFilter }}</div>
      </el-table-column>
      <el-table-column :label="$t('modules.views.appMonitor.serviceCall.s_0d652d40')">
        <div slot-scope="{ row }">{{ (row.duration / (row.spanLength || 1)) | NsFilter }}</div>
      </el-table-column>
      <el-table-column :label="$t('modules.views.appMonitor.traceDetail.s_70b3635a')">
        <template slot-scope="{ row }">
          <div v-if='row.children'>{{ row.exectime | NsFilter }}</div>
          <div v-else>-</div>
        </template>
      </el-table-column>
      <el-table-column :label="$t('modules.views.appMonitor.traceDetail.s_6ed40596')">
        <div slot-scope="{ row }">{{ row.exectimePct | PercentFilter(true) }}</div>
      </el-table-column>
    </el-table>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import deepClone from 'lodash/cloneDeep';
import { v4 as uuidv4 } from 'uuid';

@Component
export default class ServiceManageList extends Vue {
  @Prop({ default: () => [] }) private source!: any[];
  @Prop({ default: 0 }) private totalExectime!: any;

  @Watch('source', { immediate: true })
  private onSourceChange (newVal: any[]) {
    if (!newVal) {
      return
    }
    this.formatData(deepClone(newVal));
  }

  private tableList: any = [];

  private rowClickHandle (row: any) {
    if (!row.children && row.span_id) {
      this.$emit('on-change', row)
    }
  }

  private formatData (data: any[]) {
    const formatItem = (list: any[], totalDuration: number) => {
      const duration = list.reduce((prev: any, curr: any) => prev + curr.duration, 0);
      const exectime = list.reduce((prev: any, curr: any) => prev + (Number(curr.exectime) || 0), 0);
      // 执行占比 = 自身汇总 / 总耗时
      const exectimePct = totalDuration ? exectime / totalDuration : '-';
      return {
        id: uuidv4(),
        duration,
        exectime,
        exectimePct,
      }
    }

    const totalDuration = Number(this.totalExectime) || 0;
    const _data = data.map(item => {
      const duration = Number(item.duration) || 0
      const exectime = Number.isFinite(Number(item.exectime)) ? Number(item.exectime) : duration
      return {
        id: uuidv4(),
        name: item.resource,
        service: item.service,
        resource: item.resource,
        error: item.error,
        duration,
        exectime,
        // 执行占比 = 自身 / 总耗时
        exectimePct: totalDuration ? exectime / totalDuration : '-',
        span_id: item.span_id,
      }
    }).sort((a: any, b: any) => b.exectime - a.exectime);

    // 所有的服务名称
    const services: string[] = Array.from(new Set(_data.map(item => item.service)));

    // 服务列表
    const serviceList = services.map((service) => {
      // 同服务的所有span
      const list = _data.filter(item => item.service === service);

      // 所有的接口名称
      const resources = Array.from(new Set(list.map(item => item.resource)));

      // 请求列表
      const resourceList = resources.map((resource: string) => {
        // 同请求的所有span
        const spanList = list.filter(item => item.resource === resource);
        // 服务
        return {
          ...formatItem(spanList, totalDuration),
          name: resource,
          spanLength: spanList.length,
          children: spanList,
        };
      }).sort((a: any, b: any) => b.exectime - a.exectime);

      // 服务的Span数量
      const serviceSpanLength = resourceList.reduce((prev: any, curr: any) => prev + curr.spanLength, 0);
      // 服务
      return {
        ...formatItem(resourceList, totalDuration),
        name: service,
        spanLength: serviceSpanLength,
        children: resourceList,
      }
    }).sort((a: any, b: any) => b.exectime - a.exectime);

    this.tableList = serviceList
  }
}
</script>

<style lang="scss" scoped>
.service-manage-cont{
  padding-right: 20px;
  position: relative;
  
  .table-info-cont{
    justify-content: space-between;
    margin-bottom: 10px;

    .search-ipt{
      width: 320px;
    }
  }
}
.custom-tree-error-span {
  color: var(--color-danger);
}
</style>
