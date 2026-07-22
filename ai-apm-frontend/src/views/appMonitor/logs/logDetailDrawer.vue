<template>
  <el-drawer
    :visible.sync="drawerVisible"
    :title="drawerTitle"
    direction="rtl"
    size="560px"
    append-to-body
    :wrapper-closable="true"
    custom-class="log-detail-drawer"
    @closed="onClosed">
    <div v-loading="loading" class="log-detail-shell">
      <div v-if="error && !detail" class="log-detail-state describe">{{ errorText }}</div>
      <template v-else-if="detail">
        <div class="log-detail-tabs">
          <button
            type="button"
            :class="['log-detail-tab', activeTab === 'overview' ? 'is-active' : '']"
            @click="activeTab = 'overview'">
            {{ $t('modules.views.appMonitor.logs.s_overview') }}
          </button>
          <button
            type="button"
            :class="['log-detail-tab', activeTab === 'json' ? 'is-active' : '']"
            @click="activeTab = 'json'">
            JSON
          </button>
        </div>

        <div class="log-detail-scroll">
          <div v-if="activeTab === 'overview'" class="log-detail-body">
            <section class="log-detail-section">
              <div class="log-detail-section-title">{{ $t('modules.views.alarmCenter.eventDetail.s_a19a72d2') }}</div>
              <pre :class="['log-detail-message', messageIsJson ? 'is-json' : '']">{{ messageDisplay }}</pre>
            </section>

            <section class="log-detail-section">
              <div class="log-detail-section-title">{{ $t('modules.views.appMonitor.logs.s_meta') }}</div>
              <div class="log-detail-kv-list">
                <div v-for="item in metaItems" :key="item.key" class="log-detail-kv">
                  <div class="log-detail-k">{{ item.label }}</div>
                  <div class="log-detail-v">
                    <span
                      v-if="item.key === 'status'"
                      :class="['log-level-tag', severityClass(item.value)]">{{ item.value || '-' }}</span>
                    <template v-else-if="item.key === 'traceId' && item.value">
                      <span class="log-detail-mono">{{ item.value }}</span>
                      <span
                        v-if="canViewTrace"
                        @click.stop="onViewTrace"
                        class="log-detail-link db-blue cp">{{ $t('modules.views.metrics.list.s_607e7a4f') }}</span>
                    </template>
                    <span v-else :class="item.mono ? 'log-detail-mono' : ''">{{ item.value || '-' }}</span>
                  </div>
                </div>
              </div>
            </section>

            <section v-if="attributeItems.length" class="log-detail-section">
              <div class="log-detail-section-title">{{ $t('modules.views.appMonitor.logs.s_attributes') }}</div>
              <div class="log-detail-kv-list">
                <div v-for="item in attributeItems" :key="`attr-${item.key}`" class="log-detail-kv">
                  <div class="log-detail-k">{{ item.key }}</div>
                  <div class="log-detail-v log-detail-mono">{{ item.value }}</div>
                </div>
              </div>
            </section>

            <section v-if="resourceItems.length" class="log-detail-section">
              <div class="log-detail-section-title">{{ $t('modules.views.appMonitor.logs.s_resources') }}</div>
              <div class="log-detail-kv-list">
                <div v-for="item in resourceItems" :key="`res-${item.key}`" class="log-detail-kv">
                  <div class="log-detail-k">{{ item.key }}</div>
                  <div class="log-detail-v log-detail-mono">{{ item.value }}</div>
                </div>
              </div>
            </section>
          </div>

          <div v-else class="log-detail-body">
            <pre class="log-detail-json">{{ jsonText }}</pre>
          </div>
        </div>
      </template>
    </div>
  </el-drawer>
</template>

<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import dayjs from 'dayjs';
import i18n from '@/i18n';
import LogApi from '@/api/log';
import { toAsyncWait } from '@/utils/common';
import { getSeverityClass } from '@/utils/logSeverity';

type KvItem = { key: string; label?: string; value: string; mono?: boolean };
type MessageBody = { text: string; isJson: boolean; parsed?: any };

@Component({ name: 'LogDetailDrawer' })
export default class LogDetailDrawer extends Vue {
  @Prop({ type: Boolean, default: false }) private visible!: boolean;
  @Prop({ default: null }) private row!: any;

  private loading = false;
  private error = false;
  private detail: any = null;
  private activeTab: 'overview' | 'json' = 'overview';
  private loadToken = 0;
  private messageBody: MessageBody = { text: '-', isJson: false };

  get drawerVisible () {
    return this.visible;
  }

  set drawerVisible (value: boolean) {
    this.$emit('update:visible', value);
  }

  get drawerTitle () {
    return i18n.t('modules.views.appMonitor.logs.s_detail_title') as string;
  }

  get errorText () {
    return i18n.t('modules.views.appMonitor.logs.s_load_failed') as string;
  }

  get canViewTrace () {
    return Boolean(this.detail?.traceId);
  }

