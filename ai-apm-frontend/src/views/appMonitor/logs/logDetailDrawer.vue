<template>
  <el-drawer
    :visible.sync="drawerVisible"
    direction="rtl"
    size="720px"
    append-to-body
    :with-header="false"
    :wrapper-closable="true"
    custom-class="log-detail-drawer"
    @closed="onClosed">
    <div v-loading="loading" class="log-detail-shell">
      <header :class="['log-detail-header', detail ? severityClass(detail.status) : '']">
        <div class="log-detail-title-row">
          <div class="log-detail-title-wrap">
            <h3 class="log-detail-title">{{ drawerTitle }}</h3>
            <div v-if="detail" class="log-detail-summary">
              <span :class="['log-level-tag', severityClass(detail.status)]">{{ severityLabel }}</span>
              <span v-if="detail.service" class="log-detail-summary-text">{{ detail.service }}</span>
              <span v-if="detail.serviceInstance" class="log-detail-summary-sep">/</span>
              <span v-if="detail.serviceInstance" class="log-detail-summary-text is-muted">{{ detail.serviceInstance }}</span>
              <span v-if="eventTimeText" class="log-detail-summary-sep">·</span>
              <span v-if="eventTimeText" class="log-detail-summary-text is-muted is-mono">{{ eventTimeText }}</span>
            </div>
          </div>
          <button type="button" class="log-detail-close" @click="drawerVisible = false">
            <i class="el-icon-close" />
          </button>
        </div>

        <div v-if="detail" class="log-detail-tabs" role="tablist">
          <button
            type="button"
            role="tab"
            :aria-selected="activeTab === 'overview'"
            :class="['log-detail-tab', activeTab === 'overview' ? 'is-active' : '']"
            @click="activeTab = 'overview'">
            {{ $t('modules.views.appMonitor.logs.s_overview') }}
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="activeTab === 'json'"
            :class="['log-detail-tab', activeTab === 'json' ? 'is-active' : '']"
            @click="activeTab = 'json'">
            JSON
          </button>
        </div>
      </header>

      <div v-if="error && !detail" class="log-detail-state describe">{{ errorText }}</div>
      <div v-else-if="detail" class="log-detail-scroll">
        <div v-if="activeTab === 'overview'" class="log-detail-body">
          <section class="log-detail-section is-message">
            <div class="log-detail-section-title">{{ $t('modules.views.alarmCenter.eventDetail.s_a19a72d2') }}</div>
            <div :class="['log-detail-message-wrap', severityClass(detail.status)]">
              <div class="log-detail-message-rail" aria-hidden="true" />
              <pre :class="['log-detail-message', messageIsJson ? 'is-json' : '']">{{ messageDisplay }}</pre>
            </div>
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
                    <a
                      v-if="canViewTrace"
                      href="javascript:;"
                      class="log-detail-link"
                      @click.prevent.stop="onViewTrace">
                      {{ $t('modules.views.metrics.list.s_607e7a4f') }}
                    </a>
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
                <div class="log-detail-k is-code">{{ item.key }}</div>
                <div class="log-detail-v log-detail-mono">{{ item.value }}</div>
              </div>
            </div>
          </section>

          <section v-if="resourceItems.length" class="log-detail-section">
            <div class="log-detail-section-title">{{ $t('modules.views.appMonitor.logs.s_resources') }}</div>
            <div class="log-detail-kv-list">
              <div v-for="item in resourceItems" :key="`res-${item.key}`" class="log-detail-kv">
                <div class="log-detail-k is-code">{{ item.key }}</div>
                <div class="log-detail-v log-detail-mono">{{ item.value }}</div>
              </div>
            </div>
          </section>
        </div>

        <div v-else class="log-detail-body">
          <pre class="log-detail-json">{{ jsonText }}</pre>
        </div>
      </div>
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

