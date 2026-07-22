<template>
  <div class="span-log-wrapper">
    <!-- Shared fields across current page of logs -->
    <div v-if="commonMetaItems.length" class="log-common-meta">
      <div
        v-for="meta in commonMetaItems"
        :key="meta.key"
        class="log-common-meta-item">
        <span class="log-common-meta-label">{{ meta.label }}:</span>
        <span
          v-if="meta.key === 'service'"
          @click.stop="viewServiceDetail(meta.value)"
          :class="['log-common-meta-value', getBasicServiceMap[meta.value] ? 'cphu' : '']">
          <i class="db-icon vm">{{ meta.serviceType | DbIconFilter }}</i>
          {{ meta.value }}
        </span>
        <span class="log-common-meta-value" :title="meta.value">{{ meta.value }}</span>
      </div>
    </div>

    <div v-if="logList.length" class="log-list">
      <div
        v-for="item in logList"
        :key="item.id"
        class="log-row">
        <div class="log-row-rail" :class="severityClass(item.status)"></div>
        <div class="log-row-body">
          <div class="log-row-head">
            <span class="log-time" :title="item._timestampFull">{{ item._timestamp }}</span>
            <span :class="['log-level-tag', severityClass(item.status)]">{{ item.status || 'INFO' }}</span>
            <span
              v-for="field in item._extraFields"
              :key="field.key"
              class="log-extra-field"
              :title="field.value">
              <span class="describe">{{ field.label }}:</span>{{ field.value }}
            </span>
          </div>
          <div class="log-message" :title="item._displayMessage">
            {{ item.messageKey ? $t(item.messageKey) : item._displayMessage }}
          </div>
        </div>
      </div>
    </div>

    <div
      v-if="!noMore"
      @click="loadMoreHandle"
      :class="['load-more-btn describe tc font-12', listLoading ? '' : 'cp db-blue']">
      {{ listLoading
        ? $t('modules.views.appMonitor.traceDetail.s_26b5bd49')
        : $t('modules.views.appMonitor.traceDetail.s_77281549') }}
    </div>
    <div v-if="traceId && logList.length" class="tc mt-8">
      <span @click="viewAllLogsHandle" class="db-blue cp font-12">
        {{ $t('modules.views.appMonitor.traceDetail.s_f7444b29') }}
      </span>
    </div>

    <div v-if="!logList.length && !listLoading" class="describe tc mt-20">
      {{ $t('modules.components.charts.s_21efd88b') }}
    </div>
  </div>
</template>

<script lang="ts">
import { toAsyncWait } from '@/utils/common';
import i18n from '@/i18n';
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import LogApi from '@/api/log';
import { v4 as uuidv4 } from 'uuid'
import dayjs from 'dayjs';
import { getSeverityClass } from '@/utils/logSeverity';

type CommonFieldKey = 'service' | 'serviceInstance' | 'hostname'

/** Status stays per-row; only identity fields can be hoisted. */
const COMMON_FIELD_KEYS: CommonFieldKey[] = [
  'service',
  'serviceInstance',
  'hostname',
]

@Component
export default class SpanLog extends Vue {
  @Prop() private traceId!: string
  @Prop() private spanId!: string
  @Prop() private startTime!: number

  @Watch('spanId')
  private onSpanIdChange () {
    this.getTableList(1)
  }

  private queryParams: any = {
    pageNum: 1,
    pageSize: 50,
    query: '',
    hosts: [],
    services: [],
    fromTime: '',
    toTime: '',
  }

  private logList: any[] = [];
  private listLoading = true;
  private listTotal = 0;

  // 日志上下文弹窗
  private showMoreLog = false

  private moreLog = '';

  get getBasicServiceMap () {
    return this.$store.getters['Service/basicServiceMap']
  }

  get noMore () {
    return this.logList.length >= this.listTotal
  }

  /** Fields identical across all currently loaded logs. */
  get commonFields (): Partial<Record<CommonFieldKey, string>> {
    if (!this.logList.length) {
      return {}
    }
    const common: Partial<Record<CommonFieldKey, string>> = {}
    COMMON_FIELD_KEYS.forEach((key) => {
      const first = this.normalizeField(this.logList[0][key])
      if (!first) {
        return
      }
      const allSame = this.logList.every(
        (log) => this.normalizeField(log[key]) === first,
      )
      if (allSame) {
        common[key] = first
      }
    })
    return common
  }

  get commonMetaItems () {
    const common = this.commonFields
    const items: Array<{
      key: CommonFieldKey
      label: string
      value: string
      serviceType?: string
    }> = []

    if (common.service) {
      const { service_type, type } = (this.getBasicServiceMap || {})[common.service] || {}
      items.push({
        key: 'service',
        label: i18n.t('modules.views.appMonitor.traceDetail.s_0bbeee07') as string,
        value: common.service,
        serviceType: type || service_type || 'default',
      })
    }
    if (common.serviceInstance) {
      items.push({
        key: 'serviceInstance',
        label: `${i18n.t('modules.views.alarmCenter.alarm.s_71673bab')}`,
        value: common.serviceInstance,
      })
    }
    if (common.hostname) {
      items.push({
        key: 'hostname',
        label: i18n.t('modules.views.appMonitor.traceDetail.s_dbe2c06e') as string,
        value: common.hostname,
      })
    }
    return items.map((item) => ({
      ...item,
      label: this.stripTrailingColon(item.label),
    }))
  }

