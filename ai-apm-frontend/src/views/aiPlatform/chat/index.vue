<template>
  <div
    v-loading="bootLoading"
    :class="['chat-page', { 'chat-started': conversationStarted }]"
  >
    <div class="header-fixed">
      <div class="header-actions is-left">
        <el-tooltip :content="$t('modules.views.aiPlatform.chat.s_de0a387d')">
          <i class="el-icon-time header-icon" @click="showSessionDrawer = true"></i>
        </el-tooltip>
        <el-tooltip :content="$t('modules.views.aiPlatform.chat.s_611f2696')">
          <i class="el-icon-circle-plus-outline header-icon" @click="newSession"></i>
        </el-tooltip>
      </div>
    </div>

    <div
      ref="messageBox"
      :class="['chat-layout', { 'chat-layout-scroll': conversationStarted }]"
      @scroll="onMessageScroll"
    >
      <div class="chat-card">
        <div :class="['chat-main', { 'chat-main-idle': !conversationStarted }]">
          <div
            :class="['header-hero', { 'header-hero-hidden': conversationStarted }]"
          >
            <div class="hero-visual">
              <span class="hero-ring hero-ring-one"></span>
              <span class="hero-ring hero-ring-two"></span>
              <img :src="headerLogo" alt="logo" class="header-logo" />
            </div>
            <div class="header-user-meta">
              <div class="welcome-text">{{ greetingText }}</div>
              <div class="service-stats">
                {{ $t('modules.views.aiPlatform.chat.s_cd72989a') }}
                <span class="service-count">{{ sessionTotal }}</span>
                {{ $t('modules.views.aiPlatform.chat.s_fca1902e') }}
                <template v-if="tasks.length">
                  {{ $t('modules.views.aiPlatform.chat.s_3b1b39eb') }}
                  <span class="service-count">{{ tasks.length }}</span>
                  {{ $t('modules.views.aiPlatform.chat.s_f8d58d67') }}
                </template>
              </div>
            </div>
          </div>

          <div
            v-show="conversationStarted"
            class="message-list"
          >
            <div
              v-for="msg in renderMessages"
              :key="msg.messageId || msg._key"
              :class="['char-item', msg.role === 'user' ? 'ask-item' : 'answer-item', { 'last-answer': msg.isLastAssistant }]"
            >
              <div v-if="msg.role !== 'user'" class="message-avatar assistant-avatar">
                AI
              </div>
              <div class="message-stack">
                <div class="message-meta">
                  <span class="message-author">{{ messageAuthor(msg) }}</span>
                  <span class="message-time">{{ messageTime(msg) }}</span>
                </div>
                <div
                  :class="[
                    'item-content',
                    {
                      'has-thinking': msg.role === 'assistant' && messageThinkingDetails(msg).length,
                      'has-answer': msg.role === 'assistant' && String(msg.displayContent || '').trim(),
                      'has-chart': msg.role === 'assistant' && messageTrendCharts(msg).length,
                    },
                  ]"
                >
                  <thinking-process
                    v-if="msg.role === 'assistant' && messageThinkingDetails(msg).length"
                    :detail-items="messageThinkingDetails(msg)"
                    :finished="isThinkingFinished(msg)"
                    :default-expanded="isThinkingExpanded(msg)"
                    :duration-ms="thinkingDurationMs(msg)"
                    :show-toggle="true"
                    :resolve-expert-name="expertDisplayName"
                    @open-tool-detail="(tool, view) => openToolDetail(tool, view)"
                    @layout-change="centerThinkingProcess"
                  />
                  <ai-trend-charts
                    v-if="msg.role === 'assistant' && messageTrendCharts(msg).length"
                    :charts="messageTrendCharts(msg)"
                    class="answer-charts"
                  />
                  <div
                    v-if="msg.role === 'assistant' && String(msg.displayContent || '').trim()"
                    class="answer-body"
                  >
                  <!-- marked-view：LLM 原文直出，禁止预处理 markdown -->
                    <marked-view
                      :data="msg.displayContent"
                      :show-copy="false"
                      class="item-marked-cont"
                    />
                  </div>
                  <div v-else class="plain-text">{{ msg.displayContent }}</div>
                  <div v-if="messageAttachments(msg).length" class="message-attachments">
                    <div
                      v-for="(att, attIndex) in messageAttachments(msg)"
                      :key="`${msg.messageId}_${attIndex}`"
                      class="message-attachment-item"
                    >
                      <el-image
                        v-if="att.type === 'image' && att.dataUrl"
                        :src="att.dataUrl"
                        fit="cover"
                        class="message-attachment-image"
                        :preview-src-list="[att.dataUrl]"
                      />
                      <div v-else class="message-attachment-file">
                        <i class="el-icon-document"></i>
                        <span>{{ att.name || $t('modules.views.aiPlatform.chat.s_c9a6ee90') }}</span>
                        <el-button
                          v-if="att.filePath && activeSessionId"
                          type="text"
                          size="mini"
                          icon="el-icon-download"
                          @click="downloadWorkspaceFile(att)"
                        />
                      </div>
                    </div>
                  </div>
                  <div v-if="messageGeneratedFiles(msg).length" class="message-generated-files">
                    <div class="generated-files-title">{{ $t('modules.views.aiPlatform.chat.s_a13d8dff') }}</div>
                    <div
                      v-for="(file, fileIndex) in messageGeneratedFiles(msg)"
                      :key="`${msg.messageId}_gen_${fileIndex}`"
                      class="generated-file-item cp"
                      @click="downloadWorkspaceFile(file)"
                    >
                      <i class="el-icon-download"></i>
                      <span class="generated-file-name">{{ file.name || file.filePath }}</span>
                      <span v-if="file.size" class="generated-file-size">{{ formatFileSize(file.size) }}</span>
                    </div>
                  </div>
                </div>
              </div>
              <div v-if="msg.role === 'user'" class="message-avatar user-avatar">
                {{ userAvatarText }}
              </div>
            </div>

            <div v-if="showThinking" class="char-item answer-item last-answer">
              <div class="message-avatar assistant-avatar">
                AI
              </div>
              <div class="message-stack thinking-stack">
                <div
                  v-if="currentRoundThinkingDetails.length"
                  class="item-content has-thinking"
                >
                  <thinking-process
                    :detail-items="currentRoundThinkingDetails"
                    :finished="false"
                    :default-expanded="true"
                    :duration-ms="currentRoundThinkingDurationMs"
                    :resolve-expert-name="expertDisplayName"
                    @open-tool-detail="(tool, view) => openToolDetail(tool, view)"
                    @layout-change="centerThinkingProcess"
                  />
                </div>
                <div
                  v-else
                  class="expert-process-segment"
                >
                  <div class="message-meta">
                    <span class="message-author">{{ expertDisplayName(expertId) }}</span>
                    <span class="message-time">{{ currentTimeText }}</span>
                  </div>
                  <div class="item-content loading-content">
                    <div class="inline-thinking-loader">
                      <span class="loader-badge">
                        <span class="chat-loading"></span>
                      </span>
                      <span class="loader-text">{{ $t('modules.views.aiPlatform.chat.s_56d099bb') }}</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div class="footer">
            <el-alert
              v-if="!llmReady"
              type="warning"
              :closable="false"
              show-icon
              class="llm-alert"
            >
              <span>{{ $t('modules.views.aiPlatform.chat.s_68dacfee') }}</span>
              <router-link to="/config/llm" class="llm-alert-link">{{ $t('modules.views.aiPlatform.chat.s_1202c60b') }}</router-link>
            </el-alert>
            <div :class="['input-wrap', { 'has-upload': uploadItems.length }]">
              <div class="input-effect" />
              <div class="input-area">
                <chat-upload-preview
                  v-if="uploadItems.length"
                  :items="uploadPreviewItems"
                  @remove="removeUploadItem"
                />
                <el-input
                  v-model="draft"
                  type="textarea"
                  :autosize="{ minRows: 3, maxRows: 8 }"
                  :disabled="sending"
                  :maxlength="maxInputLength"
                  :placeholder="$t('modules.views.aiPlatform.chat.s_41845b76')"
                  class="input-chat"
                  @paste.native="handlePaste"
                />
                <div class="composer-actions">
                  <el-dropdown
                    trigger="click"
                    class="upload-dropdown"
                    :disabled="sending || serverRunning"
                    @command="handleUploadCommand"
                  >
                    <span :class="['upload-add-btn', sending || serverRunning ? 'cn' : 'cp']">
                      <i class="el-icon-plus"></i>
                    </span>
                    <el-dropdown-menu slot="dropdown">
                      <el-dropdown-item command="file">
                        {{ $t('modules.views.aiPlatform.chat.s_a6fc9e3a') }}
                        <el-tooltip effect="light" placement="top" :content="uploadFileTooltipText">
                          <i class="el-icon-info upload-tip-icon"></i>
                        </el-tooltip>
                      </el-dropdown-item>
                      <el-dropdown-item command="image">{{ $t('modules.views.aiPlatform.chat.s_ce68551b') }}</el-dropdown-item>
                    </el-dropdown-menu>
                  </el-dropdown>
                  <el-divider direction="vertical" class="composer-divider" />
                  <div class="composer-actions-center flex-1">
                    <el-select
                      v-model="selectedModelKey"
                      size="mini"
                      class="chat-model-select"
                      popper-class="chat-model-select-popper"
                      :disabled="sending || serverRunning || !modelOptions.length"
                      filterable
                      :placeholder="$t('modules.views.aiPlatform.chat.s_0e34a1fe')"
                      @change="handleModelChange"
                    >
                      <el-option
                        v-for="item in modelOptions"
                        :key="item.key"
                        :label="item.label"
                        :value="item.key"
                      >
                        <div class="model-option">
                          <span class="model-option-provider">{{ item.providerName }}</span>
                          <span class="model-option-name">{{ item.modelLabel }}</span>
                        </div>
                      </el-option>
                    </el-select>
                    <span
                      v-for="item in inlineQuickExperts"
                      :key="item.expertId"
                      :class="['expert-trigger-btn', { 'is-active': expertId === item.expertId, 'is-disabled': sending || serverRunning }]"
                      @click="toggleQuickExpert(item.expertId)"
                    >
                      <i :class="item.icon"></i>
                      <span class="expert-trigger-btn-text">{{ expertDisplayName(item.expertId) }}</span>
                    </span>
                    <el-popover
                      v-if="quickExpertGroups.hasOverflow"
                      v-model="showExpertOverflowPopover"
                      trigger="click"
                      placement="top-start"
                      popper-class="chat-expert-overflow-popover"
                      :disabled="sending || serverRunning"
                      @show="resetExpertOverflowKeyword"
                    >
                      <div class="expert-overflow-panel">
                        <el-input
                          v-model="expertOverflowKeyword"
                          size="small"
                          prefix-icon="el-icon-search"
                          clearable
                          :placeholder="$t('modules.components.query-filter.components.s_e5f71fc3')"
                          @click.native.stop
                        />
                        <div class="expert-overflow-list">
                          <div
                            v-for="item in filteredOverflowExperts"
                            :key="item.expertId"
                            :class="['expert-overflow-item', { 'is-active': expertId === item.expertId }]"
                            @click="selectOverflowExpert(item.expertId)"
                          >
                            <i :class="item.icon"></i>
                            <span class="expert-overflow-item-text">{{ expertDisplayName(item.expertId) }}</span>
                          </div>
                          <div v-if="!filteredOverflowExperts.length" class="expert-overflow-empty">
                            {{ $t('modules.views.aiPlatform.experts.s_0ab9d46f') }}
                          </div>
                        </div>
                      </div>
                      <span
                        slot="reference"
                        :class="['expert-trigger-btn', { 'is-active': isOverflowExpertActive, 'is-disabled': sending || serverRunning }]"
                      >
                        <i class="el-icon-user"></i>
                        <span class="expert-trigger-btn-text">{{ $t('modules.views.aiPlatform.experts.s_95e85417') }}</span>
                      </span>
                    </el-popover>
                  </div>
                  <div class="composer-actions-right">
                    <div class="input-count">{{ draft.length }}/{{ maxInputLength }}</div>
                    <img
                      v-if="sending || serverRunning"
                      :src="sendStopIcon"
                      class="send-icon cp"
                      width="28"
                      :alt="$t('modules.views.aiPlatform.chat.s_c6ae3469')"
                      @click="abortChat"
                    />
                    <img
                      v-else-if="!canSend"
                      :src="sendIcon"
                      class="send-icon cn"
                      width="28"
                      :alt="$t('modules.views.aiPlatform.chat.s_1535fcfa')"
                    />
                    <img
                      v-else
                      :src="sendActiveIcon"
                      class="send-icon cp"
                      width="28"
                      :alt="$t('modules.views.aiPlatform.chat.s_1535fcfa')"
                      @click="sendMessage"
                    />
                  </div>
                </div>
              </div>
            </div>
            <input
              ref="uploadFileInput"
              type="file"
              multiple
              :accept="allowedFileAccept"
              class="hidden-file-input"
              @change="handleFileInputChange($event, 'file')"
            />
            <input
              ref="uploadImageInput"
              type="file"
              multiple
              accept="image/*"
              class="hidden-file-input"
              @change="handleFileInputChange($event, 'image')"
            />

            <div v-if="!conversationStarted" class="suggestion-panel">
              <div class="suggestion-head">
                <span class="suggestion-title">{{ $t('modules.views.aiPlatform.chat.s_b87b8b60') }}</span>
                <el-button type="text" size="mini" icon="el-icon-refresh" @click="changeSuggestionBatch">{{ $t('modules.views.aiPlatform.chat.s_0e0b0ddf') }}</el-button>
              </div>
              <div class="suggestion-list">
                <div
                  v-for="(item, index) in visibleSuggestions"
                  :key="`${suggestionCursor}_${item.labelKey}`"
                  class="suggestion-item cp"
                  :style="{ animationDelay: `${index * 60}ms` }"
                  @click="fillSuggestion(item)"
                >
                  <span class="suggestion-index">{{ index + 1 }}</span>
                  <span class="suggestion-copy">{{ $t(item.labelKey) }}</span>
                  <i class="el-icon-arrow-right"></i>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <el-drawer
      :visible.sync="showSessionDrawer"
      append-to-body
      size="320px"
      custom-class="ai-chat-drawer"
    >
      <template slot="title">
        <div class="drawer-title">
          <img :src="chatStarIcon" class="drawer-star" alt="" />
          {{ $t('modules.views.aiPlatform.chat.s_de0a387d') }}
        </div>
      </template>
      <div class="drawer-toolbar">
        <el-button type="primary" size="small" icon="el-icon-plus" @click="handleNewSessionFromDrawer">{{ $t('modules.views.aiPlatform.chat.s_611f2696') }}</el-button>
        <el-button size="small" icon="el-icon-refresh" @click="reloadSessions">{{ $t('modules.views.aiPlatform.chat.s_694fc5ef') }}</el-button>
      </div>
      <div
        v-loading="sessionListLoading && !sessions.length"
        class="session-list"
        v-infinite-scroll="() => loadMoreSessions()"
        :infinite-scroll-disabled="sessionListNoMore || sessionListLoading"
        :infinite-scroll-distance="50"
      >
        <div
          v-for="item in sessions"
          :key="item.sessionId"
          :class="['session-item', { active: item.sessionId === activeSessionId }]"
          @click="selectSessionFromDrawer(item.sessionId)"
        >
          <div class="session-title">{{ item.title || $t('modules.views.aiPlatform.chat.s_1ac07a4b') }}</div>
          <div class="session-meta">{{ expertDisplayName(item.expertId || expertId) }}</div>
        </div>
        <div v-if="!sessions.length && !sessionListLoading" class="empty-tip">{{ $t('modules.views.aiPlatform.chat.s_2bc4c0ed') }}</div>
      </div>
    </el-drawer>

    <el-dialog
      :visible.sync="showToolDetailDialog"
      append-to-body
      width="680px"
      custom-class="tool-detail-dialog"
    >
      <template slot="title">
        <div class="drawer-title tool-detail-title">
          <div class="tool-detail-title-main">
            {{ selectedToolDetailTitle }}
            <template v-if="selectedToolName">
              <span class="tool-detail-sep">|</span>
              <span class="tool-detail-name">{{ selectedToolName }}</span>
            </template>
            <template v-if="selectedToolDurationText">
              <span class="tool-detail-sep">|</span>
              <span class="tool-detail-duration">{{ selectedToolDurationText }}</span>
            </template>
            <template v-if="selectedToolStatusLabel">
              <span class="tool-detail-sep">|</span>
              <span :class="['tool-detail-status', selectedToolStatusClass]">{{ selectedToolStatusLabel }}</span>
            </template>
          </div>
          <el-button
            size="mini"
            icon="el-icon-document-copy"
            class="tool-detail-copy"
            @click="copySelectedToolDetail"
          >
            {{ $t('modules.views.aiPlatform.chat.s_79d3abe9') }}
          </el-button>
        </div>
      </template>
      <ai-trend-charts
        v-if="selectedToolCharts.length"
        :charts="selectedToolCharts"
        class="tool-detail-charts"
      />
      <div v-else-if="selectedToolTerminalDisplay" class="tool-detail-terminal">
        <div v-if="selectedToolTerminalDisplay.meta" class="tool-detail-terminal-meta is-prefix">{{ selectedToolTerminalDisplay.meta }}</div>
        <pre class="tool-detail-content is-terminal">{{ selectedToolTerminalDisplay.body }}</pre>
      </div>
      <div v-else-if="selectedToolBashParamsDisplay" class="tool-detail-terminal">
        <pre class="tool-detail-content is-terminal">{{ selectedToolBashParamsDisplay.command }}</pre>
        <div v-if="selectedToolBashParamsDisplay.meta" class="tool-detail-terminal-meta">{{ selectedToolBashParamsDisplay.meta }}</div>
      </div>
      <pre v-else :class="['tool-detail-content', { 'is-terminal': selectedToolIsTerminal }]">{{ selectedToolDetail }}</pre>
    </el-dialog>
  </div>