  get messageDisplay () {
    return this.messageBody.text;
  }

  get messageIsJson () {
    return this.messageBody.isJson;
  }

  get metaItems (): KvItem[] {
    const detail = this.detail || {};
    const severity = detail.status || '';
    const severityNumber = detail.severityNumber;
    const severityText = severityNumber
      ? `${severity || '-'} · ${severityNumber}`
      : severity;
    return [
      { key: 'timestamp', label: i18n.t('modules.views.appMonitor.errorDetail.s_13f7745f') as string, value: this.formatTime(detail.timestamp), mono: true },
      { key: 'status', label: i18n.t('modules.views.alarmCenter.eventDetail.s_3fea7ca7') as string, value: severityText },
      { key: 'service', label: i18n.t('modules.views.alarmCenter.alarm.s_47d68cd0') as string, value: detail.service || '' },
      { key: 'serviceInstance', label: i18n.t('modules.views.alarmCenter.alarm.s_71673bab') as string, value: detail.serviceInstance || '' },
      { key: 'hostname', label: i18n.t('modules.views.alarmCenter.alarm.s_65227369') as string, value: detail.hostname || '' },
      { key: 'traceId', label: 'Trace ID', value: detail.traceId || '', mono: true },
      { key: 'spanId', label: 'Span ID', value: detail.spanId || '', mono: true },
      { key: 'timeNs', label: 'time_unix_nano', value: detail.timeNs || '', mono: true },
      { key: 'observedTimeNs', label: 'observed_time_unix_nano', value: detail.observedTimeNs || '', mono: true },
    ].filter((item) => item.key === 'status' || item.key === 'timestamp' || Boolean(item.value));
  }

  get attributeItems (): KvItem[] {
    return this.mapToItems(this.detail?.attributes);
  }

  get resourceItems (): KvItem[] {
    return this.mapToItems(this.detail?.resources);
  }

  get jsonText () {
    if (!this.detail) {
      return '';
    }
    const bodyFormatted = this.messageBody;
    const payload = {
      body: bodyFormatted.isJson ? bodyFormatted.parsed : (this.detail.message || ''),
      timestamp: this.formatTimeIso(this.detail.timestamp),
      time_unix_nano: this.detail.timeNs || '',
      observed_time_unix_nano: this.detail.observedTimeNs || '',
      severity_text: this.detail.status || '',
      severity_number: this.detail.severityNumber || 0,
      service: this.detail.service || '',
      service_id: this.detail.serviceId || '',
      service_instance: this.detail.serviceInstance || '',
      hostname: this.detail.hostname || '',
      trace_id: this.detail.traceId || '',
      span_id: this.detail.spanId || '',
      attributes: this.detail.attributes || {},
      resources: this.detail.resources || {},
    };
    return JSON.stringify(payload, null, 2);
  }

  @Watch('visible')
  private onVisibleChange (visible: boolean) {
    if (visible) {
      this.activeTab = 'overview';
      this.loadDetail();
    }
  }

  @Watch('row')
  private onRowChange () {
    if (this.visible) {
      this.activeTab = 'overview';
      this.loadDetail();
    }
  }

  private onClosed () {
    this.detail = null;
    this.error = false;
    this.loading = false;
    this.messageBody = { text: '-', isJson: false };
    this.activeTab = 'overview';
  }

  private onViewTrace () {
    if (!this.detail) {
      return;
    }
    this.$emit('view-trace', this.detail);
  }

  private setDetail (data: any) {
    this.detail = data;
    this.messageBody = this.formatMessageBody(data?.message);
  }

  private async loadDetail () {
    const row = this.row;
    const timeNs = String(row?.timeNs || '').trim();

    if (!timeNs) {
      this.setDetail(this.fallbackFromRow());
      this.error = !this.detail;
      this.loading = false;
      return;
    }
    if (row?._detail && row._detailTimeNs === timeNs) {
      this.setDetail(row._detail);
      this.error = false;
      this.loading = false;
      return;
    }

    const token = ++this.loadToken;
    this.loading = true;
    this.error = false;
    this.detail = null;
    this.messageBody = { text: '-', isJson: false };
    const { result, error } = await toAsyncWait(LogApi.getLogDetail({
      timeNs,
      serviceId: row?.serviceId || undefined,
    }));
    if (token !== this.loadToken) {
      return;
    }
    this.loading = false;
    if (error) {
      this.error = true;
      this.setDetail(this.fallbackFromRow());
      return;
    }
    const data = result?.data || {};
    if (!data || (!data.message && !data.timeNs)) {
      this.setDetail(this.fallbackFromRow());
      this.error = !this.detail;
      return;
    }
    this.setDetail(data);
    row._detail = data;
    row._detailTimeNs = timeNs;
  }

  private fallbackFromRow () {
    if (!this.row) {
      return null;
    }
    return {
      ...this.row,
      attributes: {},
      resources: {},
      severityNumber: 0,
      observedTimeNs: '',
    };
  }

