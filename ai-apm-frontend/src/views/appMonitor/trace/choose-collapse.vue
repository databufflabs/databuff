<template>
  <div :class='["choose-collapse flex-v", collapsed ? "is-collapsed" : ""]'
    v-loading="filterLoading">
    <div class="choose-collapse-title flex-h-jc">
      <span class="font-13">{{ $t('modules.views.alarmCenter.alarm.s_c0fd0276') }}</span>
      <div>
        <span v-show='!collapsed' @click="toggleCollapsed(true)" class="db-icon db-icon-fold cp font-12"></span>
      </div>
    </div>

    <div class="choose-collapse-body">
      <simplebar style="height: 100%;padding-right: 12px;">
        <el-collapse v-model="activeNames" class="filter-collapse">
          <el-collapse-item
            v-for="item in filterList"
            :key="item.name"
            :title="item.title"
            :name="item.name"
          >
            <div slot="title" class="flex-h-jc">
              <span>{{ item.titleKey ? $t(item.titleKey) : item.title }}</span>
              <span v-show="item.model.length && !disabledNames.includes(item.name)" class="filter-btn-wrapper flex-h">
                <span @click.stop="() => {}" class="filter-btn flex-h">
                  <span class="db-icon-filter icon-filter"></span>
                </span>
                <span @click.stop="clearLimitHandle(item.name)" class="filter-btn flex-h">
                  <span class="db-icon-close icon-close"></span>
                </span>
              </span>
            </div>
            <simplebar v-if="item.children.length" style="max-height: 200px;">
              <el-checkbox-group v-model="item.model" @change="changeHandle">
                <template v-for="t in item.children">
                  <el-checkbox
                    :key="t.value"
                    :label="t.value"
                    :disabled="t.disabled"
                    :class="t.status"
                    class="filter-checkbox"
                  >
                    <template v-if="item.name !== 'error'">{{ t.labelKey ? $t(t.labelKey) : t.label }}</template>
                    <template v-else>{{ t.label | SpanStatusFilter }}</template>
                  </el-checkbox>
                </template>
              </el-checkbox-group>
            </simplebar>
            <div v-else class="describe" style="margin-left:30px">{{ $t('modules.views.alarmCenter.alarm.s_d81bb206') }}</div>
          </el-collapse-item>
        </el-collapse>
      </simplebar>

      <div v-if="!filterLoading && !filterList.length" class="empty">{{ $t('modules.components.charts.s_21efd88b') }}</div>
    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import i18n from '@/i18n';
import Simplebar from 'simplebar-vue'
import { toAsyncWait } from '@/utils/common';
import ApmApi from '@/api/apm'

interface FilterType {
  label: string;      // 类型中文名
  value: string;      // 查询时用的参数名
  field: string;      // 获取的筛选数据对应的字段
}

@Component({
  components: {
    Simplebar: Simplebar as any,
  },
})
export default class ChooseCollapse extends Vue {
  @Prop({}) private collapsed!: any;
  @Prop({ default: () => ({}) }) private query!: any;
  @Prop({ default: () => ({}) }) private timeParams!: any;
  @Prop({ default: () => [] }) private defaultActiveNames!: any; // 默认展开项
  @Prop({ default: () => [] }) private disabledNames!: any; // 禁用项
  @Prop({ default: '' }) private componentType!: string;


  private activeNames: string[] = ['serviceIds', 'errorTypes'];