const TIME_FMT = 'YYYY-MM-DD HH:mm:ss';

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

  get severityLabel () {
    const status = this.detail?.status || '';
    const number = this.detail?.severityNumber;
    if (number) {
      return `${status || '-'} · ${number}`;
    }
    return status || '-';
  }

  get eventTimeText () {
    return this.formatTime(this.detail?.timestamp)
      || this.formatNanoTime(this.detail?.timeNs);
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
      { key: 'timeNs', label: 'time_unix_nano', value: this.formatNanoTime(detail.timeNs), mono: true },
      { key: 'observedTimeNs', label: 'observed_time_unix_nano', value: this.formatNanoTime(detail.observedTimeNs), mono: true },
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
      timestamp: this.formatTime(this.detail.timestamp),
      time_unix_nano: this.formatNanoTime(this.detail.timeNs) || '',
      observed_time_unix_nano: this.formatNanoTime(this.detail.observedTimeNs) || '',
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

  /** Epoch millis / ms-string → `YYYY-MM-DD HH:mm:ss`. */
  private formatTime (value: any) {
    const ms = this.toMillis(value);
    return ms ? dayjs(ms).format(TIME_FMT) : '';
  }

  /** OTel nano timestamp string → `YYYY-MM-DD HH:mm:ss` (no JS precision loss). */
  private formatNanoTime (value: any) {
    const text = value == null ? '' : String(value).trim();
    if (!text || !/^\d+$/.test(text)) {
      return '';
    }
    // ns → ms: drop last 6 digits
    const msText = text.length > 6 ? text.slice(0, -6) : text;
    const ms = Number(msText);
    if (!Number.isFinite(ms) || ms <= 0) {
      return '';
    }
    return dayjs(ms).format(TIME_FMT);
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
  background: var(--bg-color-base);
}

.log-detail-header {
  flex: none;
  padding: 18px 24px 0;
  background: var(--bg-color);
  border-bottom: 1px solid var(--border-color-light);

  /* Soft severity wash — warn/error only; INFO stays neutral */
  &.is-warn {
    background: rgba(247, 149, 50, 0.05);
  }

  &.is-error {
    background: rgba(225, 40, 40, 0.04);
  }
}

:root[data-theme=dark] {
  .log-detail-header.is-warn {
    background: rgba(247, 149, 50, 0.08);
  }

  .log-detail-header.is-error {
    background: rgba(225, 40, 40, 0.08);
  }
}

.log-detail-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.log-detail-title-wrap {
  min-width: 0;
  flex: 1;
}

.log-detail-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  line-height: 24px;
  color: var(--color-text-primary);
}

.log-detail-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  min-width: 0;
}

.log-detail-summary-text {
  font-size: 13px;
  line-height: 20px;
  color: var(--color-text-regular);

  &.is-muted {
    color: var(--color-text-secondary);
  }

  &.is-mono {
    font-variant-numeric: tabular-nums;
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;
  }
}

.log-detail-summary-sep {
  color: var(--color-text-placeholder);
  font-size: 12px;
  line-height: 18px;
}

.log-detail-close {
  appearance: none;
  border: none;
  flex: none;
  width: 28px;
  height: 28px;
  margin-top: -2px;
  border-radius: 6px;
  background: var(--bg-color03);
  color: var(--color-text-secondary);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;

  &:hover {
    color: var(--color-text-primary);
    background: var(--bg-color02);
  }
}

.log-detail-tabs {
  display: flex;
  gap: 24px;
  margin-top: 14px;
}

.log-detail-tab {
  appearance: none;
  border: none;
  background: transparent;
  padding: 0 0 12px;
  font-size: 13px;
  line-height: 20px;
  color: var(--color-text-secondary);
  cursor: pointer;
  position: relative;

  &.is-active {
    color: var(--color-primary);
    font-weight: 600;

    &::after {
      content: '';
      position: absolute;
      left: 0;
      right: 0;
      bottom: 0;
      height: 2px;
      background: var(--color-primary);
      border-radius: 1px 1px 0 0;
    }
  }

  &:hover:not(.is-active) {
    color: var(--color-text-primary);
  }
}

.log-detail-state {
  padding: 40px 24px;
  font-size: 13px;
}