  private formatMessageBody (message: unknown): MessageBody {
    if (message == null || message === '') {
      return { text: '-', isJson: false };
    }
    const text = String(message);
    const trimmed = text.trim();
    if (!trimmed || (trimmed[0] !== '{' && trimmed[0] !== '[')) {
      return { text, isJson: false };
    }
    try {
      const parsed = JSON.parse(trimmed);
      if (parsed === null || typeof parsed !== 'object') {
        return { text, isJson: false };
      }
      return { text: JSON.stringify(parsed, null, 2), isJson: true, parsed };
    } catch {
      return { text, isJson: false };
    }
  }

  private mapToItems (map: Record<string, any> | undefined): KvItem[] {
    if (!map || typeof map !== 'object') {
      return [];
    }
    return Object.keys(map)
      .sort((a, b) => a.localeCompare(b))
      .map((key) => ({
        key,
        value: map[key] == null ? '' : String(map[key]),
      }))
      .filter((item) => item.value !== '');
  }

  private formatTime (value: any) {
    const ms = this.toMillis(value);
    return ms ? dayjs(ms).format('YYYY-MM-DD HH:mm:ss.SSS') : '';
  }

  private formatTimeIso (value: any) {
    const ms = this.toMillis(value);
    return ms ? new Date(ms).toISOString() : '';
  }

  private toMillis (value: any) {
    if (value == null || value === '') {
      return 0;
    }
    const text = String(value).trim();
    if (!text) {
      return 0;
    }
    const num = Number(text.substring(0, 13));
    return Number.isFinite(num) ? num : 0;
  }

  private severityClass (level: string) {
    const text = (level || '').split('·')[0].trim();
    return getSeverityClass(text);
  }
}
</script>

<style lang="scss" scoped>
.log-detail-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.log-detail-state {
  padding: 24px 16px;
  font-size: 12px;
}

.log-detail-tabs {
  display: flex;
  flex: none;
  gap: 4px;
  padding: 0 16px;
  border-bottom: 1px solid var(--border-color-base);
}

.log-detail-tab {
  appearance: none;
  border: none;
  background: transparent;
  padding: 10px 14px;
  margin-bottom: -1px;
  font-size: 13px;
  line-height: 20px;
  color: var(--color-text-secondary);
  cursor: pointer;
  border-bottom: 2px solid transparent;

  &.is-active {
    color: var(--color-text-primary);
    font-weight: 500;
    border-bottom-color: var(--color-primary, #409eff);
  }

  &:hover {
    color: var(--color-text-primary);
  }
}

.log-detail-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 16px;
}

.log-detail-section + .log-detail-section {
  margin-top: 18px;
}

.log-detail-section-title {
  margin-bottom: 10px;
  font-size: 12px;
  font-weight: 500;
  color: var(--color-text-secondary);
  letter-spacing: 0.02em;
}

.log-detail-message,
.log-detail-json {
  margin: 0;
  padding: 12px 14px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: normal;
  font-size: 12px;
  line-height: 1.55;
  color: var(--color-text-primary);
  background: var(--bg-color03);
  border-radius: 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;
}

.log-detail-message.is-json,
.log-detail-json {
  white-space: pre;
  overflow: auto;
}

.log-detail-kv-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.log-detail-kv {
  display: flex;
  align-items: flex-start;
  min-width: 0;
  font-size: 12px;
  line-height: 20px;
}

.log-detail-k {
  flex: none;
  width: 160px;
  padding-right: 12px;
  color: var(--color-text-secondary);
  overflow-wrap: anywhere;
}

.log-detail-v {
  flex: 1;
  min-width: 0;
  color: var(--color-text-primary);
  overflow-wrap: anywhere;
}

.log-detail-mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;
}

.log-detail-link {
  margin-left: 8px;
  font-size: 12px;
}

.log-level-tag {
  display: inline-block;
  padding: 0 8px;
  line-height: 20px;
  border-radius: 3px;
  font-size: 12px;
  background-color: var(--bg-color03);
  color: var(--color-text-regular);

  &.is-error {
    color: var(--color-danger);
    background-color: rgba(var(--color-danger-rgb, 245, 108, 108), 0.12);
  }
  &.is-warn {
    color: var(--color-warning);
    background-color: rgba(var(--color-warning-rgb, 230, 162, 60), 0.12);
  }
  &.is-info {
    color: var(--color-success);
    background-color: rgba(var(--color-success-rgb, 103, 194, 58), 0.12);
  }
}
</style>

<style lang="scss">
.log-detail-drawer.el-drawer {
  .el-drawer__header {
    margin-bottom: 0;
    padding: 14px 16px;
    border-bottom: 1px solid var(--border-color-base);
    color: var(--color-text-primary);
    font-size: 14px;
    font-weight: 500;
  }

  .el-drawer__body {
    padding: 0;
    height: calc(100% - 53px);
    overflow: hidden;
  }
}
</style>
