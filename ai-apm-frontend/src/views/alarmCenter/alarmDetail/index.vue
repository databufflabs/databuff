<template>
  <div class="detail-wrapper">
    <div class="detail" :class="`detail-${activeName}`" v-loading="isLoading">
      <div class="detail-header">
        <div class="detail-title mb-20">
          <span
            :class="{
              'bg-red': detail.level === 3,
              'bg-yellow': detail.level === 2,
              'bg-grey': detail.level !== 3 && detail.level !== 2,
            }"
            class="alarm-status flex-none">{{ detail.level | AlarmStatusFilter }}</span>
          <text-expand
            @on-toggle="titleToggleHandle"
            :content="detail.description || '-'"
            :lineHeight="20"
            :maxLines="1"
            class="title-text" />
          <div class="detail-btns flex-none">
            <el-button
              v-if="detail.problemId"
              @click="viewProblemDetail(detail)"
              type="primary" plain size="small"
              class="detail-btn">{{ $t('modules.views.alarmCenter.alarmDetail.s_e8f54f03') }}</el-button>
          </div>
        </div>
        <div class="detail-info">
          <div class="info-row">
            <div class="info-t flex-h"><span class="label">{{ $t('modules.views.alarmCenter.alarm.s_10b22107') }}</span>{{ alarmId || '-' }}</div>
            <div class="info-t flex-h"><span class="label">{{ $t('modules.views.alarmCenter.alarmDetail.s_938f3274') }}</span>{{ highestLevelText }}</div>
          </div>
          <div class="info-row">
            <div class="info-t flex-h"><span class="label">{{ $t('modules.views.alarmCenter.alarmDetail.s_4d51ee56') }}</span>{{ detail.startTriggerTime | TimesToDateFilter('YYYY-MM-DD HH:mm') }}</div>
            <div class="info-t flex-h"><span class="label">{{ $t('modules.views.alarmCenter.alarmDetail.s_1b61e916') }}</span>{{ detail.timestamp | TimesToDateFilter('YYYY-MM-DD HH:mm') }}</div>
            <div class="info-t flex-h"><span class="label">{{ $t('modules.components.charts.s_f782779e') }}</span>{{ detail.endTriggerTime | TimesToDateFilter('YYYY-MM-DD HH:mm') }}</div>
            <div class="info-t flex-h"><span class="label">{{ $t('modules.views.configManage.alarm.s_4a6341a8') }}</span>{{ detail.duration | DurationFilter(true) }}</div>
          </div>
          <a
            @click="showOtherInfo = !showOtherInfo"
            class="dib blue cp font-13 lh-14 mb-12">{{ $t('modules.views.alarmCenter.alarmDetail.s_febe40ce') }}<i :class="{ active: showOtherInfo }" class="db-icon-down font-12 ml-5 detail-info-arrow"></i>
          </a>
          <div v-if="showOtherInfo" class="info-row">
            <div class="info-t flex-h"><span class="label">{{ $t('modules.views.alarmCenter.alarmDetail.s_1a42bdb8') }}</span>{{ detail.triggerObject || detail.serviceName || '-' }}</div>
            <div class="info-t flex-h"><span class="label">{{ $t('modules.views.alarmCenter.alarmDetail.s_87ae1624') }}</span>{{ abnormalMetricsText }}</div>
            <div v-if="getEnableStatus" class="info-t flex-h"><span class="label">{{ $t('modules.views.alarmCenter.alarm.s_f9d4e244') }}</span>
              <span>{{ detail.domainName || '-' }}</span>
            </div>
          </div>
          <div v-if="showOtherInfo" class="info-t tag-info-t flex-h mb-12"><span class="label">{{ $t('modules.views.infrastructure.hostDetail.s_14d34236') }}</span>
            <div class="tag-list">
              <collapse-tags v-if="detail.formatTags && detail.formatTags.length"
                :tags="detail.formatTags || []"
                :minWidth='300'
                :maxLine="2" />
              <span v-else>-</span>
            </div>
          </div>
        </div>
      </div>

      <div class="detail-content">
        <db-tabnav
          v-model="activeName"
          :tabnavs="tabs.filter(item => !item.hide)"
          @on-change="toggleTabHandle"
          :thin="true"
          class="detail-tabs">
          <span slot-scope="{ tab }">{{ tab.labelKey ? $t(tab.labelKey) : tab.label }}<i v-if="tab.icon" :class="tab.icon" class="tab-icon"></i></span>
        </db-tabnav>

        <component
          :ref="activeName"
          :is="activeName"
          :queryParams="getQueryParams"
          :detail="detail"
          class="detail-tabs-pane-wrap" />
      </div>
    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Watch } from 'vue-property-decorator';