</template>

<script lang="ts">
import dayjs from 'dayjs';
import i18n from '@/i18n';
import { Vue, Component, Watch } from 'vue-property-decorator';
import { v4 as uuidv4 } from 'uuid';
import MarkedView from '@/components/marked-view.vue';
import ChatUploadPreview from './ChatUploadPreview.vue';
import ThinkingProcess from './components/ThinkingProcess.vue';
import AiTrendCharts from './components/AiTrendCharts.vue';
import { buildThinkingDetailItems, formatToolDurationText } from './utils/thinking-detail';
import {
  formatTerminalToolDisplay,
  formatBashToolParamsDisplay,
  isTerminalToolName,
  normalizeToolValue,
  stringifyToolValue,
} from './utils/tool-detail-format';
import {
  allUserRoundsHaveFinalAnswer,
  hasVisibleAssistantAnswerForRound,
  isProcessMessageType,
  isRoundFinalText,
} from './utils/message-flags';
import type { ToolCallItem } from './types/thinking';
import {
  ALLOWED_FILE_ACCEPT,
  ALLOWED_FILE_EXTENSIONS,
  DEFAULT_EXPERT_ID,
  MAX_UPLOAD_FILE_SIZE,
  MAX_UPLOAD_FILE_SIZE_MB,
  EXPERT_NAME_KEYS,
  groupQuickExperts,
} from './constants';
import AiPlatformApi, {
  AiChatMessage,
  AiExpertDefinition,
  AiSessionSummary,
  ExpertTask,
} from '@/api/aiPlatform';
import { getLlmStatus, listLlmProviders, getLlmProviderDetail } from '@/api/llmConfig';
import { debounce, toAsyncWait } from '@/utils/common';
import sendIcon from '@/assets/img/aibot/send.svg';
import sendActiveIcon from '@/assets/img/aibot/send-active.svg';
import sendStopIcon from '@/assets/img/aibot/send-stop.svg';
import headerLogo from '@/assets/img/aibot/logo.png';
import chatStarIcon from '@/assets/img/chatai/chat_star.svg';

interface RenderMessage extends AiChatMessage {
  displayContent: string;
  isLastAssistant?: boolean;
  _key?: string;
}

interface UploadItem {
  id: string
  type: 'image' | 'file'
  name: string
  file: File
  url?: string
}

interface AttachmentMeta {
  type: string
  name: string
  mimeType?: string
  size?: number
  dataUrl?: string
  filePath?: string
}

interface GeneratedFileMeta {
  type?: string
  name: string
  mimeType?: string
  size?: number
  filePath: string
}

interface ChatModelOption {
  key: string;
  providerCode: string;
  providerName: string;
  modelName: string;
  modelLabel: string;
  label: string;
  defaultProvider: boolean;
  defaultModel: boolean;
}

interface ChatSuggestion {
  text: string;
  labelKey: string;
}

const DEFAULT_SUGGESTIONS: ChatSuggestion[] = [
  { text: i18n.t('modules.views.aiPlatform.chat.s_80dbeb77') as string, textKey: 'modules.views.aiPlatform.chat.s_80dbeb77', labelKey: 'modules.views.aiPlatform.chat.s_80dbeb77' },
  { text: i18n.t('modules.views.aiPlatform.chat.s_ed272468') as string, textKey: 'modules.views.aiPlatform.chat.s_ed272468', labelKey: 'modules.views.aiPlatform.chat.s_ed272468' },
  { text: i18n.t('modules.views.aiPlatform.chat.s_c7e18d81') as string, textKey: 'modules.views.aiPlatform.chat.s_c7e18d81', labelKey: 'modules.views.aiPlatform.chat.s_c7e18d81' },
  { text: i18n.t('modules.views.aiPlatform.chat.s_1d5efe38') as string, textKey: 'modules.views.aiPlatform.chat.s_1d5efe38', labelKey: 'modules.views.aiPlatform.chat.s_1d5efe38' },
];

@Component({
  components: { MarkedView, ChatUploadPreview, ThinkingProcess, AiTrendCharts },
})
export default class AiPlatformChat extends Vue {
  public $refs!: {
    messageBox: HTMLDivElement;
    uploadFileInput: HTMLInputElement;
    uploadImageInput: HTMLInputElement;
  }

  private bootLoading = false
  private llmReady = true
  private sending = false
  private aborting = false
  private draft = ''
  private maxInputLength = 20000
  private expertId = DEFAULT_EXPERT_ID
  private activeSessionId = ''
  private lastPollMessageId = ''
  private serverRunning = false
  private pollTimer: number | null = null
  private typewriterTimers: Record<string, number> = {}
  private isUserScrolling = false
  private lastScrollTop = 0
  private chatPageEl: HTMLElement | null = null
  private showSessionDrawer = false
  private suggestionCursor = 0
  private greetingNowTs = Date.now()
  private greetingTimer: number | null = null
  private uploadItems: UploadItem[] = []
  private allowedFileAccept = ALLOWED_FILE_ACCEPT
  private sendIcon = sendIcon
  private sendActiveIcon = sendActiveIcon
  private sendStopIcon = sendStopIcon
  private headerLogo = headerLogo
  private chatStarIcon = chatStarIcon
  private chatTextarea: HTMLTextAreaElement | null = null

