<template>
  <div
    :class="[
      'thinking-card',
      {
        'is-running': !finished,
        'is-expanded': expanded,
        'is-finished': finished,
      },
    ]"
  >
    <div class="thinking-header cp" @click="toggleExpand">
      <div class="thinking-header-left">
        <span class="thinking-badge" :class="{ 'is-active': !finished }">
          <i class="el-icon-magic-stick"></i>
        </span>
        <span
          class="thinking-summary"
          :class="{ 'thinking-summary-active': !finished }"
        >
          {{ summaryText }}
        </span>
        <span v-if="detailItems.length" class="step-count">{{ detailItems.length }} {{ $t('modules.views.aiPlatform.chat.s_4a0ff510') }}</span>
      </div>
      <button type="button" class="thinking-toggle" @click.stop="toggleExpand">
        <span>{{ expanded ? $t('modules.components.text-expand.s_def9e98b') : $t('modules.components.collapse-tags.s_e2edde5a') }}</span>
        <i :class="expanded ? 'el-icon-arrow-up' : 'el-icon-arrow-down'"></i>
      </button>
    </div>

    <el-collapse-transition>
      <div v-show="expanded" class="thinking-body">
        <div
          v-for="(segment, segmentIndex) in expertSegments"
          :key="`segment_${segment.expertId}_${segmentIndex}`"
          class="thinking-expert-segment"
        >
          <div class="expert-segment-header">
            <span class="expert-segment-name">{{ resolveExpertLabel(segment.expertId) }}</span>
          </div>
          <div class="thinking-timeline">
            <div
              v-for="(entry, index) in segment.items"
              :key="resolveDetailKey(entry, resolveFlatIndex(segmentIndex, index))"
              :class="[
                'timeline-item',
                entry.type === 'tool' ? 'timeline-item-tool' : 'timeline-item-text',
              ]"
            >
              <div class="timeline-rail">
                <span
                  :class="[
                    'timeline-dot',
                    entry.type === 'tool' && entry.tool
                      ? `dot-${getToolDisplayStatus(entry.tool, resolveFlatIndex(segmentIndex, index))}`
                      : 'dot-text',
                  ]"
                ></span>
                <span
                  v-if="index < segment.items.length - 1 || segmentIndex < expertSegments.length - 1"
                  class="timeline-line"
                ></span>
              </div>

              <div class="timeline-content">
                <div
                  v-if="entry.type === 'text'"
                  :class="['detail-text', { 'detail-text-markdown': isReasoningText(entry), 'detail-text-deliverable': entry.deliverable }]"
                >
                  <!-- marked-view：推理文本原样渲染，禁止预处理 markdown -->
                  <marked-view
                    v-if="isReasoningText(entry)"
                    :data="entry.content || ''"
                    :show-copy="false"
                    class="detail-text-marked"
                  />
                  <template v-else>{{ entry.contentKey ? $t(entry.contentKey) : entry.content }}</template>
                </div>
                <div v-else-if="entry.type === 'tool' && entry.tool" class="tool-action-group">
                  <div
                    v-if="hasToolResult(entry.tool) && canViewToolParams(entry.tool)"
                    :class="['tool-call-btn', 'status-view']"
                    @click="openToolParams(entry.tool)"
                  >
                    <span class="tool-icon-wrap">
                      <i class="el-icon-document"></i>
                    </span>
                    <span class="tool-call-status">{{ $t('modules.views.aiPlatform.chat.s_6fa6500b') }}</span>
                    <span class="tool-call-divider"></span>
                    <span class="tool-call-name ell" :title="formatToolDisplayName(entry.tool)">{{ formatToolDisplayName(entry.tool) }}</span>
                    <span class="tool-call-duration" aria-hidden="true"></span>
                    <span class="tool-call-arrow">
                      <i class="el-icon-arrow-right"></i>
                    </span>
                  </div>
                  <div
                    v-if="hasToolResult(entry.tool)"
                    :class="['tool-call-btn', `status-${resolveResultStatus(entry.tool)}`]"
                    @click="openToolResult(entry.tool)"
                  >
                    <span class="tool-icon-wrap">
                      <i v-if="resolveResultStatus(entry.tool) === 'success'" class="el-icon-success"></i>
                      <i v-else class="el-icon-error"></i>
                    </span>
                    <span class="tool-call-status">{{ formatToolCallSummary(entry.tool) }}</span>
                    <span class="tool-call-divider"></span>
                    <span class="tool-call-name ell" :title="formatToolDisplayName(entry.tool)">{{ formatToolDisplayName(entry.tool) }}</span>
                    <span class="tool-call-duration">
                      {{ formatToolDurationText(entry.tool.durationMs) || '' }}
                    </span>
                    <span class="tool-call-arrow">
                      <i class="el-icon-arrow-right"></i>
                    </span>
                  </div>
                  <div
                    v-else
                    :class="['tool-call-btn', `status-${getToolDisplayStatus(entry.tool, resolveFlatIndex(segmentIndex, index))}`]"
                    @click="openToolDetail(entry.tool, resolveFlatIndex(segmentIndex, index))"
                  >
                    <span class="tool-icon-wrap">
                      <i v-if="getToolDisplayStatus(entry.tool, resolveFlatIndex(segmentIndex, index)) === 'running'" class="el-icon-setting"></i>
                      <i v-else class="el-icon-document"></i>
                    </span>
                    <span class="tool-call-status">{{ formatRunningToolSummary(entry.tool, resolveFlatIndex(segmentIndex, index)) }}</span>
                    <span class="tool-call-divider"></span>
                    <span class="tool-call-name ell" :title="formatToolDisplayName(entry.tool)">{{ formatToolDisplayName(entry.tool) }}</span>
                    <span class="tool-call-duration" aria-hidden="true"></span>
                    <span class="tool-call-arrow">
                      <i v-if="getToolDisplayStatus(entry.tool, resolveFlatIndex(segmentIndex, index)) === 'running'" class="el-icon-loading"></i>
                      <i v-else class="el-icon-arrow-right"></i>
                    </span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </el-collapse-transition>
  </div>
