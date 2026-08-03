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
 * Mermaid：mermaid 代码块在 DOM 更新后异步渲染为 SVG；流式/打字机期间：
 * - 未闭合的 ```mermaid 围栏不渲染（避免半截图与反复失败）
 * - 已渲染的图按源码缓存，v-html 重绘后同步回填，避免闪烁
 * - 解析失败时保留源码占位，不阻断其余 Markdown
 *
 * 通过 /vendor/mermaid.min.js 脚本加载（不走 Vite 打包），避免 mermaid 与
 * AntV 强制拆包产生循环依赖导致整站白屏。文件由 yarn copy:mermaid /
 * postinstall 从 node_modules 拷到 public/vendor（不入库）。
 *
 * 思考过程折叠（v-show / display:none）时不渲染；可见后再 run，避免量出
 * 16x16 残缺 SVG。展开后多次延迟重试，覆盖 collapse 过渡动画。
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

/** 模块级缓存：同源 mermaid 文本复用 SVG，跨组件实例也避免重复布局闪烁 */
const mermaidSvgCache = new Map<string, string>()

/** 解析失败时给 diagram 容器加的标记 class，用于展示「图表解析失败」提示 */
const MERMAID_FAIL_CLASS = 'mermaid-render-failed'

/**
 * 对 LLM 常见的 mermaid 语法错误做最小修复。仅在 mermaid.run 抛错后兜底重试时调用，
 * 不影响首次正常渲染。当前覆盖：
 * - 圆柱体 / 引号矩形闭合 `)` 或 `"` 与 `]` 之间多了空格：`id[("label") ]` → `id[("label")]`
 */
function repairMermaidSource (source: string): string {
  if (!source) {
    return source
  }
  return source
    .replace(/\)\s+\]/g, ')]')
    .replace(/"\s+\]/g, '"]')
}

function escapeHtml (text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** 检测 Markdown 中是否存在未闭合的 ```mermaid 围栏（流式输出中） */
function hasOpenMermaidFence (markdown: string): boolean {
  if (!markdown || !/```\s*mermaid\b/i.test(markdown)) {
    return false
  }
  let open = false
  for (const line of markdown.split(/\r?\n/)) {
    const trimmed = line.trim()
    if (!open) {
      if (/^```\s*mermaid\b/i.test(trimmed)) {
        open = true
      }
      continue
    }
    if (/^```\s*$/.test(trimmed)) {
      open = false
    }
  }
  return open
}