.log-detail-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 20px 24px 28px;
}

.log-detail-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.log-detail-section {
  padding: 14px 16px 16px;
  background: var(--bg-color);
  border: 1px solid var(--border-color-light);
  border-radius: 8px;

  &.is-message {
    padding-bottom: 14px;
  }
}

.log-detail-section-title {
  margin: 0 0 10px;
  font-size: 13px;
  font-weight: 600;
  line-height: 18px;
  color: var(--color-text-primary);
}

.log-detail-message-wrap {
  display: flex;
  align-items: stretch;
  min-width: 0;
  border-radius: 6px;
  overflow: hidden;
  background: var(--bg-color03);
  border: 1px solid var(--border-color-extra-light);

  &.is-info .log-detail-message-rail {
    background: var(--color-success, #08be7e);
  }

  &.is-warn .log-detail-message-rail {
    background: var(--color-warning, #f79532);
  }

  &.is-error .log-detail-message-rail {
    background: var(--color-danger, #e12828);
  }

  &.is-muted .log-detail-message-rail {
    background: var(--color-text-secondary, #b5b7bb);
  }
}

.log-detail-message-rail {
  width: 3px;
  flex: none;
  background: var(--color-text-secondary, #b5b7bb);
}

.log-detail-message,
.log-detail-json {
  margin: 0;
  padding: 12px 14px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: normal;
  font-size: 12px;
  line-height: 1.65;
  color: var(--color-text-primary);
  background: transparent;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;
}

.log-detail-message {
  flex: 1;
  min-width: 0;
}

.log-detail-message.is-json,
.log-detail-json {
  white-space: pre;
  overflow: auto;
}

.log-detail-json {
  max-height: calc(100vh - 180px);
  border: 1px solid var(--border-color-extra-light);
  border-radius: 6px;
  background: var(--bg-color03);
  color: var(--color-text-primary);
}

.log-detail-kv-list {
  display: flex;
  flex-direction: column;
}

.log-detail-kv {
  display: grid;
  grid-template-columns: 148px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
  padding: 8px 0;
  font-size: 12px;
  line-height: 20px;

  & + & {
    border-top: 1px solid var(--border-color-extra-light);
  }
}

.log-detail-k {
  color: var(--color-text-regular);
  overflow-wrap: anywhere;

  &.is-code {
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;
    color: var(--color-text-regular);
  }
}

.log-detail-v {
  min-width: 0;
  color: var(--color-text-primary);
  overflow-wrap: anywhere;
}

.log-detail-mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;
  font-variant-numeric: tabular-nums;
  color: var(--color-text-primary);
}

.log-detail-link {
  margin-left: 8px;
  color: var(--color-text-link);
  text-decoration: none;
  font-size: 12px;
  font-weight: 500;

  &:hover {
    text-decoration: underline;
  }
}

.log-level-tag {
  display: inline-flex;
  align-items: center;
  padding: 0 8px;
  height: 20px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.02em;
  background-color: var(--bg-color03);
  color: var(--color-text-regular);

  &.is-error {
    color: var(--color-danger);
    background-color: rgba(225, 40, 40, 0.1);
  }
  &.is-warn {
    color: var(--color-warning);
    background-color: rgba(247, 149, 50, 0.12);
  }
  &.is-info {
    color: var(--color-success);
    background-color: rgba(8, 190, 126, 0.1);
  }

  &.is-muted {
    color: var(--color-text-secondary);
    background-color: var(--bg-color03);
  }
}
</style>

<style lang="scss">
.log-detail-drawer.el-drawer {
  box-shadow: -12px 0 32px rgba(18, 19, 23, 0.1);

  .el-drawer__body {
    padding: 0;
    height: 100%;
    overflow: hidden;
    background: var(--bg-color-base);
  }
}

:root[data-theme=dark] {
  .log-detail-drawer.el-drawer {
    box-shadow: -12px 0 32px rgba(0, 0, 0, 0.45);
  }
}
</style>