</template>

<script lang="ts">
import { Component, Prop, Vue, Watch } from 'vue-property-decorator';
import i18n from '@/i18n';
import MarkedView from '@/components/marked-view.vue';
import type { ThinkingDetailItem, ToolCallItem } from '../types/thinking';
import { buildThinkingExpertSegments, type ThinkingExpertSegment } from '../utils/expert-segments';
import { formatToolCallDisplayName } from '../utils/tool-detail-format';
import { formatToolDurationText } from '../utils/thinking-detail';

type ToolDisplayStatus = 'running' | 'view' | 'success' | 'failed';
type ExpertNameResolver = (expertId?: string) => string;

@Component({
  name: 'ThinkingProcess',
  components: { MarkedView },
})
export default class ThinkingProcess extends Vue {
  @Prop({ default: 0 })
  private durationMs!: number;

  @Prop({ default: true })
  private finished!: boolean;

  @Prop({ default: () => [] })
  private detailItems!: ThinkingDetailItem[];

  @Prop({ default: false })
  private defaultExpanded!: boolean;

  @Prop({ default: true })
  private showToggle!: boolean;

  @Prop({ type: Function, default: null })
  private resolveExpertName!: ExpertNameResolver | null;

  private expanded = false;
  private detailKeySeed = 0;
  private detailKeyMap: WeakMap<object, string> = new WeakMap<object, string>();

  private get summaryText (): string {
    return this.finished
      ? i18n.t('modules.views.aiPlatform.chat.s_c5ec3754', { duration: this.durationText }) as string
      : i18n.t('modules.views.aiPlatform.chat.s_56d099bb') as string;
  }

  private get durationText (): string {
    const normalized = Number(this.resolvedDurationMs || 0);
    if (normalized <= 0) {
      return '0s';
    }
    if (normalized < 1000) {
      return `${normalized} ms`;
    }
    return `${Math.ceil(normalized / 1000)}s`;
  }

  private get resolvedDurationMs (): number {
    const propDurationMs = Number(this.durationMs || 0);
    return propDurationMs > 0 ? propDurationMs : this.getDurationFromDetailItems(this.detailItems);
  }

