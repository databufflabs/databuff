<template>
  <div
    v-html="markedData"
    ref="markedWrap"
    :class="['marked-view-wrapper', { nocopy: !showCopy }]"></div>
</template>

<script lang="ts">
/**
 * Markdown 渲染组件。
 *
 * 注意：禁止在 marked.parse 之前对 data 做字符串预处理（正则替换、normalize 等）。
 * LLM 输出应原样交给 marked；预处理极易误伤表格/标题/代码块，导致解析失败。
 * 格式问题应在 prompt、后端结构化输出或换渲染方案上解决，不要在前端 patch 文本。
 *
 * Mermaid：mermaid 代码块在 DOM 更新后异步渲染为 SVG；流式输出期间防抖，
 * 解析失败时保留源码占位，不阻断其余 Markdown。
 *
 * 通过 /vendor/mermaid.min.js 脚本加载（不走 Vite 打包），避免 mermaid 与
 * AntV 强制拆包产生循环依赖导致整站白屏。文件由 yarn copy:mermaid /
 * postinstall 从 node_modules 拷到 public/vendor（不入库）。
 *
 * 思考过程折叠（v-show / display:none）时不渲染；可见后再 run，避免量出
 * 16x16 残缺 SVG。
 */
import { Vue, Component, Prop, Watch } from 'vue-property-decorator'
import Clipboard from 'clipboard'
import { marked } from 'marked'
import hljs from 'highlight.js/lib/common';
import i18n from '@/i18n';
import 'highlight.js/styles/atom-one-light.css';

type MermaidApi = {
  initialize: (config: Record<string, unknown>) => void
  run: (options: { nodes: HTMLElement[] }) => Promise<void>
}

