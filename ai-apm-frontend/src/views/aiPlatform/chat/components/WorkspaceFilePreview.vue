<template>
  <el-drawer
    :visible.sync="drawerVisible"
    append-to-body
    :size="drawerSize"
    direction="rtl"
    :with-header="false"
    :wrapper-closable="true"
    :custom-class="'workspace-file-preview-drawer' + (expanded ? ' is-expanded' : '')"
    @closed="onClosed"
  >
    <div class="preview-shell">
      <div class="preview-toolbar">
        <div class="preview-title" :title="fileName">{{ fileName }}</div>
        <div class="preview-actions">
          <el-button type="text" size="small" @click="toggleExpanded">
            <i :class="expanded ? 'el-icon-copy-document' : 'el-icon-full-screen'" />
            {{ expanded ? $t('modules.views.aiPlatform.chat.s_69de8d7f') : $t('modules.views.aiPlatform.chat.s_4f9b192c')  }}
          </el-button>
          <el-button type="text" size="small" icon="el-icon-download" @click="$emit('download')">
            {{ $t('modules.views.configStatus.runLog.s_f26ef914') }}
          </el-button>
          <el-button type="text" size="small" icon="el-icon-close" @click="drawerVisible = false" />
        </div>
      </div>
      <div v-loading="loading" class="preview-body">
        <iframe
          v-if="(previewKind === 'html' || previewKind === 'svg') && src"
          :src="src"
          class="preview-frame"
          :title="fileName"
          sandbox="allow-scripts allow-same-origin"
        />
        <div
          v-else-if="previewKind === 'markdown' && textContent"
          class="preview-markdown"
        >
          <marked-view :data="textContent" />
        </div>
        <pre v-else-if="previewKind === 'text' && textContent" class="preview-text">{{ textContent }}</pre>
        <div v-else-if="error" class="preview-empty">{{ error }}</div>
        <div v-else class="preview-empty">{{ $t('modules.views.aiPlatform.chat.s_93177327') }}</div>
      </div>
    </div>
  </el-drawer>
</template>

<script lang="ts">
import i18n from '@/i18n';
import { Vue, Component, Prop, Watch } from 'vue-property-decorator'
import AiPlatformApi from '@/api/aiPlatform'
import MarkedView from '@/components/marked-view.vue'

@Component({
  name: 'WorkspaceFilePreview',
  components: { MarkedView },
})
export default class WorkspaceFilePreview extends Vue {
  @Prop({ type: Boolean, default: false }) readonly visible!: boolean
  @Prop({ type: String, required: true }) readonly sessionId!: string
  @Prop({ type: String, required: true }) readonly filePath!: string
  @Prop({ type: String, default: '' }) readonly fileName!: string

  private loading = false
  private error = ''
  private src = ''
  private textContent = ''
  private objectUrl = ''
  private expanded = false

  private get drawerVisible (): boolean {
    return this.visible
  }

  private set drawerVisible (value: boolean) {
    this.$emit('update:visible', value)
    if (!value) {
      this.$emit('close')
    }
  }

  private get drawerSize (): string {
    // 默认约半屏偏宽，聊天区仍可见；放大后接近原先较宽预览尺寸
    return this.expanded ? '82%' : '64%'
  }

  /** Known binary / non-text suffixes; everything else previews as plain text. */
  private static readonly BINARY_EXT_RE = /\.(png|jpe?g|gif|webp|ico|bmp|tiff?|pdf|zip|tar|t?gz|bz2|xz|7z|rar|jar|war|ear|class|exe|dll|so|dylib|bin|wasm|mp[34]|m4a|wav|flac|avi|mov|mkv|webm|woff2?|ttf|otf|eot|parquet|avro|orc|pickle|pyc|pyo|o|a|obj|dmg|iso|apk|ipa)$/

  private get previewKind (): 'html' | 'svg' | 'markdown' | 'text' | 'other' {
    const name = (this.fileName || this.filePath || '').toLowerCase()
    if (/\.(html?|htm)$/.test(name)) {
      return 'html'
    }
    if (/\.svg$/.test(name)) {
      return 'svg'
    }
    if (/\.(md|markdown)$/.test(name)) {
      return 'markdown'
    }
    // Text/code files (.py, .sh, .yml, .json, .txt, Dockerfile, …) all preview online.
    if (!WorkspaceFilePreview.BINARY_EXT_RE.test(name)) {
      return 'text'
    }
    return 'other'
  }