  private get expertSegments (): ThinkingExpertSegment[] {
    return buildThinkingExpertSegments(this.detailItems);
  }

  private resolveExpertLabel (expertId: string): string {
    if (typeof this.resolveExpertName === 'function') {
      return this.resolveExpertName(expertId);
    }
    return expertId;
  }

  private resolveFlatIndex (segmentIndex: number, itemIndex: number): number {
    let offset = 0;
    for (let index = 0; index < segmentIndex; index += 1) {
      offset += this.expertSegments[index]?.items.length || 0;
    }
    return offset + itemIndex;
  }

  @Watch('defaultExpanded', { immediate: true })
  private onDefaultExpandedChange (val: boolean) {
    this.expanded = Boolean(val);
  }

  @Watch('finished')
  private onFinishedChange (val: boolean) {
    if (val && this.defaultExpanded) {
      this.expanded = true;
    }
  }

  private getDurationFromDetailItems (detailItems: ThinkingDetailItem[]): number {
    const timestamps: number[] = [];
    (detailItems || []).forEach((item) => {
      const source = item?.tool?.sourceMessage || null;
      const raw = source?.ts || source?.timestamp || source?.updatedAt;
      if (!raw) {
        return;
      }
      const value = new Date(raw).getTime();
      if (Number.isFinite(value) && value > 0) {
        timestamps.push(value);
      }
    });
    if (timestamps.length < 2) {
      return 0;
    }
    return Math.max(0, Math.max(...timestamps) - Math.min(...timestamps));
  }

  private isReasoningText (entry: ThinkingDetailItem): boolean {
    const type = String(entry.messageType || '').toUpperCase()
    return type === 'REASONING' || Boolean(entry.deliverable)
  }

  private formatStatus (status: ToolDisplayStatus): string {
    if (status === 'view') return i18n.t('modules.views.aiPlatform.chat.s_6fa6500b') as string;
    if (status === 'success') return i18n.t('modules.views.aiPlatform.chat.s_242be0fe') as string;
    if (status === 'failed') return i18n.t('modules.views.aiPlatform.chat.s_c0d43a35') as string;
    return i18n.t('modules.views.aiPlatform.chat.s_347d167c') as string;
  }

  private getToolDisplayStatus (tool: ToolCallItem, index: number): ToolDisplayStatus {
    const status = tool?.status || 'running';
    const sourceType = String(tool?.sourceType || '').trim().toUpperCase();
    if (sourceType === 'TOOL_RESULT') {
      if (status === 'failed') {
        return 'failed';
      }
      if (status === 'success' || status === 'view') {
        return 'success';
      }
      return 'running';
    }
    if (status !== 'running') {
      return 'view';
    }
    const callId = String(tool?.callId || '').trim();
    if (!callId) {
      return 'running';
    }
    const hasCompletedResult = (this.detailItems || []).some((item, idx) => {
      if (idx === index || item?.type !== 'tool' || !item?.tool) {
        return false;
      }
      return String(item.tool.callId || '').trim() === callId
        && item.tool.status !== 'running';
    });
    return hasCompletedResult ? 'view' : 'running';
  }

  private resolveDetailKey (item: ThinkingDetailItem, index: number): string {
    if (item && typeof item === 'object') {
      const cached = this.detailKeyMap.get(item as object);
      if (cached) {
        return cached;
      }
      const type = String(item?.type || 'detail');
      let baseKey = '';
      if (type === 'tool') {
        const tool = item?.tool;
        baseKey = String(tool?.callId || tool?.id || tool?.name || '').trim();
      } else {
        baseKey = String(item?.content || '').trim().slice(0, 48);
      }
      this.detailKeySeed += 1;
      const generated = `${type}_${baseKey || 'detail'}_${this.detailKeySeed}`;
      this.detailKeyMap.set(item as object, generated);
      return generated;
    }
    return `detail_${index}`;
  }

  private formatResultStatus (tool: ToolCallItem): string {
    return this.resolveResultStatus(tool) === 'failed'
      ? i18n.t('modules.views.aiPlatform.chat.s_c0d43a35') as string
      : i18n.t('modules.views.aiPlatform.chat.s_242be0fe') as string;
  }

