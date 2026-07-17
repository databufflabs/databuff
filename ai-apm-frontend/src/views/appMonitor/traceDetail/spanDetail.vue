<template>
  <div class="span-info-cont">
    <span-detail-baseinfo class="tags-wrapper" v-show='activeName === "tags"' :spanInfo='spanInfo' />
    <span-log
      v-if='activeName === "logs"'
      :trace-id="traceId"
      :span-id="spanId"
      :start-time="startTime" />
    <Profiling :detail="spanInfo" v-if='activeName === "profiling"' />
  </div>
</template>

<script lang="ts">

import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import Profiling from './span-profiling.vue'
import { Getter } from 'vuex-class';
import SpanKeys from './spanKeys';
import { orderBy } from 'lodash';
import dayjs from 'dayjs';
import SpanDetailBaseinfo from './spanDetailBaseinfo.vue';
import SpanLog from './span-log.vue';

@Component({
  components: {
    SpanDetailBaseinfo,
    SpanLog,
    Profiling,
  },
})

export default class SpanInfo extends Vue {
  @Getter('globalTime') private globalTimeFunc!: any;
  @Prop() private row!: any;
  @Prop() private totalDuration!: any;
  @Prop({ default: 'tags' }) private activeName!: string;
  @Prop() private spanParents!: any[]
  @Prop({ default: '' }) private traceId!: string
  @Prop({ default: '' }) private spanId!: string
  @Prop({ default: 0 }) private startTime!: number

  // 是否有性能剖析
  get hasProfiling () {
    return +this.spanInfo.onCpu > 0
  }

  get getCurrentSpanCalcPercent () {
    return this.spanInfo.duration && this.totalDuration ?
      this.spanInfo.duration / this.totalDuration : 0
  }

  private lastHourDuration = {
    fromTime: '',
    toTime: '',
  }

  private percentLoading = false;
  private spanInfo: any = {}
  private spanPercentList: any = [];
  private jsonInfo: any = {}
  private requestHeaderInfo: any = {}
  private responseHeaderInfo: any = {}

  get getSeriviceMapInfo () {
    return this.$store.state.Service.basicServiceMap?.[this.row?.serviceId] || {}
  }

  // 已展开的keys
  private expandedKeys: string[] = [];

  @Watch('row', { immediate: true })
  private onRowChange (newVal: any) {
    if (newVal) {
      const { fromTime, toTime } = this.globalTimeFunc()
      const normalizeDirection = (value: any): number => Number(value) === 1 ? 1 : 0
      this.lastHourDuration.fromTime = dayjs(+toTime  - 3600 * 1000).format('YYYY-MM-DD HH:mm:ss')
      this.lastHourDuration.toTime = dayjs(toTime).format('YYYY-MM-DD HH:mm:ss')
      const startMs = this.toEpochMillis(newVal._start || newVal.start || newVal.startTime)
      const endMs = this.toEpochMillis(newVal.endTime || newVal.end)
        || (startMs && newVal.duration
          ? startMs + Math.ceil(Number(newVal.duration) / 1_000_000)
          : 0)
      this.spanInfo = {
        ...newVal,
        isIn: newVal._isInBac !== undefined ? normalizeDirection(newVal._isInBac) : normalizeDirection(newVal.isIn),
        isOut: normalizeDirection(newVal.isOut),
        duration: newVal.duration || 0,
        startTime: startMs,
        endTime: endMs,
      }

      // 没有性能剖析，切换到tags
      if (!this.hasProfiling && this.activeName === 'profiling') {
        this.activeName = 'tags'
      }

      const meta = newVal.meta || {};
      const translateObj: any = {};
      Object.keys(meta).forEach(key => {
        if (key.indexOf('meta.') === 0) {
          const _key = key.replace('meta.', '')
          meta[_key] = meta[_key] || meta[key]
          delete meta[key]
        }
      })

      // 请求头 响应头
      const formatHeaderInfo = (headerInfo: any) => {
        const formatedInfo: any = {}
        try {
          const _headerInfo = JSON.parse(headerInfo || '{}')
          orderBy(Object.keys(_headerInfo), [(key: string) => key.toLocaleLowerCase()], ['asc']).forEach(key => {
            formatedInfo[key] = _headerInfo[key]
          })
        } catch (error) {
          //
        }
        return formatedInfo
      }
      this.requestHeaderInfo = formatHeaderInfo(meta.requestHeader)
      this.responseHeaderInfo = formatHeaderInfo(meta.responseHeader)

      const metaKeys = orderBy(Object.keys(meta), [(key: string) => key.toLocaleLowerCase()], ['asc']).filter(key => {
        return key !== 'requestHeader' && key !== 'responseHeader'
      });
      metaKeys.forEach(key => {
        const _key = (SpanKeys as any)[key] || key;
        if (`${meta[key] || ''}`) {
          translateObj[(_key as any)] = `${meta[key] || ''}`.split('\n').filter(t => !!t)
        }
      })
      this.jsonInfo = translateObj;
    }
  }

  private toggleMetaItem (key: string) {
    const keyIdx = this.expandedKeys.findIndex((item) => item === key)
    if (keyIdx !== -1) {
      this.expandedKeys.splice(keyIdx, 1)
    } else {
      this.expandedKeys.push(key)
    }
  }

  /** Normalize portal start/end fields (ms string/number, or ns) to epoch millis. */
  private toEpochMillis (value: any): number {
    if (value == null || value === '') {
      return 0
    }
    if (typeof value === 'number') {
      return value > 1_000_000_000_000_000 ? Math.floor(value / 1_000_000) : value
    }
    const text = String(value).trim()
    if (!text) {
      return 0
    }
    if (text.length > 13) {
      return Math.floor(+text.substring(0, 13))
    }
    const num = +text
    return Number.isFinite(num) ? num : 0
  }

}
</script>

<style lang="scss" scoped>
.span-info-cont{
  height: 100%;
  padding-bottom: 16px;
  position: relative;
  overflow-y: auto;
  background-color: var(--bg-color);

  .span-info-header{
    height: 32px;
    line-height: 32px;
    overflow: hidden;
    position: sticky;
    top: 0;
    box-shadow: 0 0 5px var(--bg-color-base);
    background-color: var(--bg-color);
    z-index: 9;
  }
  .span-baseinfo{
    flex: 1;
    overflow: hidden;
    .span-link-service{
      max-width: 150px;
      font-weight: 700;
      font-size: 15px;
      color: var(--color-text-primary);
      line-height: 20px;
    }
    .span-link-name{
      max-width: 150px;
      // line-height: 1;
      line-height: 20px;
    }
    .span-link-resource{
      flex: 1;
      // line-height: 1;
      line-height: 20px;
    }
  }
  .span-extra{
    flex: none;
  }

}
.tag-title{
  vertical-align: middle;
  max-width: 120px;
  margin-right: 4px;
  color: #fff;
  opacity: 0.8;
}
.tags-wrapper {
  padding: 16px 16px 0 0;
}
</style>
