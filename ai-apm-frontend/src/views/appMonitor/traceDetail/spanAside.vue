<template>
  <!-- span详情 -->
  <div class="trace-span-detail flex-v ovh">
    <div v-if='currentSpan' class="span-detail-header mb-20 pr-16">
      <div class="span-header-main flex-h-jc ovh">
        <div class="flex-1 ovh ell">
          <span class="node-type-icon" :data-type='currentSpan.service_type'>
            <i :class='["db-icon font-16 mr-8", "db-icon-" + (currentSpan.type || currentSpan.service_type)]'></i>
          </span>
          <span class="font-14 fw-500">{{ currentSpan.resource }}</span>
        </div>
      </div>
      <div class="span-header-info">
        <span>{{ $t('modules.views.appMonitor.traceDetail.s_d8dc9309') }}<span>{{ currentSpan.duration | NsFilter }}</span></span>
        <span>{{ $t('modules.views.appMonitor.traceDetail.s_0f6f5c0f') }} <span>{{ durationPct | PercentFilter }}</span></span>
        <span class="ml-8">{{ $t('modules.views.alarmCenter.alarm.s_f782779e') }}：{{ spanEndMillis | TimesToDateFilter('YYYY-MM-DD HH:mm:ss.SSS') }}</span>
      </div>
      <div class="tl mt-5">
        <span
          v-if="currentSpan.hotspot && isDatabuffSource"
          @click.stop="viewHotMethodHandle(currentSpan.span_id)"
          class="db-blue cp font-12 fw-normal ml-8 flex-0">{{ $t('modules.views.appMonitor.serviceAnalysis.s_a3d9cb1f') }}</span>
      </div>
    </div>

    <db-tabnav v-if='detailOptions.length > 1' v-model='detailModel' :tabnavs='detailOptions' :slim='true'></db-tabnav>

    <div class="span-detail-main flex-1" v-if="currentSpan">
      <span-detail
        :type='detailModel'
        :row='currentSpan'
        :spanParents='spanParents'
        :totalDuration='totalDuration'
        :activeName='detailModel'
        :trace-id="currentSpan.trace_id"
        :span-id="currentSpan.span_id"
        :start-time="spanStartMillis" />
    </div>
  </div>
</template>

<script lang="ts">
import i18n from '@/i18n';
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import SpanDetail from './spanDetail.vue'

@Component({
  components: {
    SpanDetail
  }
})
export default class SpanAside extends Vue {
  @Prop() private currentSpan!: any
  @Prop() private spanParents!: any[]
  @Prop() private totalDuration!: any
  @Prop() private totalExectime!: any


  @Watch('currentSpan', { immediate: true })
  private onCurrentSpan (newVal: any) {
    if (newVal) {
      if (!this.hasProfiling && this.detailModel === 'profiling') {
        this.detailModel = 'tags'
      }
      if (!newVal.hasLog && this.detailModel === 'logs') {
        this.detailModel = 'tags'
      }
    }
  }

  get excutePct () {
    const total = Number(this.totalDuration) || 0
    const selfTime = Number(this.currentSpan?.exectime)
    const duration = Number(this.currentSpan?.duration) || 0
    const self = Number.isFinite(selfTime) ? selfTime : duration
    const _excutePct = total ? self / total : NaN
    return !isNaN(_excutePct) && isFinite(_excutePct) ? _excutePct : '-'
  }

  get durationPct () {
    // 页面展示的占比：自身 / 总耗时
    return this.excutePct
  }

  get getSeriviceMapInfo () {
    return this.$store.state.Service.basicServiceMap?.[this.currentSpan?.serviceId] || {}
  }

  get isDatabuffSource () {
    const datasource = String(this.getSeriviceMapInfo?.datasource || '').toLowerCase();
    const virtual_service = this.getSeriviceMapInfo?.virtual_service;
    return (datasource === 'df-javaagent' || datasource === 'databuff') && !virtual_service;
  }

  // span详情
  public detailModel = 'tags'

  public showLogsTab () {
    if (!this.currentSpan?.hasLog) {
      return
    }
    this.detailModel = 'logs'
  }

  get detailOptions () {
    const tabs = [
      { label: i18n.t('modules.utils.static.s_24d67862') as string, labelKey: 'modules.utils.static.s_24d67862', value: 'tags' },
    ]
    if (this.currentSpan?.hasLog) {
      tabs.push({ label: i18n.t('modules.utils.static.s_456d29ef') as string, labelKey: 'modules.utils.static.s_456d29ef', value: 'logs' })
    }
    if (this.hasProfiling && this.isDatabuffSource) {
      tabs.push({ label: i18n.t('modules.views.appMonitor.traceDetail.s_9687d0eb') as string, labelKey: 'modules.views.appMonitor.traceDetail.s_9687d0eb', value: 'profiling' })
    }
    return tabs
  }

  get spanStartMillis () {
    const start = this.currentSpan?._start || this.currentSpan?.start
    if (!start) {
      return 0
    }
    const text = String(start)
    return text.length > 13 ? Math.floor(+text / 1_000_000) : +text
  }

  get spanEndMillis () {
    const end = this.currentSpan?.endTime || this.currentSpan?.end
    if (end) {
      const text = String(end)
      const ms = text.length > 13 ? Math.floor(+text.substring(0, 13)) : +text
      if (Number.isFinite(ms) && ms > 0) {
        return ms
      }
    }
    const startMs = this.spanStartMillis
    const duration = Number(this.currentSpan?.duration) || 0
    return startMs && duration ? startMs + Math.ceil(duration / 1_000_000) : 0
  }

  // 是否有性能剖析
  get hasProfiling () {
    return +this.currentSpan.onCpu > 0
  }


  // 跳转到Profiling
  private viewHotMethodHandle (id: string) {
    const data = this.currentSpan
    // 自定义时间范围参数，纳秒级
    const query: any = {
      sn: encodeURIComponent(data.service),
      sid: encodeURIComponent(data.serviceId),
      si: encodeURIComponent(data.serviceInstance || ''),
      resource: encodeURIComponent(data.resource),
      traceId: encodeURIComponent(data.trace_id),
      fromTimeNs: `${data._start}`,
      toTimeNs: `${data.end}`,
    }
    this.$router.push({
      path: '/appMonitor/hotMethods',
      query,
    })
  }

}
</script>

<style lang="scss" scoped>
.trace-span-detail {
  padding: 16px 0 0 16px;
  flex: 0 0 auto;
  height: 100%;
  border-left: 1px solid var(--border-color-light);
  border-top: 1px solid var(--border-color-light);

}
.span-detail-header {
  .span-header-main {
    line-height: 26px;
  }
  .span-header-info {
    font-size: 12px;
  }
}
.span-detail-main {
  overflow-y: auto;
}
</style>