  private formatToolCallSummary (tool: ToolCallItem): string {
    return this.formatResultStatus(tool);
  }

  private formatRunningToolSummary (tool: ToolCallItem, index: number): string {
    const status = this.getToolDisplayStatus(tool, index);
    return this.formatStatus(status);
  }

  private resolveToolInput (tool: ToolCallItem): unknown {
    const callMeta = tool?.callMessage?.metadata || {};
    if (callMeta.toolInput !== undefined && String(callMeta.toolInput).trim()) {
      return callMeta.toolInput;
    }
    const resultMeta = tool?.resultMessage?.metadata || {};
    if (resultMeta.toolInput !== undefined && String(resultMeta.toolInput).trim()) {
      return resultMeta.toolInput;
    }
    const sourceMeta = tool?.sourceMessage?.metadata || {};
    if (sourceMeta.toolInput !== undefined && String(sourceMeta.toolInput).trim()) {
      return sourceMeta.toolInput;
    }
    return null;
  }

  private formatToolDisplayName (tool: ToolCallItem): string {
    return formatToolCallDisplayName(String(tool?.name || 'tool'), this.resolveToolInput(tool));
  }

  private formatToolDurationText = formatToolDurationText;

  private resolveResultStatus (tool: ToolCallItem): 'success' | 'failed' {
    if (tool?.status === 'failed') {
      return 'failed';
    }
    return 'success';
  }

  private hasToolResult (tool: ToolCallItem): boolean {
    return Boolean(tool?.resultMessage);
  }

  private canViewToolParams (tool: ToolCallItem): boolean {
    if (tool?.callMessage) {
      return true;
    }
    const metadata = tool?.resultMessage?.metadata || {};
    return metadata.toolInput !== undefined && String(metadata.toolInput).trim().length > 0;
  }

  private openToolParams (tool: ToolCallItem) {
    this.$emit('open-tool-detail', tool, 'params');
  }

  private openToolResult (tool: ToolCallItem) {
    this.$emit('open-tool-detail', tool, 'result');
  }

  private openToolDetail (tool: ToolCallItem, index: number) {
    this.$emit('open-tool-detail', tool, 'params');
  }

  private toggleExpand () {
    if (!this.showToggle) {
      return;
    }
    this.expanded = !this.expanded;
    this.$nextTick(() => {
      this.$emit('layout-change', this.$el);
    });
  }
}
</script>

<style lang="scss" scoped>
.thinking-card {
  margin-bottom: 14px;
  border-radius: 14px;
  border: 1px solid rgba(214, 224, 240, 0.95);
  background:
    linear-gradient(180deg, rgba(248, 251, 255, 0.98) 0%, rgba(255, 255, 255, 0.96) 100%);
  box-shadow:
    0 1px 2px rgba(15, 23, 42, 0.04),
    0 8px 24px rgba(41, 98, 255, 0.06);
  overflow: hidden;
  transition: border-color 0.25s ease, box-shadow 0.25s ease;

  &.is-running {
    border-color: rgba(41, 98, 255, 0.22);
    box-shadow:
      0 1px 2px rgba(15, 23, 42, 0.04),
      0 10px 28px rgba(41, 98, 255, 0.1);
  }

  &.is-expanded.is-finished {
    border-color: rgba(41, 98, 255, 0.14);
  }
}

.thinking-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 46px;
  padding: 10px 14px;
  user-select: none;
}

.thinking-header-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.thinking-badge {
  flex: none;
  width: 28px;
  height: 28px;
  border-radius: 9px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #2962ff;
  background: linear-gradient(135deg, rgba(41, 98, 255, 0.14), rgba(99, 216, 255, 0.12));
  border: 1px solid rgba(41, 98, 255, 0.12);
  font-size: 14px;
  transition: transform 0.3s ease, box-shadow 0.3s ease;

  &.is-active {
    animation: badge-pulse 2.4s ease-in-out infinite;
    box-shadow: 0 0 0 4px rgba(41, 98, 255, 0.08);
  }
}