import i18n from '@/i18n';
import { namespace } from 'vuex-class';
import { StringIsEmpty, toAsyncWait } from '@/utils/common';
import AlarmApi from '@/api/alarm';
import { AlarmStatusFilter } from '@/utils/filters/alarm';
import TextExpand from '@/components/text-expand/index.vue';
import CollapseTags from '@/components/collapse-tags/index.vue';
import TabEvent from './tab-event.vue';
import TabResponse from './tab-response.vue';

// 过滤告警/事件的标签key
const filterTagKeys = (keys: string[]) => {
  // 忽略列表
  const ignoreList = ['apiKey', 'level', 'ruleName', 'message', 'group', 'classification', 'serviceCode', 'source'];
  // 过滤掉忽略列表的key
  const _keys = keys.filter(str => !ignoreList.includes(str));
  return _keys.sort();
}

const UserModel = namespace('User');

@Component({
  components: {
    TextExpand,
    CollapseTags,
    TabEvent,
    TabResponse,
  }
})
export default class ProblemDetail extends Vue {
  @UserModel.Getter('getGroupMapping') private groupMapping!: any;

  public $refs!: {
    tabEvent: TabEvent
    tabResponse: TabResponse
  }

  @Watch('globalTimeV2', { deep: true })
  private watchGlobalTime() {
    this.durationChangeHandle()
  }

  get getEnableStatus () {
    return this.$store.getters['User/getGroupEnabled']
  }

  get tagLabelMapping () {
    const mapping: any = {}
    const tagLabelMap = this.$store.getters['Common/tagLabelMap']
    Object.entries(tagLabelMap || {}).forEach(([key, item]: any) => {
      mapping[key] = item.name
    })
    return mapping
  }

  get abnormalMetricsText () {
    const metrics = this.detail?.abnormalMetrics || []
    return metrics.length ? metrics.join(', ') : '-'
  }

  get highestLevelText () {
    const status = AlarmStatusFilter(this.detail?.level)
    if (status === '-') {
      return '-'
    }
    return i18n.t('modules.views.alarmCenter.alarmDetail.s_50a153a1', { value0: status }) as string
  }

  private detail: any = {}
  private isLoading = false

  private alarmId: string = ''

  private timeParams: any = {}

  private showOtherInfo = false

  get getQueryParams () {
    return {
      fromTime: this.timeParams.fromTime,
      toTime: this.timeParams.toTime,
      interval: this.timeParams.interval,
      alarmId: this.alarmId || '',
    };
  }

  private tabs = [
    { label: i18n.t('modules.views.alarmCenter.alarmDetail.s_38c1fc12') as string, labelKey: 'modules.views.alarmCenter.alarmDetail.s_38c1fc12', value: 'tabEvent' },
    { label: i18n.t('modules.views.alarmCenter.alarmDetail.s_38dfa9e2') as string, labelKey: 'modules.views.alarmCenter.alarmDetail.s_38dfa9e2', value: 'tabResponse' },
  ]
  private activeName: string = 'tabEvent'

  private created () {
    const { aid = '' } = this.$route.query;
    this.alarmId = decodeURIComponent(aid as string);

    const type: any = this.$route.query.type
    if (this.tabs.find(t => t.value === type)) {
      this.activeName = type
    }
  }

  private mounted () {
    // 监听全局的手动刷新事件，created中会有不触发的情况
    // 子组件绑定会被覆盖
    this.$eventBus.$on('GlobalRefresh', this, () => {
      this.durationChangeHandle()
    });
    this.durationChangeHandle();
  }

  private beforeDestroy () {
    // 清除监听全局刷新事件
    this.$eventBus.$off('GlobalRefresh')
  }

  // 时间范围改变
  private async durationChangeHandle () {
    this.timeParams = { ...this.getGlobalTimeV2() }
    if (!this.alarmId) {
      return;
    }
    await this.$store.dispatch('Common/GET_TAG_LABEL_MAP');
    await this.getDetail()
    this.$nextTick(async () => {
      const $refComp = (this.$refs as any)[this.activeName]
      if ($refComp && $refComp.getData) {
        $refComp.getData();
      }
    })
  }

  private toggleTabHandle(tab: any) {
    const { value } = tab;
    this.activeName = value
    const type = this.$route.query.type
    if (value !== type) {
      const query: any = { ...this.$route.query, type: value }
      delete query.eType
      this.$router.replace({ query })
    }
    if (!this.alarmId) {
      return;
    }
    this.$nextTick(async () => {
      const $refComp = (this.$refs as any)[this.activeName]
      if ($refComp && $refComp.getData) {
        $refComp.getData();
      }
    })
  }

  // 展开/收起标题
  private titleToggleHandle () {
    this.$nextTick(async () => {
      const $refComp = (this.$refs as any)[this.activeName]
      if ($refComp && $refComp.resize) {
        $refComp.resize();
      }
    })
  }