function escapeHtml (text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

const mermaidRenderer = new marked.Renderer()
const defaultCodeRenderer = mermaidRenderer.code.bind(mermaidRenderer)
mermaidRenderer.code = function (code: string, infostring: string, escaped: boolean) {
  const lang = (infostring || '').match(/^\S*/)?.[0] || ''
  if (lang === 'mermaid') {
    const body = escaped ? code : escapeHtml(code)
    // 保留原文，便于不可见时误渲染后恢复重试
    const source = encodeURIComponent(code)
    return `<div class="mermaid-diagram" data-mermaid-source="${source}"><pre class="mermaid">${body}</pre></div>\n`
  }
  return defaultCodeRenderer(code, infostring, escaped)
}

marked.setOptions({
  renderer: mermaidRenderer,
  highlight: (code: string, lang: string = 'bash') => {
    if (lang === 'mermaid') {
      // 原样交给 renderer，避免 highlight.js 改写语法
      return null as unknown as string
    }
    return hljs.highlightAuto(code).value
  },
  // langPrefix: 'hljs language-', // 代码高亮 code标签的className前缀
  gfm: true, // 允许 GitHub标准的markdown
  tables: true, // 允许支持表格语法（该选项要求 gfm 为true）
  breaks: true, // 允许回车换行（该选项要求 gfm 为true）
  pedantic: false, // 不纠正原始模型任何的不良行为和错误（默认为false）
  sanitizer: false, // 对输出进行过滤（清理），将忽略任何已经输入的html代码（标签）
  smartLists: false, // 使用比原生markdown更智能的列表
  smartypants: false, // 使用智能标点符号表示引号和破折号
})

let mermaidInitialized = false
let mermaidLoadPromise: Promise<MermaidApi> | null = null

function loadMermaid (): Promise<MermaidApi> {
  if ((window as any).mermaid) {
    return Promise.resolve((window as any).mermaid as MermaidApi)
  }
  if (!mermaidLoadPromise) {
    mermaidLoadPromise = new Promise<MermaidApi>((resolve, reject) => {
      const existing = document.querySelector<HTMLScriptElement>('script[data-databuff-mermaid]')
      if (existing) {
        existing.addEventListener('load', () => resolve((window as any).mermaid as MermaidApi))
        existing.addEventListener('error', () => reject(new Error('mermaid script load failed')))
        return
      }
      const script = document.createElement('script')
      script.src = '/vendor/mermaid.min.js'
      script.async = true
      script.dataset.databuffMermaid = '1'
      script.onload = () => {
        const api = (window as any).mermaid as MermaidApi | undefined
        if (!api) {
          reject(new Error('mermaid global missing after script load'))
          return
        }
        resolve(api)
      }
      script.onerror = () => reject(new Error('mermaid script load failed'))
      document.head.appendChild(script)
    })
  }
  return mermaidLoadPromise
}

function isElementVisible (el: HTMLElement | null | undefined): boolean {
  if (!el || !el.isConnected) {
    return false
  }
  if (el.offsetParent === null && getComputedStyle(el).position !== 'fixed') {
    // display:none 链路上 offsetParent 为 null
    return false
  }
  const style = getComputedStyle(el)
  if (style.display === 'none' || style.visibility === 'hidden') {
    return false
  }
  const rect = el.getBoundingClientRect()
  return rect.width > 0 && rect.height > 0
}

function isBrokenMermaidSvg (svg: SVGElement): boolean {
  const rect = svg.getBoundingClientRect()
  const viewBox = String(svg.getAttribute('viewBox') || '').trim()
  // 折叠态误渲染常见为 16x16 / viewBox="-8 -8 16 16"
  if (/^-?\d+(?:\.\d+)?\s+-?\d+(?:\.\d+)?\s+16(?:\.0+)?\s+16(?:\.0+)?$/.test(viewBox)) {
    return true
  }
  return rect.width > 0 && rect.height > 0 && rect.width < 40 && rect.height < 40
}

@Component
export default class MarkedView extends Vue {
  @Prop({ default: '' }) private data!: string
  @Prop({ default: 'markdown' }) private type!: string // markdown | code
  @Prop({ default: true }) private showCopy!: boolean

  public $refs!: {
    markedWrap: HTMLDivElement
  }

  private markedData: string = ''
  private mermaidRenderTimer: ReturnType<typeof setTimeout> | null = null
  private mermaidRenderToken = 0
  private mermaidVisibilityObserver: IntersectionObserver | null = null

  @Watch('data', { immediate: true })
  private watchData () {
    const data = this.type !== 'code' ? this.data : `\n\`\`\`\n${this.data}\n\`\`\`\n`
    // 直接 parse，勿预处理 — 见文件头注释
    this.markedData = marked.parse(data);
    if (this.showCopy) {
      this.initCopyButton();
    }
    this.scheduleMermaidRender()
  }

  private created() {
    if (this.showCopy) {
      this.initMarkedClipboard();
    }
  }

  private mounted () {
    this.ensureMermaidVisibilityObserver()
    this.$el.addEventListener('databuff-mermaid-retry', this.onMermaidRetry)
  }

  private beforeDestroy () {
    if (this.mermaidRenderTimer) {
      clearTimeout(this.mermaidRenderTimer)
      this.mermaidRenderTimer = null
    }
    this.mermaidRenderToken += 1
    if (this.mermaidVisibilityObserver) {
      this.mermaidVisibilityObserver.disconnect()
      this.mermaidVisibilityObserver = null
    }
    this.$el.removeEventListener('databuff-mermaid-retry', this.onMermaidRetry)
  }

  private onMermaidRetry = () => {
    this.scheduleMermaidRender()
  }

  private scheduleMermaidRender () {
    if (this.mermaidRenderTimer) {
      clearTimeout(this.mermaidRenderTimer)
    }
    // 流式输出时防抖，等代码块相对稳定再渲染
    this.mermaidRenderTimer = setTimeout(() => {
      this.mermaidRenderTimer = null
      this.renderMermaidDiagrams()
    }, 280)
  }

  private ensureMermaidVisibilityObserver () {
    if (this.mermaidVisibilityObserver || typeof IntersectionObserver === 'undefined') {
      return
    }
    this.mermaidVisibilityObserver = new IntersectionObserver((entries) => {
      const visible = entries.some(entry => entry.isIntersecting && entry.intersectionRatio > 0)
      if (visible) {
        this.scheduleMermaidRender()
      }
    }, { threshold: 0.01 })
    const wrap = this.$refs.markedWrap
    if (wrap) {
      this.mermaidVisibilityObserver.observe(wrap)
    }
  }

  private restoreBrokenMermaidDiagrams (wrap: HTMLElement) {
    wrap.querySelectorAll<HTMLElement>('.mermaid-diagram[data-mermaid-source]').forEach((diagram) => {
      const svg = diagram.querySelector('svg')
      if (!svg || !isBrokenMermaidSvg(svg as unknown as SVGElement)) {
        return
      }
      let source = ''
      try {
        source = decodeURIComponent(diagram.getAttribute('data-mermaid-source') || '')
      } catch (e) {
        return
      }
      if (!source.trim()) {
        return
      }
      diagram.innerHTML = `<pre class="mermaid">${escapeHtml(source)}</pre>`
    })
  }

  private collectPendingMermaidNodes (wrap: HTMLElement): HTMLElement[] {
    this.restoreBrokenMermaidDiagrams(wrap)
    return Array.from(wrap.querySelectorAll<HTMLElement>('pre.mermaid:not([data-processed])'))
  }

  private async renderMermaidDiagrams () {
    const token = ++this.mermaidRenderToken
    await this.$nextTick()
    if (token !== this.mermaidRenderToken) {
      return
    }
    const wrap = this.$refs.markedWrap
    if (!wrap) {
      return
    }
    this.ensureMermaidVisibilityObserver()
    if (this.mermaidVisibilityObserver) {
      this.mermaidVisibilityObserver.observe(wrap)
    }

    // 思考过程收起等不可见场景：先等可见，避免量出残缺图
    if (!isElementVisible(wrap)) {
      return
    }

    const nodes = this.collectPendingMermaidNodes(wrap)
    if (!nodes.length) {
      return
    }

    try {
      const mermaid = await loadMermaid()
      if (token !== this.mermaidRenderToken) {
        return
      }
      if (!isElementVisible(wrap)) {
        return
      }
      if (!mermaidInitialized) {
        const isDark = document.documentElement.getAttribute('data-theme') === 'dark'
        mermaid.initialize({
          startOnLoad: false,
          theme: isDark ? 'dark' : 'default',
          // 允许节点标签内 <br/> 等 HTML（LLM 常用换行写法）
          securityLevel: 'loose',
          fontFamily: 'inherit',
        })
        mermaidInitialized = true
      }
      await mermaid.run({ nodes })
      // 若仍因布局瞬时不可见而残缺，恢复源码等下次可见重试
      this.restoreBrokenMermaidDiagrams(wrap)
    } catch (err) {
      // 语法未闭合或非法时保留源码，不打断其余内容
      console.warn('[marked-view] mermaid render failed', err)
    }
  }

  // 插入复制按钮
  private initCopyButton () {
    this.$nextTick(() => {
      const $preList = this.$refs.markedWrap.querySelectorAll('pre')
      $preList.forEach(($pre: any) => {
        if ($pre.classList?.contains('mermaid') || $pre.closest?.('.mermaid-diagram')) {
          return
        }
        const $code = $pre.querySelector('code')
        if (!$code) {
          return
        }
        const codeText = $code.innerText
        const copyId = `marked-${Math.random().toString(36).substring(2)}`
        const copyBtn = `<button class="marked-copy-btn" :title="$t('modules.views.aiPlatform.chat.s_79d3abe9')" data-clipboard-action="copy" data-clipboard-target="#${copyId}"><span class="copy-icon db-icon-copy"></span></button><textarea id="${copyId}" style="position:absolute;top:-9999px;left:-9999px;z-index:-9999;">${codeText}</textarea>`
        $code.insertAdjacentHTML('afterend', copyBtn)
      })
    })
  }

  // 创建全局的剪切板实例
  private initMarkedClipboard () {
    if (!(window as any).markedClipboard) {
      const clipboard = new Clipboard('.marked-view-wrapper .marked-copy-btn');
      clipboard.on('success', (e: any) => {
        this.$notify({
          title: '',
          message: i18n.t('modules.components.s_a28aa67f') as string, messageKey: 'modules.components.s_a28aa67f',
          duration: 1000,
          showClose: false,
          customClass: 'notification-copy success',
        });
        e.clearSelection()
      });
      clipboard.on('error', (e: any) => {
        this.$notify({
          title: '',
          message: i18n.t('modules.components.s_cd981710') as string, messageKey: 'modules.components.s_cd981710',
          duration: 1000,
          showClose: false,
          customClass: 'notification-copy error',
        });
      });
      (window as any).markedClipboard = clipboard;
    }
  }
}
</script>

<style lang="scss">
.marked-view-wrapper {
  font-size: 13px;
  line-height: 24px;
  color: var(--color-text-primary);

  h1,h2,h3,h4,h5 {
    margin-bottom: 10px;
    font-weight: 500;
  }
  h1 {
    font-size: 22px;
  }
  h2 {
    font-size: 20px;
  }
  h3 {
    font-size: 18px;
  }
  h4 {
    font-size: 16px;
  }
  h5 {
    font-size: 14px;
  }
  h6 {
    font-size: 1em;
  }

  img,pre,table,ul,ol {
    margin: 0 0 10px;
  }

  p {
    margin: 5px 0;
  }

  img {
    display: block;
  }

  p a code,
  a {
    color: var(--color-text-link);
  }

  ul,
  ol {
    padding-left: 16px;
    list-style-type: revert;
  }

  table {
    width: 100%;
    display: table;
    border-collapse: collapse;
    border-spacing: 0;
    border-radius: 4px 4px 0 0;
    overflow: hidden;
    tr:hover {
      background-color: var(--table-hover-color);
    }
    td,
    th {
      border-bottom: 1px solid var(--border-color-lighter);
      padding: 9px 10px 8px;
      font-size: 12px;
      line-height: 22px;
      text-align: left;
      color: var(--color-text-primary);
      font-weight: normal;
    }
    th {
      background-color: var(--background-color-base);
      border-right-color: var(--border-color-light);
      font-size: 13px;
      white-space: nowrap;
    }
  }

  code {
    box-sizing: border-box;
    margin: 0 6px;
    padding: 3px 6px;
    border-radius: 4px;
    background-color: #2a2d32;
    color: #e3e8ec;
    font-size: 12px;
  }

  pre {
    box-sizing: border-box;
    font-size: 12px;
    position: relative;
    code {
      display: block;
      height: 100%;
      min-height: 40px;
      max-height: 400px;
      overflow: auto;
      margin: 0;
      padding: 10px 28px 10px 12px;
      background: #282c34;
      border-radius: 4px;
      color: #abb2bf;
      font-size: 12px;
      line-height: 1.65;
      word-break: break-all;
      white-space: pre-wrap;
    }
  }

  .mermaid-diagram {
    margin: 0 0 12px;
    padding: 12px;
    overflow-x: auto;
    border-radius: 4px;
    background-color: var(--background-color-base, #f5f6f7);
    text-align: center;

    pre.mermaid {
      margin: 0;
      padding: 0;
      background: transparent;
      color: var(--color-text-primary);
      text-align: left;
      white-space: pre-wrap;
      word-break: break-word;
      font-size: 12px;
      line-height: 1.5;
    }

    svg {
      max-width: 100%;
      height: auto;
    }
  }

  &.nocopy pre code {
    padding-right: 12px;
  }

  .marked-copy-btn {
    width: 20px;
    height: 20px;
    padding: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    background-color: transparent;
    border: none;
    color: var(--color-primary);
    user-select: none;
    outline: none;
    cursor: pointer;
    position: absolute;
    top: 10px;
    right: 6px;
    z-index: 1;
    .copy-icon {
      font-size: 12px;
    }
    &:focus .copy-icon {
      display: none;
    }
    &:focus:before {
      content: "";
      margin-top: -4px;
      display: inline-block;
      width: 10px;
      height: 5px;
      border: 2px solid #1eaa99;
      border-top: none;
      border-right: none;
      transform: rotate(-50deg);
    }
  }
}

:root[data-theme=light] .marked-view-wrapper {
  code {
    background: #F5F6F7;
    color: var(--color-text-primary);
  }
}
</style>