  private sessions: AiSessionSummary[] = []
  private sessionTotal = 0
  private sessionListOffset = 0
  private sessionListLoading = false
  private readonly sessionPageSize = 20
  private experts: AiExpertDefinition[] = []
  private messages: AiChatMessage[] = []
  private displayContent: Record<string, string> = {}
  private tasks: ExpertTask[] = []
  private showToolDetailDialog = false
  private selectedToolMessage: AiChatMessage | null = null
  private selectedToolDetailView: 'params' | 'result' = 'result'
  private modelOptions: ChatModelOption[] = []
  private selectedModelKey = ''
  private showExpertOverflowPopover = false
  private expertOverflowKeyword = ''

  private get sessionListNoMore (): boolean {
    return this.sessions.length >= this.sessionTotal
  }

  private get quickExpertGroups () {
    return groupQuickExperts(this.experts)
  }

  private get inlineQuickExperts () {
    return this.quickExpertGroups.inline
  }

  private get overflowQuickExperts () {
    return this.quickExpertGroups.overflow
  }

  private get filteredOverflowExperts () {
    const keyword = this.expertOverflowKeyword.trim().toLowerCase()
    if (!keyword) {
      return this.overflowQuickExperts
    }
    return this.overflowQuickExperts.filter(item => {
      const name = this.expertDisplayName(item.expertId).toLowerCase()
      return item.expertId.toLowerCase().includes(keyword) || name.includes(keyword)
    })
  }

  private get isOverflowExpertActive (): boolean {
    return this.overflowQuickExperts.some(item => item.expertId === this.expertId)
  }

  private get conversationStarted (): boolean {
    return this.messages.length > 0 || this.sending || this.serverRunning
  }

  private get canSend (): boolean {
    return Boolean(this.draft.trim() || this.uploadItems.length)
  }

  private get uploadPreviewItems () {
    return this.uploadItems.map(item => ({
      id: item.id,
      type: item.type,
      name: item.name,
      url: item.url,
    }))
  }

  private get uploadFileTooltipText (): string {
    return i18n.t('modules.views.aiPlatform.chat.s_51aaf9a1', {
      extensions: ALLOWED_FILE_EXTENSIONS.join(', '),
      size: MAX_UPLOAD_FILE_SIZE_MB,
    }) as string
  }

  private get accountName (): string {
    return this.$store.getters['User/getUserInfo']?.account || i18n.t('modules.views.aiPlatform.chat.s_1fd02a90') as string
  }

  private get userAvatarText (): string {
    const name = String(this.accountName || 'U').trim()
    return (name.slice(0, 1) || 'U').toUpperCase()
  }

  private get currentTimeText (): string {
    return dayjs().format('HH:mm')
  }

  private get greetingText (): string {
    const now = dayjs(this.greetingNowTs)
    const minutes = now.hour() * 60 + now.minute()
    let greetKey = 'modules.views.aiPlatform.chat.s_1609d6fb'
    if (minutes >= 5 * 60 && minutes < 11 * 60) {
      greetKey = 'modules.views.aiPlatform.chat.s_48f75a24'
    } else if (minutes >= 11 * 60 && minutes < 13 * 60) {
      greetKey = 'modules.views.aiPlatform.chat.s_1a194c80'
    } else if (minutes >= 13 * 60 && minutes < 18 * 60) {
      greetKey = 'modules.views.aiPlatform.chat.s_8c9bdf7d'
    } else if (minutes >= 18 * 60 && minutes < 23 * 60) {
      greetKey = 'modules.views.aiPlatform.chat.s_6ab1f177'
    }
    return i18n.t('modules.views.aiPlatform.chat.s_adc92119', {
      greet: i18n.t(greetKey),
      name: this.accountName,
    }) as string
  }

  private get visibleSuggestions (): ChatSuggestion[] {
    const size = 4
    const total = DEFAULT_SUGGESTIONS.length
    const list: ChatSuggestion[] = []
    for (let i = 0; i < size; i++) {
      list.push(DEFAULT_SUGGESTIONS[(this.suggestionCursor + i) % total])
    }
    return list
  }

  private get inProgressRoundIndex (): number {
    if (!this.sending && !this.serverRunning) {
      return 0
    }
    const userRounds = [...new Set(
      this.messages
        .filter(item => item.role === 'user')
        .map(item => Number(item.roundIndex || 0))
        .filter(round => round > 0),
    )].sort((left, right) => right - left)
    for (const round of userRounds) {
      if (!this.hasRenderableAssistantForRound(round)) {
        return round
      }
    }
    return 0
  }

  private hasRenderableAssistantForRound (roundIndex: number): boolean {
    return this.messages.some(item => isRoundFinalText(item)
      && Number(item.roundIndex || 0) === roundIndex)
  }

  private get showThinking (): boolean {
    if (!this.sending && !this.serverRunning) {
      return false
    }
    if (allUserRoundsHaveFinalAnswer(this.messages)) {
      return false
    }
    const round = this.inProgressRoundIndex
    if (round <= 0) {
      return false
    }
    if (this.hasRenderableAssistantForRound(round)) {
      return false
    }
    if (hasVisibleAssistantAnswerForRound(this.messages, round)) {
      return false
    }
    return true
  }

  private get currentRoundThinkingDetails () {
    return buildThinkingDetailItems(this.currentRoundProcessItems)
  }

  private get currentRoundProcessItems (): AiChatMessage[] {
    const roundIndex = (this.sending || this.serverRunning)
      ? this.inProgressRoundIndex
      : this.messages.reduce((max, item) => Math.max(max, Number(item.roundIndex || 0)), 0)
    if (!roundIndex) {
      return []
    }
    return this.messages
      .filter(item => Number(item.roundIndex || 0) === roundIndex && isProcessMessageType(item))
      .sort((left, right) => Number(left.messageIndex || 0) - Number(right.messageIndex || 0))
  }

  private get currentRoundThinkingDurationMs (): number {
    return this.thinkingDurationMs({ roundIndex: this.currentRoundProcessItems[0]?.roundIndex || 0 } as AiChatMessage)
  }

  private get selectedToolName (): string {
    if (!this.selectedToolMessage) {
      return ''
    }
    return this.processToolName(this.selectedToolMessage)
  }

  private get selectedToolDetail (): string {
    if (!this.selectedToolMessage) {
      return ''
    }
    return this.formatToolDetail(this.selectedToolMessage)
  }

  private get selectedToolTerminalDisplay () {
    if (!this.selectedToolMessage || this.selectedToolDetailView !== 'result') {
      return null
    }
    const toolName = this.processToolName(this.selectedToolMessage)
    if (!isTerminalToolName(toolName)) {
      return null
    }
    return formatTerminalToolDisplay(this.selectedToolDetail)
  }

  private get selectedToolBashParamsDisplay () {
    if (!this.selectedToolMessage || this.selectedToolDetailView !== 'params') {
      return null
    }
    if (this.processToolName(this.selectedToolMessage) !== 'Bash') {
      return null
    }
    const metadata = this.selectedToolMessage.metadata || {}
    const callId = String(metadata.toolCallId || metadata.callId || '').trim()
    return formatBashToolParamsDisplay(this.toolInputOf(metadata, callId))
  }

  private get selectedToolDetailCopyText (): string {
    if (this.selectedToolBashParamsDisplay) {
      return this.selectedToolBashParamsDisplay.command
    }
    if (this.selectedToolTerminalDisplay) {
      return this.selectedToolTerminalDisplay.body
    }
    return this.selectedToolDetail
  }

  private get selectedToolIsTerminal (): boolean {
    if (!this.selectedToolMessage || this.selectedToolDetailView !== 'result') {
      return false
    }
    return isTerminalToolName(this.processToolName(this.selectedToolMessage))
  }

  private get selectedToolDetailTitle (): string {
    if (this.selectedToolDetailView === 'params') {
      return i18n.t('modules.views.aiPlatform.chat.s_6fa6500b') as string
    }
    const type = String(this.selectedToolMessage?.messageType || '').toUpperCase()
    if (type === 'TOOL_CALL') {
      return i18n.t('modules.views.aiPlatform.chat.s_6fa6500b') as string
    }
    if (type === 'TOOL_RESULT') {
      return i18n.t('modules.views.aiPlatform.chat.s_beaee809') as string
    }
    return i18n.t('modules.views.aiPlatform.chat.s_9f02c6d0') as string
  }

  private get selectedToolCharts (): Array<Record<string, unknown>> {
    if (this.selectedToolDetailView !== 'result') {
      return []
    }
    return this.extractTrendChartsFromToolMessage(this.selectedToolMessage)
  }

  private get selectedToolStatusLabel (): string {
    if (!this.selectedToolMessage || this.selectedToolDetailView === 'params') {
      return ''
    }
    const type = String(this.selectedToolMessage.messageType || '').toUpperCase()
    if (type !== 'TOOL_RESULT') {
      return ''
    }
    return this.isSelectedToolFailed()
      ? i18n.t('modules.views.aiPlatform.chat.s_c0d43a35') as string
      : i18n.t('modules.views.aiPlatform.chat.s_242be0fe') as string
  }

  private get selectedToolStatusClass (): string {
    return this.isSelectedToolFailed() ? 'is-failed' : 'is-success'
  }

  private get selectedToolDurationText (): string {
    if (!this.selectedToolMessage || this.selectedToolDetailView === 'params') {
      return ''
    }
    const metadata = this.selectedToolMessage.metadata || {}
    const durationMs = Number(metadata.durationMs || 0)
    if (durationMs > 0) {
      return formatToolDurationText(durationMs)
    }
    const callMessage = this.findRelatedToolCallMessage(this.selectedToolMessage)
    const resultTs = this.resolveMessageTimestamp(this.selectedToolMessage)
    const callTs = this.resolveMessageTimestamp(callMessage)
    if (callTs > 0 && resultTs >= callTs) {
      return formatToolDurationText(resultTs - callTs)
    }
    return ''
  }

  private get renderMessages (): RenderMessage[] {
    const lastAssistantId = [...this.messages]
      .reverse()
      .find(item => isRoundFinalText(item))?.messageId
    return this.messages
      .filter(item => !this.isProcessMessage(item))
      .map(item => ({
        ...item,
        _key: item.messageId || uuidv4(),
        displayContent: item.messageId
          ? (this.displayContent[item.messageId] ?? item.content ?? '')
          : (item.content || ''),
        isLastAssistant: item.messageId === lastAssistantId && item.role === 'assistant',
      }))
      .filter(item => {
        if (item.role !== 'assistant') {
          return true
        }
        return Boolean(
          String(item.displayContent || '').trim()
          || this.messageThinkingDetails(item).length
          || this.messageTrendCharts(item).length
          || this.messageAttachments(item).length,
        )
      })
  }

  private async created () {
    this.bootLoading = true
    await Promise.all([this.loadExperts(), this.loadSessionTotal(), this.reloadSessions(), this.loadLlmStatus(), this.loadModelOptions()])
    const sessionId = this.routeSessionId()
    if (sessionId) {
      await this.selectSession(sessionId, false)
    }
    this.bootLoading = false
  }

