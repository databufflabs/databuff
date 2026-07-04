<template>
  <div class="span-log-wrapper">
    <el-timeline class="timeline-tail">
      <el-timeline-item v-for='item in logList' :key='item.id'
        :timestamp="item._timestamp" placement="top" type='primary'>
        <div class="span-log-item">
          <div class="font-12">
            <span class="describe">{{ $t('modules.views.appMonitor.traceDetail.s_dbe2c06e') }}</span>{{ item.hostname }}</div>
          <div class="font-12">
            <span class="describe">{{ $t('modules.views.appMonitor.traceDetail.s_0bbeee07') }}</span>
            <span @click.stop='viewServiceDetail(item.service)' :class='[getBasicServiceMap[item.service] ? "cphu" : ""]'>
              <i class="db-icon vm">{{ item.service_type | DbIconFilter }}</i>
              {{ item.service }}
            </span>
            
          </div>

          <div class="font-12">
            <span class="describe">{{ $t('modules.views.alarmCenter.eventDetail.s_3fea7ca7') }}</span>
            <span :class="['log-level', 'log-level-' + (item.status || 'INFO').toLowerCase()]">{{ item.status || 'INFO' }}</span>
          </div>
          <div class="font-12 line-clamp-3" :title="item._displayMessage">
            <span class="describe">{{ $t('modules.views.appMonitor.traceDetail.s_bd002975') }}</span>{{ item.messageKey ? $t(item.messageKey) : item._displayMessage }}</div>
        </div>
      </el-timeline-item>
    </el-timeline>
    <div v-if='!noMore' @click="loadMoreHandle" :class='["load-more-btn describe tc font-12", listLoading ? "" : "cp db-blue"]'>{{ listLoading ? $t('modules.views.appMonitor.traceDetail.s_26b5bd49') : $t('modules.views.appMonitor.traceDetail.s_77281549')  }}</div>
    <div v-if="traceId && logList.length" class="tc mt-8">
      <span @click="viewAllLogsHandle" class="db-blue cp font-12">在日志分析中查看全部</span>
    </div>

    <div v-if='!logList.length && !listLoading' class="describe tc mt-20">{{ $t('modules.components.charts.s_21efd88b') }}</div>
  </div>
</template>

<script lang="ts">
import { toAsyncWait } from '@/utils/common';
import i18n from '@/i18n';
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import LogApi from '@/api/log';
import { v4 as uuidv4 } from 'uuid'
import dayjs from 'dayjs';

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

  private mounted () {
    if (this.spanId) {
      this.getTableList(1)
    }
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
      const { data = [], total = 0, scrollId } = result || {};
      data.forEach((log: any) => {
        log.id = uuidv4();
        log._message = (log.message || '').split('\n')
        log._displayMessage = this.formatLogMessage(log.message)
        log._timestamp = this.formatLogTimestamp(log.timestamp)
        const { service_type, type } = (this.getBasicServiceMap || {})[log?.service] || {};
        log.service_type = type || service_type || 'default'
      })
      
      this.logList = page === 1 ? data : Array.from(this.logList).concat(data);
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

  /** API returns epoch millis string; tolerate legacy ISO timestamps. */
  private formatLogTimestamp (timestamp: any) {
    if (!timestamp) {
      return ''
    }
    const text = String(timestamp).trim()
    if (!text) {
      return ''
    }
    if (/^\d{10,13}$/.test(text)) {
      const millis = text.length > 10 ? +text.substring(0, 13) : +text * 1000
      return dayjs(millis).format('YYYY-MM-DD HH:mm:ss')
    }
    const parsed = dayjs(text)
    return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : text
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
  padding-left: 1px;
  :deep(.el-timeline-item__timestamp) {
    font-size: 13px;
    color: var(--color-text-secondary);
  }
}
.line-clamp-3 {
  display: -webkit-box;        /* 使用弹性盒子模型 */
  -webkit-box-orient: vertical; /* 设置盒子方向为垂直 */
  -webkit-line-clamp: 3;       /* 显示的最大行数 */
  overflow: hidden;
}
.span-log-item {
  line-height: 20px;
}
.log-level {
  font-weight: 500;
}
.log-level-info, .log-level-debug, .log-level-trace {
  color: var(--color-text-secondary);
}
.log-level-warn {
  color: #e6a23c;
}
.log-level-error, .log-level-fatal {
  color: #f56c6c;
}
</style>