  private filterTypes: FilterType[] = [
    { label: i18n.t('modules.views.alarmCenter.eventDetail.s_3fea7ca7') as string, labelKey: 'modules.views.aiPlatform.experts.s_3fea7ca7', value: 'error', field: 'status' },
    { label: i18n.t('modules.views.alarmCenter.eventDetail.s_34cab80c') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_34cab80c', value: 'resources', field: 'resource' },
    { label: i18n.t('modules.views.alarmCenter.alarm.s_47d68cd0') as string, labelKey: 'modules.views.alarmCenter.alarm.s_47d68cd0', value: 'serviceIds', field: 'service' },
    { label: i18n.t('modules.views.alarmCenter.alarm.s_71673bab') as string, labelKey: 'modules.utils.filters.s_71673bab', value: 'serviceInstances', field: 'serviceInstance' },
    { label: i18n.t('modules.views.alarmCenter.eventDetail.s_e739425d') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_e739425d', value: 'srcServiceIds', field: 'srcService' },
    { label: i18n.t('modules.views.alarmCenter.eventDetail.s_6fcfca3f') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_6fcfca3f', value: 'srcServiceInstances', field: 'srcServiceInstance' },
    { label: i18n.t('modules.views.alarmCenter.eventDetail.s_ea340b9d') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_ea340b9d', value: 'methods', field: 'httpMethod' },
    { label: i18n.t('modules.views.alarmCenter.eventDetail.s_771d897d') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_771d897d', value: 'httpCodes', field: 'httpStatusCode' },
    { label: i18n.t('modules.views.alarmCenter.eventDetail.s_226b0912') as string, labelKey: 'modules.views.aiPlatform.experts.s_226b0912', value: 'types', field: 'type' },
    { label: 'URL', value: 'urls', field: 'url' },
    { label: i18n.t('modules.views.alarmCenter.eventDetail.s_f700c855') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_f700c855', value: 'errorTypes', field: 'errorType' },
    { label: i18n.t('modules.views.alarmCenter.alarm.s_65227369') as string, labelKey: 'modules.views.alarmCenter.alarm.s_65227369', value: 'hosts', field: 'hostName' },
    { label: 'Topic', value: 'topics', field: 'topic' },
    { label: 'ConsumerGroup', value: 'groups', field: 'group' },
    { label: 'Partition', value: 'partitions', field: 'partition' },
    { label: 'Broker', value: 'brokers', field: 'broker' },
    { label: i18n.t('modules.views.alarmCenter.eventDetail.s_5ccbbd01') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_5ccbbd01', value: 'sqlDatabases', field: 'sqlDatabase' },
    { label: i18n.t('modules.views.alarmCenter.eventDetail.s_c26c0d60') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_c26c0d60', value: 'dbTypes', field: 'dbType' },
    { label: i18n.t('modules.views.alarmCenter.eventDetail.s_de9cc3dd') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_de9cc3dd', value: 'sqlOperations', field: 'sqlOperation' },
  ];

  private filterList: any[] = []
  private filterLoading = true
  private filterLoadedMapping: any = {} // 筛选项的已加载状态

  @Watch('activeNames')
  private async onActiveNamesChange (newVal: string[], oldVal: string[]) {
    if (!this.filterLoading && newVal.length > oldVal.length && newVal.find(t => !this.filterLoadedMapping[t])) {
      await this.getParams(this.activeNames)
    }
  }

  public async init () {
    this.filterList = []
    this.filterLoadedMapping = {}
    this.getActiveNames()
    this.getParams(this.activeNames, true)

    const _params: any = {}
    const { multisearch } = this.$route.query
    if (multisearch) {
      try {
        const _multisearch = decodeURIComponent(multisearch as string);
        _multisearch.split(';').forEach((item) => {
          const [key, values] = item.split('=');
          if (key === 'error') {
            const _values = values.split(',').filter(_t => _t)
            if (_values.length === 1) {
              _params.error = +_values[0]
            }
          }
        });
      } catch (err) {
        console.log(err);
      }
    }
    return { ..._params }
  }

  private async getParams (names: string[], init: boolean = false) {
    const { fromTime, toTime } = this.timeParams
    const params: any = {
      fromTime,
      toTime,
      ...this.query,
      componentType: this.componentType,
    }
    if (names.length) {
      params.queryParams = names.map(t => {
        const filterItem: any = this.filterTypes.find((item) => item.value === t) || {}
        return filterItem.field
      }).filter(t => !!t)
    }
    this.filterLoading = true
    const { result, error } = await toAsyncWait(ApmApi.getSpanParams(params))
    this.filterLoading = false
    if (!error) {
      const data = result.data || {}
      // 数据中不含状态数据，默认添加状态项数据
      // data.status = { 0: 1, 1: 1, 2: 1 }
      data.status = { 0: 1, 1: 1 }
      const filterList: any[] = [];
      this.filterTypes.filter(t => data.hasOwnProperty(t.field)).forEach((t) => {
        const keys = Object.keys(data[t.field] || {})
        const isService = t.field === 'service' || t.field === 'srcService'
        const disabled = this.disabledNames.includes(t.value)
        const values = keys.map(v => !isService ? v : data[t.field][v])
        // 已选项回填
        const filterItem: any = this.filterList.find((item) => item.name === t.value) || {}
        let model: any[] = filterItem.model || []
        let children: any[] = filterItem.children || []
        if (keys.length) {
          model = model.filter((n: any) => values.includes(n))
          children = keys.map((v: any, i: number) => ({
            label: v,
            value: values[i],
            disabled,
          }))
        }
        filterList.push({
          name: t.value,
          title: t.label,
          field: t.field || t.value,
          model,
          children,
        })
      });
      this.filterList = filterList

      // 存储筛选项的已加载状态
      let loadedNames = [...names]
      if (!loadedNames.length) {
        loadedNames = this.filterTypes.map(t => t.value)
      }
      loadedNames.forEach(t => {
        this.filterLoadedMapping[t] = true
      })
    }
    if (init) {
      this.setParams()
    }
  }

  private async setParams () {
    // ⬇⬇⬇根据路由参数还原筛选条件⬇⬇⬇
    const { multisearch } = this.$route.query
    if (multisearch) {
      try {
        const _multisearch = decodeURIComponent(multisearch as string);
        _multisearch.split(';').forEach((item) => {
          const [key, values] = item.split('=');
          const filterItem = this.filterList.find(t => t.name === key)
          if (filterItem) {
            const keys = filterItem.children.map((_t: any) => _t.value)
            if (key === 'serviceIds' || key === 'srcServiceIds') {
              const [_key, ...remain] = item.split('=');
              const _values = remain.join('=');
              filterItem.model = _values.split(',').filter(_t => _t && keys.indexOf(_t) > -1)
            } else {
              filterItem.model = values.split(',').filter(_t => _t && keys.indexOf(_t) > -1)
            }
          }
        });
      } catch (err) {
        console.log(err);
      }
    }
    // ⬆⬆⬆根据路由参数还原筛选条件⬆⬆⬆

    // 旧版参数兼容
    const decodeValue = (val: any) => decodeURIComponent(String(val))
    const { sid = '', si = '' } = this.$route.query;
    let _sids: string[] = []
    let _sis: string[] = []
    if (sid) {
      _sids = [decodeValue(sid)]
      if (si) {
        _sis = [decodeValue(si)]
      }
    }
    const setFilterItem = (name: string, values: string[]) => {
      if (values.length) {
        const filterItem = this.filterList.find(t => t.name === name)
        if (filterItem) {
          filterItem.model = Array.from(new Set([...filterItem.model, ...values]))
        }
      }
    }
    setFilterItem('serviceIds', _sids)
    setFilterItem('serviceInstances', _sis)
  }

  // 获取默认展开项
  private getActiveNames () {
    let activeNames: string[] = ['serviceIds', 'errorTypes'];
    const { multisearch, si = '', sis = '' } = this.$route.query;
    if (this.defaultActiveNames.length) {
      activeNames = [...this.defaultActiveNames]
    } else {
      const _multisearch = decodeURIComponent(multisearch as string);
      _multisearch.split(';').forEach((item) => {
        const [key, values] = item.split('=');
        if (key && !activeNames.find(t => t === key)) {
          activeNames.push(key)
        }
      });
      if ((si || sis) && !activeNames.find(t => t === 'serviceInstances')) {
        activeNames.push('serviceInstances')
      }
    }
    this.activeNames = activeNames
  }

  private changeHandle () {
    // 设置路由中的参数
    // 格式： multisearch=encodeURIComponent(aaa=xx,xxx,xxx;bbb=xxx;ccc=xxx,xxx)
    const _params: any = {}
    this.filterList.filter(t => t.model.length).forEach(t => {
      const { name, model } = t
      if (name !== 'error') {
        _params[name] = model
      } else if (model.length === 1) {
        _params.error = +model[0]
      }
    })
    // const _multisearch = Object.entries(_params).map(([key, values]: any) => {
    //   if (Array.isArray(values)) {
    //     return `${key}=${values.join(',')}`
    //   } else {
    //     return `${key}=${values}`
    //   }
    // }).join(';')
    // const __multisearch = decodeURIComponent(this.$route.query.multisearch as string || '')
    // if (_multisearch !== __multisearch) {
    //   this.$router.replace({
    //     query: {
    //       ...this.$route.query,
    //       multisearch: encodeURIComponent(_multisearch)
    //     }
    //   })
    // }
    this.$emit('on-filter-change', { ..._params })
  }

  private clearLimitHandle (field: string) {
    const target = this.filterList.find(item => item.name === field)
    if (target) {
      target.model = [];
    }
    this.changeHandle()
  }

  private toggleCollapsed (status: boolean) {
    this.$emit('on-toggle-filter', status)
  }
}
</script>

<style lang="scss" scoped>
.choose-collapse {
  width: 188px;
  height: 100%;
  padding-right: 8px;
  background-color: var(--bg-color);
  // border-bottom: 1px solid var(--border-color-base);
  opacity: 1;
  transition: transform .3s ease,
    opacity .3s ease;
  &.is-collapsed {
    transform: translateX(-200px);
    opacity: 0;
  }

  .choose-collapse-title {
    border-top: 1px solid var(--border-color-base);
    line-height: 28px;
    height: 36px;
    border-bottom: 1px solid var(--border-color-base);
    margin-right: 12px;
  }

  .choose-collapse-body {
    flex: 1;
    overflow: hidden;
    position: relative;

    .empty {
      padding-right: 12px;
      display: flex;
      align-items: center;
      justify-content: center;
      width: 100%;
      height: 100%;
      position: absolute;
      top: 0;
      left: 0;
    }
  }

  .filter-collapse {
    border: none;

    :deep(.el-collapse-item) {
      padding: 2px 0;
      &:not(:last-child) {
        border-bottom: 1px solid var(--border-color-base);
      }
    }
    :deep(.el-collapse-item__header) {
      display: block;
      padding-left: 20px;
      padding-right: 8px;
      height: 28px;
      line-height: 28px;
      background-color: transparent;
      border: none;
      border-radius: 3px;
      text-overflow: ellipsis;
      white-space: nowrap;
      overflow: hidden;
      position: relative;
      font-size: 12px;
      font-weight: normal;
      .el-collapse-item__arrow {
        position: absolute;
        top: 7px;
        left: 0;
      }
      &:hover {
        background-color: var(--bg-color03);
      }
    }
    :deep(.el-collapse-item__wrap) {
      background-color: transparent;
      border: none;
      .el-collapse-item__content {
        padding-bottom: 0;
      }
    }
    :deep(.el-checkbox-group) {
      width: 100%;
    }

    .filter-input {
      :deep(.el-input__inner) {
        border: none;
      }
    }

    .filter-checkbox {
      margin: 2px 0;
      padding: 3px 0;
      min-height: 24px;
      border-radius: 3px;
      display: flex;
      align-items: center;
      line-height: 20px;
      color: var(--color-text-regular);
      font-weight: normal;
      &:hover {
        background-color: var(--bg-color03);
      }
      :deep(.el-checkbox__inner) {
        display: block;
      }
      :deep(.el-checkbox__label) {
        font-size: 12px;
      }
      :deep(.el-checkbox__input.is-checked+.el-checkbox__label) {
        color: inherit;
      }

      &.alert,
      &.warn,
      &.unknown,
      &.nodata,
      &.ok {
        :deep(.el-checkbox__label::before) {
          content: '';
          margin-right: 5px;
          display: inline-block;
          width: 8px;
          height: 8px;
          background-color: var(--color-success);
          border-radius: 4px;
        }
      }
      &.alert :deep(.el-checkbox__label::before) {
        background: var(--color-danger);
      }
      &.warn :deep(.el-checkbox__label::before) {
        background: var(--color-warning);
      }
      &.unknown :deep(.el-checkbox__label::before),
      &.nodata :deep(.el-checkbox__label::before) {
        background: var(--color-info);
      }
    }
  }

  .filter-btn-wrapper{
    // border: ;
    .filter-btn{
      border: 1px solid var(--border-color-light);
      padding: 2px;
      .icon-filter,
      .icon-close {
        font-size: 12px;
        transform: scale(0.9);
      }
    }
    .filter-btn:first-of-type{
      border-right: none;
      border-top-left-radius: 4px;
      border-bottom-left-radius: 4px;
      cursor: default;
    }
    .filter-btn:last-of-type{
      border-left: none;
      border-top-right-radius: 4px;
      border-bottom-right-radius: 4px;
      background-color: var(--border-color-light);
      transition: background-color .3s ease, border-color .3s ease;

      &:hover{
        background-color: var(--border-color-base);
        border-color: var(--border-color-base);
      }
    }
  }
}
</style>