  private async loadLlmStatus () {
    const { result, error } = await toAsyncWait(getLlmStatus(), false)
    if (error) {
      this.llmReady = false
      return
    }
    this.llmReady = Boolean(result?.ready)
  }

  private async loadModelOptions () {
    const { result, error } = await toAsyncWait(listLlmProviders(), false)
    if (error || !Array.isArray(result)) {
      this.modelOptions = []
      return
    }
    const providers = result.filter(item => item.enabled && item.configured)
    const details = await Promise.all(providers.map(async (provider) => {
      const { result: detail } = await toAsyncWait(getLlmProviderDetail(provider.providerCode), false)
      return { provider, detail }
    }))
    const options: ChatModelOption[] = []
    details.forEach(({ provider, detail }) => {
      const models = Array.isArray(detail?.models) && detail.models.length
        ? detail.models
        : [{ modelId: provider.defaultModel, displayName: provider.defaultModel, defaultModel: true }]
      models.forEach((model: any) => {
        const modelName = String(model.modelId || '').trim()
        if (!modelName) {
          return
        }
        const modelLabel = String(model.displayName || modelName).trim()
        options.push({
          key: `${provider.providerCode}::${modelName}`,
          providerCode: provider.providerCode,
          providerName: provider.displayName,
          modelName,
          modelLabel,
          label: `${provider.displayName} / ${modelLabel}`,
          defaultProvider: Boolean(provider.defaultProvider),
          defaultModel: Boolean(model.defaultModel || modelName === provider.defaultModel),
        })
      })
    })
    this.modelOptions = options
    const defaultOption = options.find(item => item.defaultProvider && item.defaultModel)
      || options.find(item => item.defaultProvider)
      || options.find(item => item.defaultModel)
      || options[0]
    const cachedKey = window.localStorage.getItem('ai_chat_model_key') || ''
    this.selectedModelKey = cachedKey && options.some(item => item.key === cachedKey)
      ? cachedKey
      : this.selectedModelKey && options.some(item => item.key === this.selectedModelKey)
        ? this.selectedModelKey
      : (defaultOption?.key || '')
  }

  private get selectedModelOption (): ChatModelOption | null {
    return this.modelOptions.find(item => item.key === this.selectedModelKey) || null
  }

  private handleModelChange () {
    const option = this.selectedModelOption
    if (option) {
      window.localStorage.setItem('ai_chat_model_key', option.key)
    }
  }

  private mounted () {
    this.greetingTimer = window.setInterval(() => {
      this.greetingNowTs = Date.now()
    }, 60 * 1000)
    this.chatPageEl = this.$el as HTMLElement
    this.chatPageEl.addEventListener('wheel', this.onPageWheel, { passive: false })
    this.$nextTick(() => {
      this.chatTextarea = this.$el.querySelector('.input-chat textarea')
      this.chatTextarea?.addEventListener('keydown', this.keydownHandle)
    })
  }

  @Watch('$route.query.sessionId')
  private async onRouteSessionIdChange (value: string | string[] | undefined) {
    const sessionId = Array.isArray(value) ? value[0] : value
    const normalized = String(sessionId || '').trim()
    if (!normalized || normalized === this.activeSessionId) {
      return
    }
    await this.selectSession(normalized, false)
  }

  private beforeDestroy () {
    this.chatPageEl?.removeEventListener('wheel', this.onPageWheel)
    this.chatPageEl = null
    this.chatTextarea?.removeEventListener('keydown', this.keydownHandle)
    this.chatTextarea = null
    this.stopPollLoop()
    Object.values(this.typewriterTimers).forEach(timer => window.clearInterval(timer))
    this.typewriterTimers = {}
    this.clearUploadItems()
    if (this.greetingTimer) {
      window.clearInterval(this.greetingTimer)
      this.greetingTimer = null
    }
  }

  private toggleQuickExpert (expertId: string) {
    if (this.sending || this.serverRunning) {
      return
    }
    this.expertId = this.expertId === expertId ? 'brain' : expertId
  }

  private resetExpertOverflowKeyword () {
    this.expertOverflowKeyword = ''
  }

  private selectOverflowExpert (expertId: string) {
    if (this.sending || this.serverRunning) {
      return
    }
    this.expertId = this.expertId === expertId ? DEFAULT_EXPERT_ID : expertId
    this.showExpertOverflowPopover = false
    this.expertOverflowKeyword = ''
  }

  private expertDisplayName (expertId?: string): string {
    const id = String(expertId || DEFAULT_EXPERT_ID || '').trim()
    if (!id) {
      return ''
    }
    const builtInNameKey = EXPERT_NAME_KEYS[id]
    if (builtInNameKey) {
      return i18n.t(builtInNameKey) as string
    }
    const expert = this.experts.find(item => item.expertId === id)
    const nameKey = (expert as any)?.nameKey
    return nameKey ? i18n.t(nameKey) as string : (expert?.name || id)
  }

  private messageAuthor (msg: AiChatMessage): string {
    if (msg.role === 'user') {
      return this.accountName
    }
    return this.expertDisplayName(msg.expertId || DEFAULT_EXPERT_ID)
  }

  private messageTime (msg: AiChatMessage): string {
    const raw = msg.ts || msg.timestamp || msg.updatedAt
    const time = raw ? dayjs(raw) : dayjs()
    if (!time.isValid()) {
      return this.currentTimeText
    }
    return time.isSame(dayjs(), 'day') ? time.format('HH:mm') : time.format('YYYY-MM-DD HH:mm')
  }

  private handleUploadCommand (command: 'file' | 'image') {
    if (command === 'image') {
      this.$refs.uploadImageInput?.click()
      return
    }
    this.$refs.uploadFileInput?.click()
  }

  private handleFileInputChange (event: Event, uploadType: 'file' | 'image') {
    const target = event.target as HTMLInputElement
    const files = Array.from(target.files || [])
    files.forEach(file => {
      if (!this.validateUploadFile(file, uploadType)) {
        return
      }
      this.appendUploadItem(file, uploadType)
    })
    target.value = ''
  }

  private handlePaste (event: ClipboardEvent) {
    const clipboardData = event.clipboardData || (window as any).clipboardData
    if (!clipboardData) {
      return
    }
    for (let i = 0; i < clipboardData.items.length; i++) {
      const item = clipboardData.items[i]
      if (String(item?.type || '').indexOf('image') !== -1) {
        event.preventDefault()
        const file = item.getAsFile()
        if (file && this.validateUploadFile(file, 'image')) {
          this.appendUploadItem(file, 'image')
        }
        break
      }
    }
  }

  private validateUploadFile (file: File, uploadType: 'file' | 'image'): boolean {
    if (!file) {
      return false
    }
    if (Number(file.size || 0) > MAX_UPLOAD_FILE_SIZE) {
      this.$message.warning(i18n.t('modules.views.aiPlatform.chat.s_3f8cde97', {
        size: MAX_UPLOAD_FILE_SIZE_MB,
        name: file.name || '',
      }) as string)
      return false
    }
    if (uploadType === 'image') {
      if (!String(file.type || '').startsWith('image/')) {
        this.$message.warning(i18n.t('modules.views.aiPlatform.chat.s_7a615dcc', { name: file.name || '' }) as string)
        return false
      }
      return true
    }
    const fileName = String(file.name || '').toLowerCase()
    const ext = fileName.includes('.') ? fileName.slice(fileName.lastIndexOf('.')) : ''
    if (!(ALLOWED_FILE_EXTENSIONS as readonly string[]).includes(ext)) {
      this.$message.warning(i18n.t('modules.views.aiPlatform.chat.s_799d09fb', { name: file.name || '' }) as string)
      return false
    }
    return true
  }

  private appendUploadItem (file: File, type: 'image' | 'file') {
    this.uploadItems.push({
      id: `${Date.now()}_${Math.random().toString(16).slice(2)}`,
      type,
      name: String(file.name || '').trim() || (type === 'image'
        ? i18n.t('modules.views.aiPlatform.chat.s_20def794') as string
        : i18n.t('modules.views.aiPlatform.chat.s_2a0c4740') as string),
      file,
      url: type === 'image' ? URL.createObjectURL(file) : '',
    })
  }

  private removeUploadItem (itemId: string) {
    const index = this.uploadItems.findIndex(item => item.id === itemId)
    if (index < 0) {
      return
    }
    const [removed] = this.uploadItems.splice(index, 1)
    if (removed?.type === 'image' && removed.url) {
      URL.revokeObjectURL(removed.url)
    }
  }

  private clearUploadItems () {
    this.uploadItems.forEach(item => {
      if (item.type === 'image' && item.url) {
        URL.revokeObjectURL(item.url)
      }
    })
    this.uploadItems = []
  }

