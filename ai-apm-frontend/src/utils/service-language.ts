/** Normalize OTel / agent language values to db-iconfont aliases. */
const LANGUAGE_ALIAS: Record<string, string> = {
  jvm: 'java',
  javascript: 'nodejs',
  js: 'nodejs',
  node: 'nodejs',
  typescript: 'nodejs',
  ts: 'nodejs',
  csharp: 'dotnet',
  'c#': 'dotnet',
  net: 'dotnet',
  golang: 'go',
  'c++': 'cpp',
  cplusplus: 'cpp',
  c: 'cpp',
}

const LANGUAGE_LABEL: Record<string, string> = {
  java: 'Java',
  python: 'Python',
  go: 'Go',
  nodejs: 'Node.js',
  dotnet: '.NET',
  php: 'PHP',
  ruby: 'Ruby',
  rust: 'Rust',
  cpp: 'C++',
}

/** Brand-ish colors for language icons in list / detail. */
const LANGUAGE_COLOR: Record<string, string> = {
  java: '#E76F00',
  python: '#3776AB',
  go: '#00ADD8',
  nodejs: '#339933',
  dotnet: '#512BD4',
  php: '#777BB4',
  ruby: '#CC342D',
  rust: '#DEA584',
  cpp: '#00599C',
}

const KNOWN_LANGUAGE_ICONS = new Set(Object.keys(LANGUAGE_LABEL))

export function normalizeLanguageKey (language?: string | null): string {
  const raw = String(language || '').trim().toLowerCase()
  if (!raw) {
    return ''
  }
  return LANGUAGE_ALIAS[raw] || raw
}

export function hasLanguageIcon (language?: string | null): boolean {
  const key = normalizeLanguageKey(language)
  return !!key && KNOWN_LANGUAGE_ICONS.has(key)
}

export function resolveLanguageIcon (language?: string | null): string {
  return normalizeLanguageKey(language) || 'default'
}

export function languageDisplayName (language?: string | null): string {
  const key = normalizeLanguageKey(language)
  if (!key) {
    return ''
  }
  return LANGUAGE_LABEL[key] || String(language).trim()
}

export function languageIconColor (language?: string | null): string {
  const key = normalizeLanguageKey(language)
  return LANGUAGE_COLOR[key] || '#909399'
}

/** Prefer language icon for known runtimes; fall back to service type icon. */
export function resolveServiceDisplayIcon (type?: string | null, language?: string | null): string {
  if (hasLanguageIcon(language)) {
    return resolveLanguageIcon(language)
  }
  return String(type || 'default').toLowerCase() || 'default'
}