const mermaidRenderer = new marked.Renderer()
const defaultCodeRenderer = mermaidRenderer.code.bind(mermaidRenderer)
mermaidRenderer.code = function (code: string, infostring: string, escaped: boolean) {
  const lang = (infostring || '').match(/^\S*/)?.[0] || ''
  if (lang === 'mermaid') {
    // 始终转义进 HTML；mermaid.run 读 textContent 时浏览器会还原 <br/> 等
    const raw = escaped
      ? code
        .replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>')
        .replace(/&quot;/g, '"')
        .replace(/&#39;/g, "'")
        .replace(/&amp;/g, '&')
      : code
    const source = encodeURIComponent(raw)
    return `<div class="mermaid-diagram" data-mermaid-source="${source}"><pre class="mermaid">${escapeHtml(raw)}</pre></div>\n`
  }
  return defaultCodeRenderer(code, infostring, escaped)
}

marked.setOptions({
  renderer: mermaidRenderer,
  highlight: (code: string, lang: string = 'bash') => {
    if (lang === 'mermaid') {
      // 不走 highlight.js，交由自定义 renderer 处理
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
        if ((window as any).mermaid) {
          resolve((window as any).mermaid as MermaidApi)
          return
        }
        existing.addEventListener('load', () => resolve((window as any).mermaid as MermaidApi))
        existing.addEventListener('error', () => reject(new Error('mermaid script load failed')))
        return
      }
      // 静态资源挂在站点根（/vendor/...），不是 Vue Router 的 /databuff 前缀
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

function decodeMermaidSource (diagram: HTMLElement): string {
  try {
    return decodeURIComponent(diagram.getAttribute('data-mermaid-source') || '')
  } catch (e) {
    return ''
  }
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
  private mermaidDebounceTimer: ReturnType<typeof setTimeout> | null = null
  private mermaidRetryTimers: ReturnType<typeof setTimeout>[] = []
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
    // v-html 更新后同步回填缓存 SVG，避免打字机重绘闪烁
    this.$nextTick(() => {
      this.restoreCachedMermaidSvgs()
      // 围栏未闭合时只展示源码，不调度 mermaid.run
      if (this.type !== 'code' && hasOpenMermaidFence(this.data || '')) {
        return
      }
      this.scheduleMermaidRender(280, true)
    })
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
    this.clearMermaidRenderTimers()
    this.mermaidRenderToken += 1
    if (this.mermaidVisibilityObserver) {
      this.mermaidVisibilityObserver.disconnect()
      this.mermaidVisibilityObserver = null
    }
    this.$el.removeEventListener('databuff-mermaid-retry', this.onMermaidRetry)
  }

  private onMermaidRetry = () => {
    // collapse 过渡期间高度可能为 0，分多次重试（不互相 debounce 掉）
    this.scheduleMermaidRender(0, false)
    this.scheduleMermaidRender(120, false)
    this.scheduleMermaidRender(360, false)
  }

  private clearMermaidRenderTimers () {
    if (this.mermaidDebounceTimer) {
      clearTimeout(this.mermaidDebounceTimer)
      this.mermaidDebounceTimer = null
    }
    this.mermaidRetryTimers.forEach(timer => clearTimeout(timer))
    this.mermaidRetryTimers = []
  }

  private scheduleMermaidRender (delay = 280, debounce = true) {
    if (debounce) {
      if (this.mermaidDebounceTimer) {
        clearTimeout(this.mermaidDebounceTimer)
      }
      this.mermaidDebounceTimer = setTimeout(() => {
        this.mermaidDebounceTimer = null
        this.renderMermaidDiagrams()
      }, delay)
      return
    }
    const timer = setTimeout(() => {
      this.mermaidRetryTimers = this.mermaidRetryTimers.filter(item => item !== timer)
      this.renderMermaidDiagrams()
    }, delay)
    this.mermaidRetryTimers.push(timer)
  }

  private ensureMermaidVisibilityObserver () {
    if (this.mermaidVisibilityObserver || typeof IntersectionObserver === 'undefined') {
      return
    }
    this.mermaidVisibilityObserver = new IntersectionObserver((entries) => {
      const visible = entries.some(entry => entry.isIntersecting && entry.intersectionRatio > 0)
      if (visible) {
        this.onMermaidRetry()
      }
    }, { threshold: 0.01 })
    const wrap = this.$refs.markedWrap
    if (wrap) {
      this.mermaidVisibilityObserver.observe(wrap)
    }
  }

  /** v-html 重绘后立刻用缓存 SVG 填回，消除闪烁 */
  private restoreCachedMermaidSvgs () {
    const wrap = this.$refs.markedWrap
    if (!wrap) {
      return
    }
    wrap.querySelectorAll<HTMLElement>('.mermaid-diagram[data-mermaid-source]').forEach((diagram) => {
      if (diagram.querySelector('svg')) {
        return
      }
      const source = decodeMermaidSource(diagram)
      if (!source.trim()) {
        return
      }
      const cached = mermaidSvgCache.get(source)
      if (!cached) {
        return
      }
      diagram.innerHTML = cached
    })
  }

  private restoreBrokenMermaidDiagrams (wrap: HTMLElement) {
    wrap.querySelectorAll<HTMLElement>('.mermaid-diagram[data-mermaid-source]').forEach((diagram) => {
      const svg = diagram.querySelector('svg')
      if (!svg || !isBrokenMermaidSvg(svg as unknown as SVGElement)) {
        return
      }
      const source = decodeMermaidSource(diagram)
      if (!source.trim()) {
        return
      }
      mermaidSvgCache.delete(source)
      diagram.innerHTML = `<pre class="mermaid">${escapeHtml(source)}</pre>`
    })
  }

  private collectPendingMermaidNodes (wrap: HTMLElement): HTMLElement[] {
    this.restoreBrokenMermaidDiagrams(wrap)
    this.restoreCachedMermaidSvgs()
    return Array.from(wrap.querySelectorAll<HTMLElement>('pre.mermaid:not([data-processed])'))
  }

  private cacheRenderedDiagrams (wrap: HTMLElement) {
    wrap.querySelectorAll<HTMLElement>('.mermaid-diagram[data-mermaid-source]').forEach((diagram) => {
      const svg = diagram.querySelector('svg')
      if (!svg || isBrokenMermaidSvg(svg as unknown as SVGElement)) {
        return
      }
      const source = decodeMermaidSource(diagram)
      if (!source.trim()) {
        return
      }
      // 渲染成功，清除之前的失败标记
      diagram.classList.remove(MERMAID_FAIL_CLASS)
      // 缓存整段 diagram 内容（含 svg），回填时保持结构
      mermaidSvgCache.set(source, diagram.innerHTML)
    })
  }

  private async renderMermaidDiagrams () {
    if (this.type !== 'code' && hasOpenMermaidFence(this.data || '')) {
      return
    }
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
      // 加载脚本期间 DOM 可能被打字机重绘，重新采集待渲染节点
      const freshNodes = this.collectPendingMermaidNodes(wrap)
      if (!freshNodes.length) {
        return
      }
      // 逐节点渲染：单块语法错误不会阻断同消息内其它图，失败块再尝试一次最小修复
      for (const node of freshNodes) {
        if (token !== this.mermaidRenderToken) {
          return
        }
        await this.renderOneMermaidNode(mermaid, node)
      }
      // 折叠态瞬时残缺的 SVG 恢复源码，等下次可见重试
      this.restoreBrokenMermaidDiagrams(wrap)
      this.cacheRenderedDiagrams(wrap)
    } catch (err) {
      console.warn('[marked-view] mermaid render failed', err)
    }
  }

  /**
   * 渲染单个 mermaid 节点。首次失败时套用 repairMermaidSource 做一次最小修复重试；
   * 仍失败则回退为源码占位并加 mermaid-render-failed 标记，配合 CSS 提示「图表解析失败」。
   *
   * 逐节点调用 mermaid.run 而非批量传入：mermaid v11 即便某节点解析失败也会给所有节点
   * 标上 data-processed 并塞入错误 SVG，且整体 promise reject；逐节点调用可隔离失败，
   * 保证同消息里其它合法图正常渲染。
   */
  private async renderOneMermaidNode (mermaid: MermaidApi, node: HTMLElement): Promise<void> {
    const diagram = node.closest<HTMLElement>('.mermaid-diagram[data-mermaid-source]')
    const original = diagram ? decodeMermaidSource(diagram) : (node.textContent || '')
    if (!original.trim()) {
      return
    }
    const tryRun = async (src: string) => {
      node.textContent = src
      // 移除上一次失败留下的 data-processed / 错误 SVG，让 mermaid 重新处理
      node.removeAttribute('data-processed')
      await mermaid.run({ nodes: [node] })
    }
    // 第一次：原样渲染
    try {
      await tryRun(original)
      return
    } catch (e1) {
      console.warn('[marked-view] mermaid node failed, attempting repair', e1)
    }
    // 第二次：最小修复后重试
    const fixed = repairMermaidSource(original)
    if (fixed.trim() && fixed !== original) {
      try {
        await tryRun(fixed)
        // 修复成功：把修复后的文本作为新的规范源码，后续缓存/回填基于它
        if (diagram) {
          diagram.setAttribute('data-mermaid-source', encodeURIComponent(fixed))
        }
        return
      } catch (e2) {
        console.warn('[marked-view] mermaid repair retry failed', e2)
      }
    }
    // 仍失败：回退为源码占位并打标记（不保留 mermaid 的错误 SVG）
    if (diagram) {
      diagram.classList.add(MERMAID_FAIL_CLASS)
      diagram.innerHTML = `<pre class="mermaid">${escapeHtml(original)}</pre>`
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
    /* 未渲染时给一点最小高度，减少源码↔SVG 切换的布局跳动 */
    min-height: 48px;
    position: relative;

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

    /* 修复重试仍失败时：展示「图表解析失败」提示，引导用户知道是模型画法错了 */
    &.mermaid-render-failed {
      border: 1px dashed var(--color-danger, #ef4444);
      background-color: rgba(239, 68, 68, 0.04);

      &::before {
        content: '图表解析失败：模型生成的 mermaid 语法有误，已尝试自动修复未果';
        display: block;
        margin-bottom: 8px;
        padding: 2px 0;
        color: var(--color-danger, #ef4444);
        font-size: 12px;
        text-align: left;
      }
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