  private readFileAsDataUrl (file: File): Promise<string> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result || ''))
      reader.onerror = () => reject(reader.error)
      reader.readAsDataURL(file)
    })
  }

  private async buildAttachmentContext (): Promise<Record<string, unknown>> {
    if (!this.uploadItems.length) {
      return {}
    }
    const attachments = await Promise.all(this.uploadItems.map(async (item) => ({
      type: item.type,
      name: item.name,
      mimeType: item.file.type,
      size: item.file.size,
      dataUrl: await this.readFileAsDataUrl(item.file),
    })))
    return { attachments }
  }

  private messageAttachments (msg: AiChatMessage): AttachmentMeta[] {
    const raw = msg.metadata?.attachments
    if (!Array.isArray(raw)) {
      return []
    }
    return raw.filter(item => item && typeof item === 'object') as AttachmentMeta[]
  }

  private messageGeneratedFiles (msg: AiChatMessage): GeneratedFileMeta[] {
    const raw = msg.metadata?.generatedFiles
    if (!Array.isArray(raw)) {
      return []
    }
    return raw.filter(item => item && typeof item === 'object' && (item as GeneratedFileMeta).filePath) as GeneratedFileMeta[]
  }

  private formatFileSize (size?: number): string {
    const value = Number(size || 0)
    if (!value) {
      return ''
    }
    if (value < 1024) {
      return `${value} B`
    }
    if (value < 1024 * 1024) {
      return `${(value / 1024).toFixed(1)} KB`
    }
    return `${(value / (1024 * 1024)).toFixed(1)} MB`
  }

  private async downloadWorkspaceFile (file: AttachmentMeta | GeneratedFileMeta) {
    const filePath = String(file.filePath || '').trim()
    if (!filePath || !this.activeSessionId) {
      return
    }
    try {
      const response: any = await AiPlatformApi.downloadWorkspaceFile(this.activeSessionId, filePath)
      const blob = response?.data instanceof Blob ? response.data : new Blob([response?.data || ''])
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = String(file.name || filePath.split('/').pop() || 'download')
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
      URL.revokeObjectURL(url)
    } catch (error: any) {
      this.$message.error(error?.message || i18n.t('modules.views.aiPlatform.chat.s_65e200d3') as string)
    }
  }

  private changeSuggestionBatch () {
    this.suggestionCursor = (this.suggestionCursor + 4) % DEFAULT_SUGGESTIONS.length
  }

  private fillSuggestion (item: ChatSuggestion) {
    this.draft = (i18n.t(item.labelKey) as string).slice(0, this.maxInputLength)
    this.sendMessage()
  }

  private handleNewSessionFromDrawer () {
    this.showSessionDrawer = false
    this.newSession()
  }

  private async selectSessionFromDrawer (sessionId: string) {
    this.showSessionDrawer = false
    await this.selectSession(sessionId)
  }

  private async loadExperts () {
    const { result, error } = await toAsyncWait(AiPlatformApi.listExperts(), false)
    if (!error) {
      this.experts = result || []
    }
  }

  private async loadSessionTotal () {
    const { result, error } = await toAsyncWait(AiPlatformApi.countSessions(), false)
    if (!error && result) {
      this.sessionTotal = Number(result.total || 0)
    }
  }

  private async reloadSessions () {
    this.sessionListOffset = 0
    this.sessions = []
    await Promise.all([this.loadSessionTotal(), this.loadMoreSessions(true)])
  }

  private async loadMoreSessions (reset = false) {
    if (this.sessionListLoading) {
      return
    }
    if (!reset && this.sessionListNoMore) {
      return
    }
    this.sessionListLoading = true
    const offset = reset ? 0 : this.sessionListOffset
    const { result, error } = await toAsyncWait(
      AiPlatformApi.listSessions({ offset, limit: this.sessionPageSize }),
      false,
    )
    this.sessionListLoading = false
    if (error || !result) {
      return
    }
    const items = Array.isArray(result.data) ? result.data : []
    if (reset) {
      this.sessions = items
    } else if (items.length) {
      const existing = new Set(this.sessions.map(item => item.sessionId))
      this.sessions = [
        ...this.sessions,
        ...items.filter(item => item.sessionId && !existing.has(item.sessionId)),
      ]
    }
    this.sessionListOffset = Number(result.offset ?? offset + items.length)
    if (result.total != null) {
      this.sessionTotal = Number(result.total)
    }
  }

  private newSession () {
    this.stopPollLoop()
    this.activeSessionId = uuidv4()
    this.lastPollMessageId = ''
    this.messages = []
    this.displayContent = {}
    this.tasks = []
    this.serverRunning = false
    this.sending = false
    this.draft = ''
    this.expertId = DEFAULT_EXPERT_ID
    this.clearUploadItems()
    this.syncSessionIdToRoute(this.activeSessionId)
  }

  private async selectSession (sessionId: string, syncRoute = true) {
    this.stopPollLoop()
    this.activeSessionId = sessionId
    this.lastPollMessageId = ''
    await Promise.all([
      this.loadSessionMessages(sessionId),
      this.loadSessionTasks(sessionId),
    ])
    if (this.serverRunning) {
      this.startPollLoop()
    }
    if (syncRoute) {
      this.syncSessionIdToRoute(sessionId)
    }
    this.scrollToBottom(true)
  }

  private async loadSessionMessages (sessionId: string) {
    const { result, error } = await toAsyncWait(AiPlatformApi.pollMessages(sessionId), false)
    if (error) {
      return
    }
    this.messages = result?.messages || []
    this.serverRunning = !!result?.running
    this.displayContent = {}
    this.messages.forEach(item => {
      if (item.messageId) {
        this.$set(this.displayContent, item.messageId, item.content || '')
      }
      this.collectEvent(item)
    })
    this.lastPollMessageId = this.resolveLastMessageId(this.messages)
    const lastExpert = [...this.messages].reverse().find(item => item.expertId)?.expertId
    this.expertId = lastExpert || DEFAULT_EXPERT_ID
  }

  private async loadSessionTasks (sessionId: string) {
    const { result, error } = await toAsyncWait(AiPlatformApi.sessionTasks(sessionId), false)
    if (!error) {
      this.tasks = result || []
    }
  }

  private keydownHandle (event: KeyboardEvent) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      this.sendMessage()
    }
  }

  private async abortChat () {
    if (this.aborting || (!this.sending && !this.serverRunning)) {
      return
    }
    this.aborting = true
    try {
      if (this.activeSessionId) {
        const { error } = await toAsyncWait(
          AiPlatformApi.abortChat(this.activeSessionId),
          false,
        )
        if (error) {
          this.$message.error(error?.message || i18n.t('modules.views.aiPlatform.chat.s_c4b67abf') as string)
        } else {
          await this.loadSessionMessages(this.activeSessionId)
        }
      }
      this.finishChatTurn()
    } finally {
      this.aborting = false
    }
  }

  private async sendMessage () {
    const message = this.draft.trim()
    if ((!message && !this.uploadItems.length) || this.sending) {
      return
    }
    const attachmentContext = await this.buildAttachmentContext()
    const attachments = attachmentContext.attachments as AttachmentMeta[] | undefined
    const finalMessage = message || i18n.t('modules.views.aiPlatform.chat.s_59cdcfec', {
      count: attachments?.length || this.uploadItems.length,
    }) as string
    this.draft = ''
    this.sending = true
    try {
      const { result, error } = await toAsyncWait(AiPlatformApi.submitChat({
        sessionId: this.activeSessionId || null,
        expertId: this.expertId,
        message: finalMessage,
        modelProviderCode: this.selectedModelOption?.providerCode || null,
        modelName: this.selectedModelOption?.modelName || null,
        userName: this.accountName,
        context: attachmentContext,
      }), false)
      if (error) {
        throw error
      }
      this.clearUploadItems()
      this.activeSessionId = result?.sessionId || this.activeSessionId
      this.syncSessionIdToRoute(this.activeSessionId)
      this.serverRunning = true
      await this.reloadSessions()
      this.startPollLoop()
      this.scrollToBottom(true)
    } catch (error: any) {
      this.sending = false
      this.serverRunning = false
      this.$message.error(error?.message || i18n.t('modules.views.aiPlatform.chat.s_9ca6a344') as string)
    }
  }

  private startPollLoop () {
    this.stopPollLoop()
    const poll = async () => {
      if (!this.activeSessionId) {
        return
      }
      const { result, error } = await toAsyncWait(
        AiPlatformApi.pollMessages(this.activeSessionId),
        false,
      )
      if (!error && result) {
        this.applyPollResult(result.messages || [], !!result.running)
      }
      if (this.shouldContinuePolling()) {
        this.pollTimer = window.setTimeout(poll, 600)
      } else {
        this.finishChatTurn()
      }
    }
    poll()
  }

  private stopPollLoop () {
    if (this.pollTimer) {
      window.clearTimeout(this.pollTimer)
      this.pollTimer = null
    }
  }

  private shouldContinuePolling (): boolean {
    if (this.serverRunning || this.hasTypingAnimation() || this.sending) {
      return true
    }
    const round = this.inProgressRoundIndex
    if (round > 0 && !this.hasRenderableAssistantForRound(round)) {
      return this.tasks.some(task => task.status === 'RUNNING' || task.status === 'CREATED')
    }
    return false
  }

  private finishChatTurn () {
    this.stopPollLoop()
    this.sending = false
    this.serverRunning = false
    if (this.activeSessionId) {
      this.loadSessionTasks(this.activeSessionId)
      this.reloadSessions()
    }
  }

  private applyPollResult (incoming: AiChatMessage[], running: boolean) {
    this.serverRunning = running
    incoming.forEach(item => this.mergeMessage(item))
    if (this.serverRunning && this.activeSessionId) {
      this.loadSessionTasks(this.activeSessionId)
    }
  }

  private mergeMessage (item: AiChatMessage) {
    if (!item.messageId) {
      return
    }
    const index = this.messages.findIndex(row => row.messageId === item.messageId)
    if (index >= 0) {
      this.$set(this.messages, index, item)
      if (this.isAssistantTextMessage(item) && this.displayContent[item.messageId] === undefined) {
        this.$set(this.displayContent, item.messageId, '')
      } else if (!this.isAssistantTextMessage(item)) {
        this.$set(this.displayContent, item.messageId, item.content || '')
      }
    } else {
      this.messages.push(item)
      if (this.isAssistantTextMessage(item)) {
        this.$set(this.displayContent, item.messageId, '')
      } else {
        this.$set(this.displayContent, item.messageId, item.content || '')
      }
    }
    this.lastPollMessageId = item.messageId
    this.collectEvent(item)
    if (this.isAssistantTextMessage(item)) {
      this.ensureTypewriter(item.messageId)
    }
    this.scrollToBottom()
  }

  private collectEvent (item: AiChatMessage) {
    const subtasks = item.metadata?.subtasks
    if (Array.isArray(subtasks) && subtasks.length) {
      this.refreshTasks()
    }
  }

  private ensureTypewriter (messageId: string) {
    if (this.typewriterTimers[messageId]) {
      return
    }
    this.typewriterTimers[messageId] = window.setInterval(() => {
      const message = this.messages.find(row => row.messageId === messageId)
      if (!message) {
        this.clearTypewriter(messageId)
        return
      }
      const target = message.content || ''
      const current = this.displayContent[messageId] || ''
      if (current.length >= target.length) {
        this.clearTypewriter(messageId)
        if (!this.serverRunning && !this.hasTypingAnimation()) {
          this.finishChatTurn()
        }
        return
      }
      const step = Math.min(4, target.length - current.length)
      this.$set(this.displayContent, messageId, target.slice(0, current.length + step))
      this.scrollToBottom()
    }, 12)
  }

  private clearTypewriter (messageId: string) {
    if (this.typewriterTimers[messageId]) {
      window.clearInterval(this.typewriterTimers[messageId])
      delete this.typewriterTimers[messageId]
    }
  }

  private hasTypingAnimation (): boolean {
    return this.messages.some(item => {
      if (!item.messageId || !this.isAssistantTextMessage(item)) {
        return false
      }
      return (this.displayContent[item.messageId] || '').length < (item.content || '').length
    })
  }

  private resolveLastMessageId (items: AiChatMessage[]): string {
    if (!items.length) {
      return ''
    }
    return items[items.length - 1].messageId || ''
  }

  private async refreshTasks () {
    if (!this.activeSessionId) {
      return
    }
    await this.loadSessionTasks(this.activeSessionId)
  }

  private isAssistantTextMessage (item: AiChatMessage): boolean {
    return item.role === 'assistant' && !this.isProcessMessage(item)
  }

  private isProcessMessage (item: AiChatMessage): boolean {
    return isProcessMessageType(item);
  }

  private messageProcessItems (msg: AiChatMessage): AiChatMessage[] {
    const roundIndex = Number(msg.roundIndex || 0)
    if (!roundIndex) {
      return []
    }
    return this.messages
      .filter(item => Number(item.roundIndex || 0) === roundIndex && isProcessMessageType(item))
      .sort((left, right) => Number(left.messageIndex || 0) - Number(right.messageIndex || 0))
  }

  private messageThinkingDetails (msg: AiChatMessage) {
    return buildThinkingDetailItems(this.messageProcessItems(msg))
  }

  private messageTrendCharts (msg: AiChatMessage): Array<Record<string, unknown>> {
    return this.messageProcessItems(msg)
      .filter(item => String(item.messageType || '').toUpperCase() === 'TOOL_RESULT')
      .flatMap(item => this.extractTrendChartsFromToolMessage(item))
  }

  private isThinkingFinished (msg: RenderMessage): boolean {
    if (!msg.isLastAssistant) {
      return true
    }
    return !(this.serverRunning || this.sending)
  }

  private isThinkingExpanded (msg: RenderMessage): boolean {
    const running = this.serverRunning || this.sending
    const terminal = ['COMPLETED', 'FAILED', 'TIMEOUT', 'CANCELLED'].includes(String(msg.messageStatus || '').toUpperCase())
    return running && Boolean(msg.isLastAssistant) && !terminal
  }

  private thinkingDurationMs (msg: AiChatMessage): number {
    const items = this.messageProcessItems(msg)
    const timestamps = items
      .map(item => {
        const raw = item.ts || item.timestamp || item.updatedAt
        if (!raw) {
          return 0
        }
        const value = new Date(raw).getTime()
        return Number.isFinite(value) ? value : 0
      })
      .filter(value => value > 0)
    if (timestamps.length < 2) {
      return 0
    }
    return Math.max(0, Math.max(...timestamps) - Math.min(...timestamps))
  }

  private processToolName (item: AiChatMessage): string {
    return String(item.metadata?.toolName || '').trim()
  }

  private resolveMessageTimestamp (item?: AiChatMessage | null): number {
    if (!item) {
      return 0
    }
    const raw = item.ts || item.timestamp || item.updatedAt
    if (!raw) {
      return 0
    }
    const value = new Date(raw).getTime()
    return Number.isFinite(value) && value > 0 ? value : 0
  }

  private isSelectedToolFailed (): boolean {
    const item = this.selectedToolMessage
    if (!item) {
      return false
    }
    const toolResultState = String(item.metadata?.toolResultState || '').toUpperCase()
    if (['FAILED', 'ERROR', 'FAILURE', 'TIMEOUT', 'CANCELLED'].includes(toolResultState)) {
      return true
    }
    const messageStatus = String(item.messageStatus || '').toUpperCase()
    return ['FAILED', 'ERROR', 'TIMEOUT', 'CANCELLED'].includes(messageStatus)
  }

  private findRelatedToolCallMessage (resultMessage: AiChatMessage): AiChatMessage | undefined {
    const callId = String(resultMessage.metadata?.toolCallId || resultMessage.metadata?.callId || '').trim()
    if (!callId) {
      return undefined
    }
    return this.messages.find((row) => {
      const type = String(row.messageType || '').toUpperCase()
      return type === 'TOOL_CALL'
        && String(row.metadata?.toolCallId || row.metadata?.callId || '').trim() === callId
    })
  }

  private openToolDetail (tool: ToolCallItem, view: 'params' | 'result' = 'result') {
    const callMessage = tool?.callMessage
    const resultMessage = tool?.resultMessage
    let preferred: AiChatMessage | undefined
    if (view === 'params') {
      preferred = callMessage || resultMessage || tool?.sourceMessage
    } else if (resultMessage) {
      preferred = resultMessage
    } else {
      preferred = callMessage || tool?.sourceMessage
    }
    if (!preferred) {
      return
    }
    this.selectedToolDetailView = view
    this.selectedToolMessage = preferred
    this.showToolDetailDialog = true
  }

  private formatToolDetail (item: AiChatMessage): string {
    const metadata = item.metadata || {}
    const messageType = String(item.messageType || '').toUpperCase()
    const callId = String(metadata.toolCallId || metadata.callId || '').trim()
    if (this.selectedToolDetailView === 'params') {
      return this.stringifyToolValue(this.toolInputOf(metadata, callId))
    }
    if (messageType === 'TOOL_CALL') {
      return this.stringifyToolValue(this.toolInputOf(metadata, callId))
    }
    if (messageType === 'TOOL_RESULT') {
      const result = metadata.toolResult ?? metadata.result ?? item.content ?? ''
      return this.stringifyToolValue(result)
    }
    return this.stringifyToolValue('')
  }

  private async copySelectedToolDetail () {
    const text = this.selectedToolDetailCopyText
    if (!text.trim()) {
      this.$message.warning(i18n.t('modules.views.aiPlatform.chat.s_fb391a9f') as string)
      return
    }
    try {
      await navigator.clipboard.writeText(text)
      this.$message.success(i18n.t('modules.views.aiPlatform.chat.s_52e6abbe') as string)
    } catch {
      const textarea = document.createElement('textarea')
      textarea.value = text
      textarea.setAttribute('readonly', 'true')
      textarea.style.position = 'fixed'
      textarea.style.left = '-9999px'
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
      this.$message.success(i18n.t('modules.views.aiPlatform.chat.s_52e6abbe') as string)
    }
  }

  private routeSessionId (): string {
    const value = this.$route.query.sessionId
    return String(Array.isArray(value) ? value[0] : value || '').trim()
  }

  private syncSessionIdToRoute (sessionId: string) {
    const normalized = String(sessionId || '').trim()
    if (!normalized || normalized === this.routeSessionId()) {
      return
    }
    const navigation = this.$router.replace({
      path: this.$route.path,
      query: { ...this.$route.query, sessionId: normalized },
    })
    // vue-router 3.0.x 的 replace 不返回 Promise，不能直接链式 .catch
    if (navigation && typeof navigation.catch === 'function') {
      navigation.catch(() => undefined)
    }
  }

  private toolInputOf (metadata: Record<string, unknown>, callId: string): unknown {
    if (metadata.toolInput !== undefined && String(metadata.toolInput).trim()) {
      return metadata.toolInput
    }
    if (!callId) {
      return {}
    }
    const callMessage = this.messages.find(row => {
      return String(row.messageType || '').toUpperCase() === 'TOOL_CALL'
        && String(row.metadata?.toolCallId || row.metadata?.callId || '').trim() === callId
    })
    if (callMessage?.metadata?.toolInput !== undefined && String(callMessage.metadata.toolInput).trim()) {
      return callMessage.metadata.toolInput
    }
    const resultMessage = this.messages.find(row => {
      return String(row.messageType || '').toUpperCase() === 'TOOL_RESULT'
        && String(row.metadata?.toolCallId || row.metadata?.callId || '').trim() === callId
    })
    return resultMessage?.metadata?.toolInput ?? {}
  }

  private stringifyToolValue (value: unknown): string {
    return stringifyToolValue(value)
  }

  private normalizeToolValue (value: unknown): unknown {
    return normalizeToolValue(value)
  }

  private extractTrendChartsFromToolMessage (item: AiChatMessage | null): Array<Record<string, unknown>> {
    if (!item) {
      return []
    }
    const metadata = item.metadata || {}
    const messageType = String(item.messageType || '').toUpperCase()
    const toolName = String(metadata.toolName || '').trim()
    const value = messageType === 'TOOL_CALL'
      ? metadata.toolInput
      : metadata.toolResult
    const charts = this.extractTrendCharts(value)
    if (!charts.length) {
      return []
    }
    if (!this.isDrawTrendChartsTool(toolName) && !this.valueLooksLikeTrendCharts(value)) {
      return []
    }
    return charts
  }

  private extractTrendCharts (value: unknown): Array<Record<string, unknown>> {
    const normalized = this.normalizeToolValue(value)
    if (Array.isArray(normalized)) {
      return normalized.filter(this.isTrendChartItem) as Array<Record<string, unknown>>
    }
    if (!this.isPlainObject(normalized)) {
      return []
    }
    const payload = normalized as Record<string, unknown>
    if (Array.isArray(payload.charts)) {
      return payload.charts.filter(this.isPlainObject) as Array<Record<string, unknown>>
    }
    if (String(payload.chartType || '').toLowerCase() === 'trend') {
      return [payload]
    }
    const data = payload.data
    if (this.isPlainObject(data) && Array.isArray((data as Record<string, unknown>).charts)) {
      return ((data as Record<string, unknown>).charts as unknown[])
        .filter(this.isPlainObject) as Array<Record<string, unknown>>
    }
    return []
  }

  private valueLooksLikeTrendCharts (value: unknown): boolean {
    const normalized = this.normalizeToolValue(value)
    if (Array.isArray(normalized)) {
      return normalized.some(item => this.isTrendChartItem(item))
    }
    return this.isTrendChartItem(normalized)
  }

  private isTrendChartItem (value: unknown): value is Record<string, unknown> {
    if (!this.isPlainObject(value)) {
      return false
    }
    const item = value as Record<string, unknown>
    const chartType = String(item.chartType || '').toLowerCase()
    if (chartType === 'trend' || chartType === 'trendbatch') {
      return true
    }
    if (Array.isArray(item.labels) && Array.isArray(item.values)) {
      return true
    }
    if (Array.isArray(item.charts)) {
      return item.charts.some(chart => this.isTrendChartItem(chart))
    }
    return false
  }

  private isDrawTrendChartsTool (toolName: string): boolean {
    const normalized = String(toolName || '').trim()
    return normalized === 'drawTrendCharts'
      || normalized === 'data.drawTrendCharts'
      || normalized.endsWith('.drawTrendCharts')
  }

  private isPlainObject (value: unknown): value is Record<string, unknown> {
    return value !== null && typeof value === 'object' && !Array.isArray(value)
  }

  private onPageWheel = (event: WheelEvent) => {
    if (!this.conversationStarted) {
      return
    }
    const box = this.$refs.messageBox
    if (!box) {
      return
    }
    const target = event.target
    if (!(target instanceof Element)) {
      return
    }
    if (box.contains(target)) {
      return
    }
    if (target.closest('textarea, .el-textarea__inner, .el-select-dropdown, .el-dropdown-menu, .tool-detail-content')) {
      return
    }
    event.preventDefault()
    box.scrollTop += event.deltaY
  }

  private onMessageScroll = debounce(() => {
    const box = this.$refs.messageBox
    if (!box) {
      return
    }
    const { scrollHeight, clientHeight, scrollTop } = box
    if (scrollTop >= scrollHeight - clientHeight - 8) {
      this.isUserScrolling = false
    } else if (scrollTop < this.lastScrollTop) {
      this.isUserScrolling = true
    }
    this.lastScrollTop = scrollTop
  }, 100)

  private scrollToBottom (force = false) {
    this.$nextTick(() => {
      const box = this.$refs.messageBox
      if (!box || (this.isUserScrolling && !force)) {
        return
      }
      box.scrollTop = box.scrollHeight
    })
  }

  private centerThinkingProcess (target?: Element) {
    this.$nextTick(() => {
      const box = this.$refs.messageBox
      const element = target instanceof Element ? target : null
      if (!box || !element) {
        return
      }
      const boxRect = box.getBoundingClientRect()
      const elementRect = element.getBoundingClientRect()
      const offsetTop = elementRect.top - boxRect.top + box.scrollTop
      const nextTop = Math.max(0, offsetTop - (box.clientHeight - elementRect.height) / 2)
      box.scrollTop = nextTop
      this.isUserScrolling = true
      this.lastScrollTop = nextTop
    })
  }

}
</script>