  @Watch('visible')
  private onVisibleChange (value: boolean) {
    if (value) {
      this.loadPreview()
    } else {
      this.expanded = false
      this.revokeObjectUrl()
    }
  }

  @Watch('filePath')
  private onFilePathChange () {
    if (this.visible) {
      this.loadPreview()
    }
  }

  private beforeDestroy () {
    this.revokeObjectUrl()
  }

  private toggleExpanded () {
    this.expanded = !this.expanded
  }

  private onClosed () {
    this.expanded = false
    this.revokeObjectUrl()
  }

  private revokeObjectUrl () {
    if (this.objectUrl) {
      URL.revokeObjectURL(this.objectUrl)
      this.objectUrl = ''
    }
    this.src = ''
  }

  private async loadPreview () {
    this.revokeObjectUrl()
    this.textContent = ''
    this.error = ''
    if (!this.sessionId || !this.filePath) {
      return
    }
    if (this.previewKind === 'other') {
      return
    }
    this.loading = true
    try {
      const response: any = await AiPlatformApi.downloadWorkspaceFile(this.sessionId, this.filePath, {
        preview: true,
      })
      const blob = response?.data instanceof Blob ? response.data : new Blob([response?.data || ''])
      if (this.previewKind === 'html' || this.previewKind === 'svg') {
        const mimeType = this.previewKind === 'svg'
          ? 'image/svg+xml'
          : 'text/html;charset=utf-8'
        const previewBlob = blob.type && (
          blob.type.includes('html') || blob.type.includes('svg')
        )
          ? blob
          : new Blob([await blob.text()], { type: mimeType })
        this.objectUrl = URL.createObjectURL(previewBlob)
        this.src = this.objectUrl
      } else {
        this.textContent = await blob.text()
      }
    } catch (e: any) {
      this.error = e?.message || i18n.t('modules.views.aiPlatform.chat.s_c2d94c82') as string
    } finally {
      this.loading = false
    }
  }
}
</script>

<style lang="scss">
.workspace-file-preview-drawer {
  &.el-drawer {
    max-width: min(1100px, 100vw);
  }

  &.is-expanded.el-drawer {
    max-width: 100vw;
  }

  .el-drawer__body {
    padding: 0 !important;
    height: 100%;
    overflow: hidden;
    display: flex;
    flex-direction: column;
  }
}

.workspace-file-preview-drawer .preview-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: #fff;
}

.workspace-file-preview-drawer .preview-toolbar {
  flex: none;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  border-bottom: 1px solid #e8ecf2;
  background: #fafbfc;
}

.workspace-file-preview-drawer .preview-title {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  font-weight: 600;
  color: #1f2a37;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workspace-file-preview-drawer .preview-actions {
  flex: none;
  display: flex;
  align-items: center;
  gap: 4px;

  .el-button {
    color: #4b5565;
    margin-left: 0;

    &:hover {
      color: #2962ff;
    }
  }
}

.workspace-file-preview-drawer .preview-body {
  flex: 1;
  min-height: 0;
  position: relative;
  background: #f5f7fa;
}

.workspace-file-preview-drawer .preview-frame {
  display: block;
  width: 100%;
  height: 100%;
  border: 0;
  background: #fff;
}

.workspace-file-preview-drawer .preview-markdown {
  height: 100%;
  overflow: auto;
  padding: 24px 28px;
  background: #fff;
}

.workspace-file-preview-drawer .preview-text {
  margin: 0;
  padding: 24px 28px;
  height: 100%;
  overflow: auto;
  font-size: 13px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
  color: #243044;
  background: #fff;
}

.workspace-file-preview-drawer .preview-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #8a94a6;
  font-size: 13px;
  padding: 24px;
  text-align: center;
}

@media (max-width: 960px) {
  .workspace-file-preview-drawer.el-drawer {
    width: 100% !important;
  }
}
</style>