  // 获取详情
  private async getDetail () {
    this.isLoading = true;
    const { result, error } = await toAsyncWait(AlarmApi.getAlarmDetail({ id: this.alarmId }));
    this.isLoading = false;
    const data = (result || {}).data
    if (!error && data?.id) {
      data.tags = data.tags || {}
      // tags 格式化为 {key: [value1, value2]}
      Object.keys(data.tags).forEach(key => {
        if (!Array.isArray(data.tags[key])) {
          data.tags[key] = !StringIsEmpty(data.tags[key]) ? [data.tags[key]] : []
        } else {
          data.tags[key] = data.tags[key].filter((val: string) => !StringIsEmpty(val))
        }
      })
      const formatTags: string[] = []
      filterTagKeys(Object.keys(data.tags)).forEach(key => {
        const _key = this.tagLabelMapping[key] || key
        const _values: any[] = data.tags[key]
        formatTags.push(..._values.map(val => `${_key}: ${val}`))
      })
      if (formatTags.length) {
        data.formatTags = formatTags;
      }

      data.remark = Array.isArray(data.remark) ? data.remark : [];

      data.domainName = this.groupMapping[data.gid] || '';

      this.detail = data
    } else {
      this.detail = {}
      this.$confirm(i18n.t('modules.views.alarmCenter.alarmDetail.s_3c367287') as string, i18n.t('common.hint') as string, {
        confirmButtonText: i18n.t('modules.views.alarmCenter.alarmDetail.s_38cf16f2') as string, confirmButtonTextKey: 'modules.views.alarmCenter.alarmDetail.s_38cf16f2',
        closeOnClickModal: false,
        showCancelButton: false,
        showClose: false,
        type: 'warning'
      }).then(() => {
        this.$router.replace({
          path: '/alarmCenter/alarm',
          query: { ...this.getRouteTimeOrRange }
        })
      })
    }
  }

  // 跳转到问题详情
  private viewProblemDetail (row: any) {
    const routeData = this.$router.resolve({
      path: '/alarmCenter/problemDetail',
      query: { id: row.problemId, __nw: 't' },
    });
    window.open(routeData.href, '_blank');
  }
}
</script>

<style lang="scss" scoped>
.detail-wrapper {
  flex: 1;
  padding: 16px;
  height: 100%;
  font-size: 13px;
  color: var(--color-text-primary);
  overflow: auto;

  .detail {
    display: flex;
    flex-direction: column;
    height: 100%;
    min-height: 600px;
    &.detail-tabEvent {
      min-height: 800px;
    }
  }

  .detail-header {
    flex: none;
    padding: 20px;
    margin-bottom: 16px;
    background-color: var(--bg-color);
    border-radius: 4px;
  }

  .detail-content {
    flex: 1;
    display: flex;
    flex-direction: column;
    background-color: var(--bg-color);
    border-radius: 4px;
    overflow: hidden;
    min-height: 500px;
  }
}

.detail-header {
  .detail-title {
    display: flex;
    .title-text {
      font-size: 16px;
      line-height: 20px;
      font-weight: 500;
    }
    .detail-btns {
      margin: -6px 0 -6px 40px;
    }
    .detail-btn {
      height: 32px;
    }
    .alarm-status {
      display: inline-block;
      vertical-align: top;
      margin-right: 10px;
      padding: 0 6px;
      height: 20px;
      font-size: 12px;
      line-height: 20px;
      border-radius: 2px;
      color: #fff;
    }
  }

  .detail-info {
    padding: 20px 20px 8px;
    border: 1px solid var(--border-color-lighter);
    border-radius: 4px;
    line-height: 14px;
    font-size: 13px;
    .info-row {
      margin-bottom: 12px;
      display: flex;
      flex-wrap: wrap;
    }
    .info-t {
      margin-right: 40px;
    }
    .label {
      flex: none;
      margin-right: 16px;
      min-width: 52px;
      color: var(--color-text-secondary);
    }

    .detail-info-arrow {
      display: inline-block;
      transition: transform .3s;
      &.active {
        transform: rotate(180deg);
      }
    }

    .tag-info-t {
      align-items: flex-start;
      .label {
        line-height: 22px;
      }
      .tag-list {
        flex: 1;
      }
    }

  }
}

.detail-content {
  .detail-tabs {
    flex: none;
    padding: 17px 20px 0;
    border-bottom: 1px solid var(--border-color-lighter);
    .tab-icon {
      margin-left: 5px;
      -webkit-text-stroke: initial;
    }
  }

  .detail-tabs-pane-wrap {
    flex: 1;
    padding: 20px;
    overflow: hidden;
  }
}
</style>