  private mounted () {
    if (this.spanId) {
      this.getTableList(1)
    }
  }

  private normalizeField (value: any) {
    return String(value || '').trim()
  }

  private stripTrailingColon (label: string) {
    return String(label || '').replace(/[：:]\s*$/, '')
  }

  private severityClass (level: string) {
    return getSeverityClass(level)
  }

  private enrichLogList (list: any[]) {
    const common = this.computeCommonFields(list)
    const sameDay = this.isSameCalendarDay(list)
    return list.map((log) => {
      const { service_type, type } = (this.getBasicServiceMap || {})[log?.service] || {}
      const status = String(log.status || 'INFO').trim().toUpperCase() || 'INFO'
      const { display, full } = this.formatLogTimestamp(log.timestamp, sameDay)
      const extraFields: Array<{ key: string; label: string; value: string }> = []
      if (!common.service && log.service) {
        extraFields.push({
          key: 'service',
          label: this.stripTrailingColon(
            i18n.t('modules.views.appMonitor.traceDetail.s_0bbeee07') as string,
          ),
          value: log.service,
        })
      }
      if (!common.serviceInstance && log.serviceInstance) {
        extraFields.push({
          key: 'serviceInstance',
          label: this.stripTrailingColon(
            i18n.t('modules.views.alarmCenter.alarm.s_71673bab') as string,
          ),
          value: log.serviceInstance,
        })
      }
      if (!common.hostname && log.hostname) {
        extraFields.push({
          key: 'hostname',
          label: this.stripTrailingColon(
            i18n.t('modules.views.appMonitor.traceDetail.s_dbe2c06e') as string,
          ),
          value: log.hostname,
        })
      }
      return {
        ...log,
        status,
        service_type: type || service_type || 'default',
        _timestamp: display,
        _timestampFull: full,
        _extraFields: extraFields,
      }
    })
  }

  private computeCommonFields (list: any[]): Partial<Record<CommonFieldKey, string>> {
    if (!list.length) {
      return {}
    }
    const common: Partial<Record<CommonFieldKey, string>> = {}
    COMMON_FIELD_KEYS.forEach((key) => {
      const first = this.normalizeField(list[0][key])
      if (!first) {
        return
      }
      if (list.every((log) => this.normalizeField(log[key]) === first)) {
        common[key] = first
      }
    })
    return common
  }

  private isSameCalendarDay (list: any[]) {
    const days = list
      .map((log) => this.parseLogDayjs(log.timestamp))
      .filter((d): d is dayjs.Dayjs => !!d && d.isValid())
      .map((d) => d.format('YYYY-MM-DD'))
    return days.length > 0 && days.every((d) => d === days[0])
  }

  private async getTableList (page = 1) {
    this.queryParams.pageNum = page

    const offset = this.queryParams.pageSize * (page - 1)
    const params = {
      ...this.queryParams,
      offset,
      size: this.queryParams.pageSize,
      fromTimeNs: `${+new Date(this.queryParams.fromTime) * 1000 * 1000}`,
      toTimeNs: `${+new Date(this.queryParams.toTime) * 1000 * 1000}`,
    }
    this.traceId && (params.traceId = this.traceId);
    this.spanId && (params.spanId = this.spanId);
    if (this.startTime) {
      params.fromTimeNs = `${(this.startTime - 3600000) * 1000 * 1000}`
      params.toTimeNs = `${(this.startTime + 3600000) * 1000 * 1000}`
    }
    for (const _key in params) {
      const val = params[_key]
      if (Array.isArray(val) && !val.length) {
        delete params[_key]
      }
    }

    delete params.fromTime
    delete params.toTime
    delete params.pageNum
    delete params.pageSize
    this.listLoading = true;
    const { result, error } = await toAsyncWait(LogApi.getLogList(params))
    if (!error) {
      const { data = [], total = 0 } = result || {};
      const prepared = data.map((log: any) => ({
        ...log,
        id: uuidv4(),
        _message: (log.message || '').split('\n'),
        _displayMessage: this.formatLogMessage(log.message),
      }))
      const merged = page === 1 ? prepared : Array.from(this.logList).concat(prepared)
      // Re-enrich full list so common-field extraction stays correct after load-more
      this.logList = this.enrichLogList(merged.map((log) => ({
        ...log,
        // drop previous enrichment fields before recompute
        _extraFields: undefined,
        _timestamp: undefined,
        _timestampFull: undefined,
      })))
      this.listTotal = total;
    } else {
      if (error.message !== 'interrupt') {
        this.$message.error(i18n.t('modules.views.appMonitor.serviceFlow.s_e05c1ca3') as string)
      }
    }
    this.listLoading = false;
  }