<style lang="scss" scoped>
.chat-page {
  flex: 1;
  min-height: 0;
  height: 100%;
  padding: 18px 24px 22px;
  position: relative;
  display: flex;
  flex-direction: column;
  background:
    radial-gradient(circle at 14% 9%, rgba(47, 108, 255, 0.18), transparent 31%),
    radial-gradient(circle at 84% 15%, rgba(24, 184, 144, 0.14), transparent 27%),
    radial-gradient(circle at 70% 86%, rgba(233, 140, 222, 0.12), transparent 25%),
    linear-gradient(180deg, #f3f6ff 0%, #f8fafc 55%, #f5f8fc 100%);
  overflow: hidden;
  box-sizing: border-box;

  &::before {
    content: '';
    position: absolute;
    inset: 0;
    pointer-events: none;
    background-image:
      linear-gradient(rgba(83, 101, 132, 0.045) 1px, transparent 1px),
      linear-gradient(90deg, rgba(83, 101, 132, 0.045) 1px, transparent 1px);
    background-size: 34px 34px;
    mask-image: linear-gradient(180deg, rgba(0, 0, 0, 0.82), transparent 78%);
  }
}

.chat-page.chat-started {
  background:
    linear-gradient(180deg, #f6f8fc 0%, #fbfcff 52%, #f7f9fc 100%);

  &::before {
    opacity: 0;
  }

  .input-wrap {
    padding: 0;
    border: 1px solid #dfe7f2;
    box-shadow: 0 12px 30px rgba(43, 55, 86, 0.09);
  }

  .input-effect {
    display: none;
  }

  .input-area {
    border: none;
    background: #fff;
  }

  .chat-card {
    min-height: 100%;
  }

  .footer {
    position: sticky;
    bottom: 0;
    z-index: 2;
    padding-top: 8px;
    background: linear-gradient(180deg, rgba(247, 249, 252, 0) 0%, #f7f9fc 28%, #f7f9fc 100%);
  }
}

.header-fixed {
  z-index: 4;
  display: flex;
  justify-content: space-between;
  flex: none;
  margin-bottom: 4px;
}

.header-actions {
  display: flex;
  align-items: center;

  .header-icon {
    width: 34px;
    height: 34px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    font-size: 17px;
    padding: 0;
    cursor: pointer;
    color: #536076;
    border: 1px solid rgba(213, 222, 238, 0.9);
    border-radius: 10px;
    background: rgba(255, 255, 255, 0.68);
    box-shadow: 0 8px 20px rgba(47, 61, 92, 0.08);
    transition: background-color 0.2s ease, color 0.2s ease, border-color 0.2s ease, transform 0.2s ease;

    &:hover {
      border-color: rgba(41, 98, 255, 0.38);
      background: #fff;
      color: #2962ff;
      transform: translateY(-1px);
    }
  }

  .header-icon + .header-icon {
    margin-left: 8px;
  }
}

.chat-layout {
  flex: 1;
  min-height: 0;
  width: 100%;
  display: flex;
  position: relative;
  z-index: 1;
}

.chat-layout-scroll {
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }
}

.chat-card {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.chat-main {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.chat-main-idle {
  justify-content: center;
  gap: 22px;
}

.header-hero {
  flex: none;
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 4px 12px 16px;
  max-height: 360px;
  opacity: 1;
  transform: translateY(0);
  transition: opacity 0.35s ease, transform 0.35s ease, max-height 0.35s ease, padding 0.35s ease;
  overflow: hidden;
}

.hero-visual {
  position: relative;
  width: 112px;
  height: 112px;
  margin-bottom: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.hero-ring {
  position: absolute;
  border-radius: 999px;
  pointer-events: none;
}

.hero-ring-one {
  inset: 2px;
  border: 1px solid rgba(41, 98, 255, 0.18);
  background: radial-gradient(circle, rgba(255, 255, 255, 0.94), rgba(236, 244, 255, 0.82));
  box-shadow: 0 22px 42px rgba(41, 98, 255, 0.16);
}

.hero-ring-two {
  inset: 18px;
  border: 1px solid rgba(23, 184, 144, 0.16);
  background:
    linear-gradient(135deg, rgba(47, 108, 255, 0.14), rgba(23, 184, 144, 0.08)),
    #fff;
}

.header-logo {
  position: relative;
  z-index: 1;
  width: 68px;
  height: 68px;
  border-radius: 18px;
  object-fit: contain;
  filter: drop-shadow(0 12px 20px rgba(41, 98, 255, 0.18));
}

.header-user-meta {
  line-height: 1.45;
  text-align: center;
}

.header-hero-hidden {
  opacity: 0;
  transform: translateY(-24px);
  max-height: 0;
  padding-top: 0;
  padding-bottom: 0;
  pointer-events: none;
}

.welcome-text {
  color: #111827;
  font-size: 30px;
  font-weight: 750;
  line-height: 1;
  text-align: center;
  margin-bottom: 12px;
}

.service-stats {
  color: #5f6f86;
  font-size: 14px;
  line-height: 1.45;
  text-align: center;
}

.service-count {
  color: #2962ff;
  font-weight: 800;
}

.message-list {
  flex: 1;
  width: 100%;
  max-width: 1180px;
  margin: 0 auto;
  padding: 20px 8px 22px;
}

.footer {
  flex: none;
  width: 100%;
  max-width: 1180px;
  margin: 0 auto;
  padding-bottom: 8px;
}

.llm-alert {
  margin-bottom: 12px;
}

.llm-alert-link {
  color: var(--color-primary, #409eff);
  margin-left: 4px;
}

.input-wrap {
  position: relative;
  z-index: 1;
  overflow: hidden;
  border-radius: 18px;
  padding: 1px;
  box-shadow: 0 24px 58px rgba(43, 55, 86, 0.16);
}

.input-effect {
  position: absolute;
  z-index: -1;
  background-image: conic-gradient(from 180deg, #5aefff, #2962ff, #17b890, #e98cde, #5aefff);
  width: 360%;
  height: 360%;
  opacity: 0.42;
  filter: blur(34px);
  transform: translate(-50%, -50%);
  will-change: top, left;
  animation: gradient-circle 5s linear infinite;
}

.input-area {
  position: relative;
  border: 1px solid rgba(225, 232, 244, 0.82);
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(249, 252, 255, 0.96)),
    #fff;
  border-radius: 17px;
}

.input-chat {
  :deep(.el-textarea__inner) {
    border: none;
    resize: none;
    padding: 18px 20px 12px;
    font-size: 14px;
    line-height: 24px;
    color: #1f2329;
    background: transparent;
    box-shadow: none;

    &::placeholder {
      color: #b7bcc7;
    }

    &:focus {
      box-shadow: none;
    }
  }
}

.composer-actions {
  padding: 0 18px 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.composer-actions-center {
  min-width: 0;
}

.input-wrap.has-upload {
  .input-chat :deep(.el-textarea__inner) {
    border-radius: 0 0 6px 6px;
  }
}

.hidden-file-input {
  display: none;
}

.upload-dropdown {
  flex: none;
}

.upload-add-btn {
  width: 30px;
  height: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  border: 1px solid #d8e1f1;
  background: #f8fbff;
  color: #4b5563;
  transition: all 0.2s;
}

.upload-add-btn:hover {
  border-color: #2962ff;
  color: #2962ff;
}

.upload-tip-icon {
  margin-left: 4px;
  color: #98a2b3;
}

.composer-divider {
  height: 20px;
  margin: 0 4px;
}

.composer-actions-center {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  overflow-x: auto;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }
}

.chat-model-select {
  flex: none;
  width: 190px;

  :deep(.el-input__inner) {
    height: 32px;
    line-height: 32px;
    border-radius: 999px;
    border-color: rgba(41, 98, 255, 0.18);
    color: #24324b;
    background: linear-gradient(180deg, #fff, #f6f9ff);
    font-size: 12px;
    font-weight: 600;
    box-shadow: 0 4px 14px rgba(41, 98, 255, 0.08);
  }

  :deep(.el-input__suffix) {
    right: 8px;
  }
}

.model-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

.model-option-provider {
  flex: none;
  color: #64748b;
  font-size: 12px;
}

.model-option-name {
  min-width: 0;
  color: #1e293b;
  font-weight: 600;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.expert-trigger-btn {
  flex: none;
  height: 32px;
  padding: 0 12px;
  border: 1px solid transparent;
  border-radius: 100px;
  color: #34445e;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  line-height: 16px;
  cursor: pointer;
  background: #f7faff;
  transition: all 0.2s ease;

  &:hover {
    border-color: rgba(41, 98, 255, 0.22);
    background: #eef5ff;
    color: #2962ff;
  }

  &.is-active {
    border-color: rgba(41, 98, 255, 0.28);
    background: #eaf1ff;
    color: #2962ff;
  }

  &.is-disabled {
    cursor: not-allowed;
    opacity: 0.55;
  }
}

.expert-trigger-btn-text {
  max-width: 120px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.message-attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.message-attachment-image {
  width: 96px;
  height: 96px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.6);
}

.message-attachment-file {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.65);
  font-size: 12px;
  color: #475a7d;
}

.message-generated-files {
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  background: rgba(41, 98, 255, 0.06);
  border: 1px dashed rgba(41, 98, 255, 0.25);
}

.generated-files-title {
  font-size: 12px;
  font-weight: 600;
  color: #2962ff;
  margin-bottom: 8px;
}

.generated-file-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  font-size: 13px;
  color: #1f2937;
}

.generated-file-item + .generated-file-item {
  border-top: 1px solid rgba(41, 98, 255, 0.12);
}

.generated-file-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.generated-file-size {
  font-size: 12px;
  color: #6b7280;
}

.plain-text {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 14px;
  line-height: 1.72;
}

.composer-actions-right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
}

.input-count {
  font-size: 12px;
  color: #909399;
  line-height: 1;
}

.send-icon {
  flex: none;
}

.suggestion-panel {
  margin-top: 18px;
  padding: 2px 0 8px;
}

.suggestion-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.suggestion-title {
  font-size: 13px;
  font-weight: 700;
  color: #40516d;
}

.suggestion-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.suggestion-item {
  width: 100%;
  min-height: 58px;
  padding: 13px 14px;
  border: 1px solid rgba(222, 229, 242, 0.88);
  border-radius: 14px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.94), rgba(249, 252, 255, 0.9)),
    #fff;
  color: #354864;
  font-size: 13px;
  line-height: 20px;
  display: flex;
  align-items: center;
  gap: 10px;
  animation: suggestion-slide-in 0.34s ease both;
  box-shadow: 0 10px 22px rgba(43, 55, 86, 0.06);
  transition: background-color 0.2s ease, border-color 0.2s ease, transform 0.2s ease, box-shadow 0.2s ease;

  &:hover {
    border-color: rgba(41, 98, 255, 0.28);
    background: #fff;
    box-shadow: 0 16px 32px rgba(41, 98, 255, 0.12);
    transform: translateY(-2px);
  }

  .el-icon-arrow-right {
    flex: none;
    color: #8da0bb;
  }
}

.suggestion-index {
  flex: none;
  width: 24px;
  height: 24px;
  border-radius: 9px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 800;
  color: #1f5eff;
  background: #eaf1ff;
}

.suggestion-copy {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.suggestion-item:nth-child(2n) {
  .suggestion-index {
    color: #0f9f75;
    background: rgba(23, 184, 144, 0.12);
  }
}

.char-item {
  display: flex;
  align-items: flex-start;
  gap: 0;
  margin-bottom: 28px;

  .item-content {
    min-height: 44px;
    color: #1f2937;
    padding: 0;
    font-size: 13px;
    line-height: 1.75;
    border: none;
    box-shadow: none;
    overflow: visible;
    background: transparent;
  }
}

.message-avatar {
  display: none;
}

.assistant-avatar {
  color: #1f5eff;
  border: 1px solid rgba(41, 98, 255, 0.18);
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.96), rgba(236, 243, 255, 0.92)),
    #fff;
  box-shadow: 0 10px 20px rgba(41, 98, 255, 0.1);
}

.user-avatar {
  color: #fff;
  background: linear-gradient(135deg, #2f6cff 0%, #1f57dc 100%);
  box-shadow: 0 10px 20px rgba(41, 98, 255, 0.18);
}

.message-stack {
  max-width: 100%;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.thinking-stack {
  gap: 12px;
}

.expert-process-segment {
  padding-left: 8px;
  border-left: 2px solid rgba(64, 158, 255, 0.35);
}

.message-meta {
  height: 20px;
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  gap: 8px;
  color: #7a889e;
  font-size: 12px;
  line-height: 20px;
}

.message-author {
  color: #1e293b;
  font-weight: 700;
  font-size: 13px;

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

.message-time {
  color: #8b98aa;
}

.ask-item {
  justify-content: flex-end;

  .message-stack {
    align-items: flex-end;
    max-width: 72%;
  }

  .message-meta {
    justify-content: flex-end;
  }

  .message-author::before {
    background: linear-gradient(135deg, #17b890, #08be7e);
    box-shadow: 0 0 0 3px rgba(8, 190, 126, 0.12);
  }

  .item-content {
    max-width: 100%;
  }

  .plain-text {
    min-height: 36px;
    padding: 12px 16px;
    border-radius: 16px 16px 4px 16px;
    border: 1px solid rgba(191, 219, 254, 0.85);
    color: #0f172a;
    background:
      linear-gradient(135deg, rgba(239, 246, 255, 0.98), rgba(219, 234, 254, 0.92));
    box-shadow:
      0 1px 2px rgba(15, 23, 42, 0.04),
      0 8px 20px rgba(41, 98, 255, 0.08);
  }
}

.answer-item {
  justify-content: flex-start;

  .message-stack {
    width: 100%;
    max-width: 1040px;
  }

  .item-content.has-thinking,
  .item-content.has-answer,
  .item-content.loading-content {
    width: 100%;
    padding: 14px 16px 16px;
    border-radius: 16px;
    border: 1px solid rgba(226, 232, 240, 0.95);
    background:
      linear-gradient(180deg, rgba(255, 255, 255, 0.98) 0%, rgba(248, 250, 252, 0.94) 100%);
    box-shadow:
      0 1px 2px rgba(15, 23, 42, 0.04),
      0 10px 28px rgba(15, 23, 42, 0.05);
  }

  &.last-answer .item-content.has-thinking,
  &.last-answer .item-content.has-answer,
  &.last-answer .item-content.loading-content {
    border-color: rgba(41, 98, 255, 0.16);
    box-shadow:
      0 1px 2px rgba(15, 23, 42, 0.04),
      0 12px 32px rgba(41, 98, 255, 0.08);
  }
}

.has-thinking.has-answer .answer-body {
  margin-top: 4px;
  padding-top: 16px;
  border-top: 1px solid rgba(226, 232, 240, 0.92);
}

.answer-charts {
  margin-bottom: 14px;
}

.has-thinking.has-chart .answer-charts {
  padding-top: 14px;
  border-top: 1px solid rgba(226, 232, 240, 0.92);
}

.has-chart.has-answer .answer-body {
  padding-top: 14px;
  border-top: 1px solid rgba(226, 232, 240, 0.92);
}

.answer-body {
  min-width: 0;
}

.inline-thinking-loader {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-height: 36px;
  padding: 8px 14px 8px 10px;
  border-radius: 12px;
  border: 1px solid rgba(41, 98, 255, 0.14);
  background: linear-gradient(135deg, rgba(41, 98, 255, 0.05), rgba(255, 255, 255, 0.9));
}

.loader-badge {
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;

  .chat-loading {
    margin: 0;
  }
}

.loader-text {
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
  font-size: 13px;
  font-weight: 600;
}

@keyframes thinking-shimmer {
  0% { background-position: 100% 0; }
  100% { background-position: 0 0; }
}

.item-marked-cont {
  color: #1f2937;
  font-size: 14px;
  line-height: 1.72;

  :deep(p) {
    margin: 0 0 12px;
  }

  :deep(p:last-child),
  :deep(ul:last-child),
  :deep(ol:last-child) {
    margin-bottom: 0;
  }

  :deep(ul),
  :deep(ol) {
    margin: 10px 0 14px;
    padding-left: 18px;
  }

  :deep(li) {
    margin: 6px 0;
    padding-left: 4px;
  }

  :deep(strong) {
    color: #152238;
    font-weight: 700;
  }

  :deep(code) {
    margin: 0 2px;
    padding: 2px 6px;
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    color: #26364d;
    background: #f5f7fa;
  }

  :deep(pre) {
    margin: 12px 0;
    border-radius: 10px;
    overflow: hidden;
  }

  :deep(pre code) {
    border: none;
    border-radius: 10px;
    background: #1f2937;
    color: #d7dee8;
  }

  :deep(table) {
    margin: 12px 0;
    border: 1px solid #e5edf7;
    border-radius: 10px;
    overflow: hidden;
  }

  :deep(th) {
    background: #f6f8fb;
    font-weight: 700;
  }
}

.thinking-box {
  padding: 8px 12px;
  border-radius: 10px;
  display: inline-flex;
  align-items: center;
  color: #40516d;
  background: #f5f8fc;
}

.loading-content {
  padding: 14px 16px !important;
}

.chat-loading {
  display: inline-flex;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  position: relative;
  margin-right: 8px;
  animation: rotating 1s linear infinite;

  &::before {
    content: '';
    width: 100%;
    height: 100%;
    border-radius: 50%;
    background: conic-gradient(#2962ff, rgba(0, 0, 0, 0));
    scale: 0.812;
    position: absolute;
    top: 0;
    left: 0;
  }

  &::after {
    content: '';
    width: 100%;
    height: 100%;
    border-radius: 50%;
    background: #fff;
    scale: 0.555;
    position: absolute;
    top: 0;
    left: 0;
  }
}

.drawer-title {
  display: flex;
  align-items: center;
  font-size: 16px;
  font-weight: 600;
  color: #24324b;
}

.tool-detail-title {
  width: 100%;
  justify-content: space-between;
  gap: 12px;
}

.tool-detail-title-main {
  min-width: 0;
  display: flex;
  align-items: center;
}

.tool-detail-copy {
  flex: none;
  margin-right: 28px;
}

.drawer-star {
  width: 16px;
  height: 16px;
  margin-right: 8px;
}

.drawer-toolbar {
  display: flex;
  gap: 8px;
  padding: 0 16px 12px;
}

.session-list {
  flex: 1;
  min-height: 0;
  padding: 0 16px 16px;
  overflow-y: auto;
}

.session-item {
  padding: 12px;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 8px;
  border: 1px solid transparent;
  background: #fff;
  transition: background-color 0.2s ease, border-color 0.2s ease;

  &.active {
    background: #eaefff;
    border-color: #c7d7ff;
  }

  &:hover {
    background: #f2f7ff;
  }
}

.session-title {
  font-size: 14px;
  word-break: break-all;
  color: #24324b;
}

.session-meta {
  color: #7a8599;
  font-size: 12px;
  margin-top: 4px;
  word-break: break-all;
}

.empty-tip {
  color: #98a2b3;
  font-size: 13px;
  padding: 24px 0;
  text-align: center;
}

:deep(.tool-detail-dialog) {
  border-radius: 16px;
  overflow: hidden;

  .el-dialog__header {
    padding: 18px 20px 14px;
    border-bottom: 1px solid #eef2f7;
    background: linear-gradient(180deg, #fafbfd, #fff);
  }

  .el-dialog__body {
    padding: 16px 20px 20px;
    background: #f8fafc;
  }

  .el-dialog__headerbtn {
    top: 18px;
    right: 18px;
  }
}

.tool-detail-sep {
  margin: 0 10px;
  font-weight: normal;
  color: #cbd5e1;
}

.tool-detail-name {
  font-weight: 500;
  color: #475569;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 14px;
}

.tool-detail-status {
  font-weight: 600;
  font-size: 13px;

  &.is-success {
    color: #08be7e;
  }

  &.is-failed {
    color: #ef4444;
  }
}

.tool-detail-duration {
  font-weight: 500;
  color: #64748b;
  font-size: 13px;
}

.tool-detail-terminal {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.tool-detail-terminal-meta {
  padding: 0 4px;
  color: #94a3b8;
  background: transparent;
  font-size: 11px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;

  &.is-prefix {
    margin-bottom: 2px;
  }
}

.tool-detail-content {
  max-height: 520px;
  margin: 0;
  padding: 16px;
  overflow: auto;
  border: 1px solid #1e293b;
  border-radius: 8px;
  color: #e2e8f0;
  background: #0f172a;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.04);

  &.is-terminal {
    max-height: 460px;
    white-space: pre;
    word-break: normal;
    overflow-x: auto;
  }

  &::-webkit-scrollbar {
    width: 6px;
    height: 6px;
  }

  &::-webkit-scrollbar-thumb {
    border-radius: 999px;
    background: rgba(148, 163, 184, 0.35);
  }
}

.tool-detail-charts {
  max-height: 560px;
  overflow: auto;
  padding-right: 4px;
}

@keyframes gradient-circle {
  0% { top: 0; left: 0; }
  40% { top: 0; left: 100%; }
  50% { top: 100%; left: 100%; }
  90% { top: 100%; left: 0; }
  100% { top: 0; left: 0; }
}

@keyframes suggestion-slide-in {
  0% {
    opacity: 0;
    transform: translateX(-18px);
  }
  100% {
    opacity: 1;
    transform: translateX(0);
  }
}

@keyframes rotating {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

@media (max-width: 1180px) {
  .footer,
  .message-list {
    max-width: 100%;
  }
}

@media (max-width: 820px) {
  .chat-page {
    padding: 14px;
  }

  .welcome-text {
    font-size: 24px;
    line-height: 1.25;
  }

  .hero-visual {
    width: 92px;
    height: 92px;
    margin-bottom: 18px;
  }

  .header-logo {
    width: 56px;
    height: 56px;
  }

  .suggestion-list {
    grid-template-columns: 1fr;
  }

  .composer-actions {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .composer-actions-center {
    order: 3;
    width: 100%;
  }

  .ask-item .message-stack,
  .answer-item .message-stack {
    max-width: 100%;
  }

  .char-item .item-content {
    max-width: 100%;
  }
}
</style>

<style lang="scss">
.ai-chat-drawer {
  .el-drawer__header {
    margin-bottom: 0;
    padding: 18px 20px;
    border-bottom: 1px solid #eef1f7;
  }

  .el-drawer__body {
    padding: 16px 0 0;
    overflow: hidden;
    display: flex;
    flex-direction: column;
    background: #f8f9fc;
  }
}

.chat-expert-overflow-popover {
  padding: 0;
  border: 1px solid #e7edf7;
  border-radius: 12px;
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.12);

  .expert-overflow-panel {
    width: 220px;
    padding: 10px;
  }

  .el-input__inner {
    border-radius: 8px;
    border-color: #e7edf7;
    background: #f8faff;
  }

  .expert-overflow-list {
    margin-top: 8px;
    max-height: 240px;
    overflow-y: auto;
  }

  .expert-overflow-item {
    display: flex;
    align-items: center;
    gap: 8px;
    min-height: 36px;
    padding: 0 10px;
    border-radius: 8px;
    color: #34445e;
    font-size: 13px;
    cursor: pointer;
    transition: background 0.15s ease, color 0.15s ease;

    &:hover {
      background: #f3f7ff;
      color: #2962ff;
    }

    &.is-active {
      background: #eaf1ff;
      color: #2962ff;
    }
  }

  .expert-overflow-item-text {
    min-width: 0;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  .expert-overflow-empty {
    padding: 12px 10px;
    color: #98a2b3;
    font-size: 12px;
    text-align: center;
  }
}
</style>
