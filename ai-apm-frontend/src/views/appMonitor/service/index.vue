<template>
  <div class="service-cont">
    <div v-loading="queryLoading" class="service-wrapper flex-v">
      <div class="list-header flex-h">
        <query-filter
          v-model='queryParams'
          :updateRoute='true'
          :filter-list="filterList"
          @on-change="getData"
          @on-remove-tag="getData" />
      </div>

      <chart-group
        ref="chartGroup"
        :queryParams="getQueryParams"
        :timeParams="timeParams" />

      <div class="service-list-wrapper">
        <service-list
          ref="serviceListComp"
          :queryParams="getTableParams"
          class="service-list" />
      </div>
    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Watch } from 'vue-property-decorator';
import i18n from '@/i18n';
import QueryFilter from '@/components/query-filter/index.vue';
import ChartGroup from './chart-group.vue';
import ServiceList from './serviceList.vue';
import { toAsyncWait } from '@/utils/common';
import ServiceApi from '@/api/service'

@Component({
  components: {
    QueryFilter,
    ChartGroup,
    ServiceList,
  },
})
export default class ServiceManage extends Vue {
  public $refs!: {
    chartGroup: ChartGroup;
    serviceListComp: ServiceList;
  };

  @Watch('globalTimeV2', { deep: true })
  private watchGlobalTime() {
    this.durationChangeHandle()
  }

  private queryParams: any = {
    serviceName: '',
  }
  private timeParams: any = {}

  private queryLoading = false;

  get getQueryParams () {
    return {
      ...this.queryParams,
      serviceTypes: ['web'],
    }
  }
  get getTableParams () {
    const params: any = {
      ...this.getQueryParams,
    }
    Object.keys(params).forEach(k => {
      if (params[k] === '' || Array.isArray(params[k]) && !params[k].length) {
        delete params[k]
      }
    })
    return params;
  }

  private filterList: any[] = []

  private serviceList: any[] = []

  get filterListInit () {
    const list = [
      {
        field: 'serviceName',
        label: i18n.t('modules.views.alarmCenter.alarm.s_8f3747c0') as string, labelKey: 'modules.views.alarmCenter.alarm.s_8f3747c0',
        type: 'select',
        likeable: true,
        children: [...this.serviceList],
      },
    ]
    return list
  }

  private mounted () {
    this.$eventBus.$on('GlobalRefresh', this, () => {
      this.durationChangeHandle()
    });

    this.durationChangeHandle()
  }

  private beforeDestroy () {
    this.$eventBus.$off('GlobalRefresh');
  }

  private async init () {
    this.queryLoading = true;
    if (!this.serviceList.length) {
      await this.getBaseServiceList()
    }

    const routerQuery = this.$route.query
    const query: any = { ...this.queryParams }
    Object.keys(query).forEach(k => {
      if (routerQuery[k]) {
        query[k] = decodeURIComponent(String(routerQuery[k]))
      }
    });
    this.queryParams = query;
    this.filterList = this.filterListInit;
    this.queryLoading = false;
    return { ...this.queryParams }
  }

  private async getBaseServiceList () {
    const { fromTime, toTime } = this.getGlobalTimeV2()
    const { result, error } = await toAsyncWait(ServiceApi.getServicesIds({
      fromTime,
      toTime,
      ignoreTime: 0,
      serviceTypes: ['web']
    }))
    if (!error) {
      const { data = [] } = result || {};
      this.serviceList = (data || []).map((t: any) => ({
        label: t.name,
        value: t.name,
      }))
    }
  }

  private async durationChangeHandle () {
    this.init().then(() => {
      this.getData();
    })
  }

  private getData () {
    this.timeParams = { ...this.getGlobalTimeV2() }
    this.$nextTick(() => {
      this.$refs.chartGroup?.getData()
      this.$refs.serviceListComp?.refresh();
    })
  }

}
</script>

<style lang="scss" scoped>
.service-cont {
  flex: 1;
  height: 100%;
  padding: 16px;
  background-color: var(--bg-color-base);
  position: relative;
  overflow: hidden;

  .service-wrapper {
    position: relative;
    width: 100%;
    height: 100%;
    padding: 20px;
    background-color: var(--bg-color);
    overflow: hidden;
    min-height: 0;
  }

  .list-header {
    flex-shrink: 0;
    .search-group {
      flex: 1;
      flex-wrap: nowrap;
    }
  }

  :deep(.chart-group-wrap) {
    flex-shrink: 0;
  }

  .service-list-wrapper {
    margin: 0 -20px;
    padding: 12px 20px 0;
    flex: 1;
    min-height: 0;
    overflow: hidden;
    display: flex;
    .service-list {
      flex: 1;
      min-height: 0;
      overflow: hidden;
    }
  }
}
</style>