  private loadMoreHandle () {
    this.getTableList(this.queryParams.pageNum + 1)
  }

  /** Trace detail already shows trace id — strip redundant traceId from log body. */
  private formatLogMessage (message: any) {
    if (!message) {
      return ''
    }
    return String(message)
      .replace(/\s*trace[_\s-]?id\s*[=:]\s*[a-fA-F0-9]+\s*/gi, ' ')
      .replace(/\s{2,}/g, ' ')
      .trim()
  }

  private parseLogDayjs (timestamp: any) {
    if (!timestamp) {
      return null
    }
    const text = String(timestamp).trim()
    if (!text) {
      return null
    }
    if (/^\d{10,13}$/.test(text)) {
      const millis = text.length > 10 ? +text.substring(0, 13) : +text * 1000
      return dayjs(millis)
    }
    const parsed = dayjs(text)
    return parsed.isValid() ? parsed : null
  }

  /** API returns epoch millis string; tolerate legacy ISO timestamps. */
  private formatLogTimestamp (timestamp: any, preferTimeOnly = false) {
    const parsed = this.parseLogDayjs(timestamp)
    if (!parsed) {
      const text = timestamp == null ? '' : String(timestamp)
      return { display: text, full: text }
    }
    const full = parsed.format('YYYY-MM-DD HH:mm:ss')
    const display = preferTimeOnly ? parsed.format('HH:mm:ss') : full
    return { display, full }
  }

  private viewServiceDetail (service: string) {
    const serviceitem = this.getBasicServiceMap[service];
    if (!serviceitem) {
      return
    }
    this.$router.push({
      path: '/appMonitor/serviceDetail',
      query: {
        ...this.getRouteTimeOrRange,
        sid: encodeURIComponent(serviceitem.id)
      }
    })
  }

  private viewAllLogsHandle () {
    if (!this.traceId) {
      return
    }
    const query: Record<string, string> = {
      traceId: encodeURIComponent(this.traceId),
    }
    if (this.spanId) {
      query.spanId = encodeURIComponent(this.spanId)
    }
    this.$router.push({
      path: '/appMonitor/logs',
      query: {
        ...this.getRouteTimeOrRange,
        ...query,
      },
    })
  }
}
</script>

<style lang="scss" scoped>
.span-log-wrapper {
  padding: 4px 4px 0 0;
}

.log-common-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
  margin-bottom: 12px;
  padding: 8px 10px;
  background: var(--bg-color03);
  border-radius: 4px;
  line-height: 20px;
}

.log-common-meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  font-size: 12px;
}

.log-common-meta-label {
  color: var(--color-text-secondary);
  flex: none;
}

.log-common-meta-value {
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.log-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.log-row {
  display: flex;
  gap: 8px;
  min-width: 0;
}

.log-row-rail {
  width: 3px;
  flex: none;
  border-radius: 2px;
  background: var(--bg-color03);
  align-self: stretch;

  &.is-info {
    background: var(--color-success, #08be7e);
  }
  &.is-warn {
    background: var(--color-warning, #f79532);
  }
  &.is-error {
    background: var(--color-danger, #e12828);
  }
  &.is-muted {
    background: var(--color-text-secondary, #b5b7bb);
  }
}

.log-row-body {
  flex: 1;
  min-width: 0;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--border-color-light, rgba(0, 0, 0, 0.06));
}

.log-row:last-child .log-row-body {
  border-bottom: none;
  padding-bottom: 0;
}

.log-row-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 8px;
  margin-bottom: 4px;
}

.log-time {
  font-size: 12px;
  color: var(--color-text-secondary);
  font-variant-numeric: tabular-nums;
  flex: none;
}

.log-extra-field {
  font-size: 12px;
  color: var(--color-text-regular);
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;

  .describe {
    margin-right: 2px;
  }
}

.log-message {
  font-size: 13px;
  line-height: 1.5;
  color: var(--color-text-primary);
  word-break: break-word;
  white-space: pre-wrap;
}

.log-level-tag {
  display: inline-block;
  padding: 0 6px;
  line-height: 18px;
  border-radius: 3px;
  font-size: 11px;
  font-weight: 500;
  background-color: var(--bg-color03);
  color: var(--color-text-regular);
  flex: none;

  &.is-error {
    color: var(--color-danger, #e12828);
    background-color: rgba(225, 40, 40, 0.1);
  }
  &.is-warn {
    color: var(--color-warning, #f79532);
    background-color: rgba(247, 149, 50, 0.12);
  }
  &.is-info {
    color: var(--color-success, #08be7e);
    background-color: rgba(8, 190, 126, 0.1);
  }
  &.is-muted {
    color: var(--color-text-secondary);
    background-color: var(--bg-color03);
  }
}

.load-more-btn {
  margin-top: 10px;
}
</style>