@keyframes badge-pulse {
  0%, 100% {
    transform: scale(1);
    box-shadow: 0 0 0 4px rgba(41, 98, 255, 0.08);
  }
  50% {
    transform: scale(1.04);
    box-shadow: 0 0 0 6px rgba(41, 98, 255, 0.04);
  }
}

.thinking-summary {
  color: #526079;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.01em;
}

.thinking-summary-active {
  color: transparent;
  -webkit-text-fill-color: transparent;
  background-image: linear-gradient(
    90deg,
    #526079 0%,
    #526079 24%,
    #8b95a8 32%,
    #c8ced8 40%,
    #8b95a8 48%,
    #526079 66%,
    #526079 100%
  );
  background-size: 300% 100%;
  background-repeat: no-repeat;
  background-position: 0 0;
  -webkit-background-clip: text;
  background-clip: text;
  animation: thinking-shimmer 1.8s linear infinite;
}

@keyframes thinking-shimmer {
  0% { background-position: 100% 0; }
  100% { background-position: 0 0; }
}

.step-count {
  flex: none;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  font-size: 11px;
  font-weight: 600;
  color: #2962ff;
  background: rgba(41, 98, 255, 0.08);
  border: 1px solid rgba(41, 98, 255, 0.1);
}

.thinking-toggle {
  flex: none;
  height: 28px;
  padding: 0 10px;
  border: 1px solid rgba(214, 224, 240, 0.95);
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #64748b;
  font-size: 12px;
  background: rgba(255, 255, 255, 0.88);
  cursor: pointer;
  transition: all 0.2s ease;

  &:hover {
    color: #2962ff;
    border-color: rgba(41, 98, 255, 0.24);
    background: #fff;
  }
}

.thinking-body {
  padding: 2px 14px 14px;
}

.thinking-expert-segment {
  & + & {
    margin-top: 14px;
    padding-top: 12px;
    border-top: 1px dashed rgba(214, 224, 240, 0.95);
  }
}

.expert-segment-header {
  margin-bottom: 8px;
  padding-left: 2px;
}

.expert-segment-name {
  display: inline-flex;
  align-items: center;
  color: #1e293b;
  font-size: 12px;
  font-weight: 700;
  line-height: 20px;

  &::before {
    content: '';
    display: inline-block;
    width: 7px;
    height: 7px;
    margin-right: 8px;
    border-radius: 999px;
    vertical-align: 1px;
    background: linear-gradient(135deg, #2962ff, #63d8ff);
    box-shadow: 0 0 0 3px rgba(41, 98, 255, 0.12);
  }
}

.thinking-timeline {
  padding-top: 2px;
}

.timeline-item {
  display: flex;
  gap: 12px;
  min-height: 0;

  & + & {
    margin-top: 2px;
  }
}

.timeline-rail {
  flex: none;
  width: 16px;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 8px;
}

.timeline-dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #cbd5e1;
  box-shadow: 0 0 0 3px #fff;
  position: relative;
  z-index: 1;

  &.dot-text {
    background: #94a3b8;
  }

  &.dot-running {
    background: #2962ff;
    box-shadow: 0 0 0 3px #fff, 0 0 0 5px rgba(41, 98, 255, 0.18);
    animation: dot-breathe 1.6s ease-in-out infinite;
  }

  &.dot-view,
  &.dot-success {
    background: #08be7e;
  }

  &.dot-failed {
    background: #ef4444;
  }
}

@keyframes dot-breathe {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.15); }
}

.timeline-line {
  flex: 1;
  width: 2px;
  min-height: 12px;
  margin: 4px 0;
  border-radius: 999px;
  background: linear-gradient(180deg, rgba(41, 98, 255, 0.18), rgba(41, 98, 255, 0.04));
}

.timeline-content {
  flex: 1;
  min-width: 0;
  padding: 2px 0 14px;
}

.timeline-item:last-child .timeline-content {
  padding-bottom: 4px;
}

