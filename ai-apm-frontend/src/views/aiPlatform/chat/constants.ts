export const MAX_UPLOAD_FILE_SIZE = 5 * 1024 * 1024
export const MAX_UPLOAD_FILE_SIZE_MB = 5

export const ALLOWED_FILE_EXTENSIONS = [
  '.pdf', '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx',
  '.md', '.txt', '.json', '.xml', '.yaml', '.yml', '.csv', '.log',
] as const

export const ALLOWED_FILE_ACCEPT = ALLOWED_FILE_EXTENSIONS.join(',')

export interface QuickExpertOption {
  expertId: string
  icon: string
}

export const QUICK_EXPERTS: QuickExpertOption[] = [
  { expertId: 'data', icon: 'el-icon-data-analysis' },
  { expertId: 'inspection', icon: 'el-icon-view' },
]

/** 聊天页默认路由的数字专家（AI 大脑） */
export const DEFAULT_EXPERT_ID = 'brain'

const QUICK_EXPERT_ICON_BY_ID = Object.fromEntries(
  QUICK_EXPERTS.map(item => [item.expertId, item.icon]),
) as Record<string, string>

export const DEFAULT_QUICK_EXPERT_ICON = 'el-icon-s-custom'

/** 不超过该数量时全部 inline 展示；超过则前 6 个 inline + 「数字专家」溢出菜单 */
export const MAX_INLINE_QUICK_EXPERTS = 7
export const INLINE_QUICK_EXPERTS_BEFORE_OVERFLOW = 6

export interface QuickExpertSource {
  expertId: string
  enabled?: boolean
}

export interface QuickExpertGroups {
  inline: QuickExpertOption[]
  overflow: QuickExpertOption[]
  hasOverflow: boolean
}

/** 构建聊天页可选专家：内置快捷专家优先，其余已启用非 brain 专家按 ID 排序追加。 */
export function buildQuickExperts (experts: QuickExpertSource[]): QuickExpertOption[] {
  const expertById = new Map(experts.map(item => [item.expertId, item]))
  const orderedIds: string[] = []

  for (const item of QUICK_EXPERTS) {
    const expert = expertById.get(item.expertId)
    if (expert && expert.enabled === false) {
      continue
    }
    orderedIds.push(item.expertId)
  }

  experts
    .filter(item => item.expertId !== DEFAULT_EXPERT_ID && item.enabled !== false)
    .map(item => item.expertId)
    .sort()
    .forEach(expertId => {
      if (!orderedIds.includes(expertId)) {
        orderedIds.push(expertId)
      }
    })

  return orderedIds.map(expertId => ({
    expertId,
    icon: QUICK_EXPERT_ICON_BY_ID[expertId] || DEFAULT_QUICK_EXPERT_ICON,
  }))
}

/** 将可选专家拆分为 inline 按钮与溢出菜单两组。 */
export function groupQuickExperts (experts: QuickExpertSource[]): QuickExpertGroups {
  const all = buildQuickExperts(experts)
  if (all.length <= MAX_INLINE_QUICK_EXPERTS) {
    return { inline: all, overflow: [], hasOverflow: false }
  }
  return {
    inline: all.slice(0, INLINE_QUICK_EXPERTS_BEFORE_OVERFLOW),
    overflow: all.slice(INLINE_QUICK_EXPERTS_BEFORE_OVERFLOW),
    hasOverflow: true,
  }
}

/** 内置专家展示名 i18n key（后端 name 为中文，需前端翻译） */
export const EXPERT_NAME_KEYS: Record<string, string> = {
  brain: 'modules.views.aiPlatform.chat.s_16ffc5ac',
  data: 'modules.views.aiPlatform.chat.s_6e8ecff0',
  inspection: 'modules.views.aiPlatform.chat.s_4dabf8a7',
}