.detail-text {
  padding: 10px 12px 10px 14px;
  border-radius: 10px;
  color: #64748b;
  font-size: 13px;
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
  background: rgba(241, 245, 249, 0.72);
  border: 1px solid rgba(226, 232, 240, 0.9);
  position: relative;

  &::before {
    content: '';
    position: absolute;
    top: 10px;
    bottom: 10px;
    left: 0;
    width: 3px;
    border-radius: 0 3px 3px 0;
    background: linear-gradient(180deg, #2962ff, rgba(99, 216, 255, 0.65));
  }

  &.detail-text-markdown {
    white-space: normal;
  }
}

.detail-text-marked {
  color: #64748b;
  font-size: 13px;
  line-height: 1.75;

  :deep(p) {
    margin: 0 0 10px;
  }

  :deep(p:last-child),
  :deep(ul:last-child),
  :deep(ol:last-child) {
    margin-bottom: 0;
  }

  :deep(ul),
  :deep(ol) {
    margin: 8px 0 12px;
    padding-left: 18px;
  }

  :deep(li) {
    margin: 4px 0;
    padding-left: 4px;
  }

  :deep(strong) {
    color: #475569;
    font-weight: 700;
  }

  :deep(code) {
    margin: 0 2px;
    padding: 2px 6px;
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    color: #475569;
    background: rgba(255, 255, 255, 0.72);
  }

  :deep(pre) {
    margin: 10px 0;
    border-radius: 8px;
    overflow: hidden;
  }

  :deep(pre code) {
    border: none;
    border-radius: 8px;
    background: #1f2937;
    color: #d7dee8;
  }

  :deep(table) {
    margin: 10px 0;
    border: 1px solid #e5edf7;
    border-radius: 8px;
    overflow: hidden;
  }

  :deep(th) {
    background: rgba(255, 255, 255, 0.72);
    font-weight: 700;
  }
}

.tool-action-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tool-call-btn {
  max-width: 100%;
  display: grid;
  grid-template-columns: 28px auto 1px minmax(0, 1fr) 52px 18px;
  column-gap: 10px;
  align-items: center;
  min-height: 42px;
  padding: 8px 12px 8px 10px;
  border-radius: 11px;
  border: 1px solid rgba(226, 232, 240, 0.95);
  font-size: 13px;
  cursor: pointer;
  user-select: none;
  color: #334155;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.04);
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease, background-color 0.18s ease;

  &:hover {
    transform: translateY(-1px);
    border-color: rgba(41, 98, 255, 0.22);
    box-shadow: 0 6px 16px rgba(41, 98, 255, 0.1);
    background: #fff;
  }

  &.status-running {
    border-color: rgba(41, 98, 255, 0.2);
    background: linear-gradient(135deg, rgba(41, 98, 255, 0.06), rgba(255, 255, 255, 0.96));
  }

  &.status-success {
    border-color: rgba(8, 190, 126, 0.22);
    background: linear-gradient(135deg, rgba(8, 190, 126, 0.05), rgba(255, 255, 255, 0.96));
  }

  &.status-view {
    border-color: rgba(41, 98, 255, 0.16);
  }

  &.status-failed {
    border-color: rgba(239, 68, 68, 0.22);
    background: linear-gradient(135deg, rgba(239, 68, 68, 0.04), rgba(255, 255, 255, 0.96));
  }
}

.tool-icon-wrap {
  flex: none;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  background: #f1f5f9;
  color: #64748b;

  .status-running & {
    color: #2962ff;
    background: rgba(41, 98, 255, 0.1);
  }

  .status-success & {
    color: #08be7e;
    background: rgba(8, 190, 126, 0.1);
  }

  .status-view & {
    color: #2962ff;
    background: rgba(41, 98, 255, 0.08);
  }

  .status-failed & {
    color: #ef4444;
    background: rgba(239, 68, 68, 0.08);
  }
}

.tool-call-status {
  white-space: nowrap;
  font-weight: 600;
  color: #475569;
}

.tool-call-duration {
  justify-self: end;
  min-width: 0;
  font-size: 12px;
  font-weight: 500;
  color: #94a3b8;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.tool-call-divider {
  width: 1px;
  height: 14px;
  justify-self: center;
  background: #dbe3ef;
}

.tool-call-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.01em;
  color: #1e293b;
}

.tool-call-arrow {
  justify-self: end;
  width: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #94a3b8;
  font-size: 13px;

  .status-running & {
    color: #2962ff;
  }
}
</style>
