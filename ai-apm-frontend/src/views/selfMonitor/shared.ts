import dayjs from 'dayjs'
import PlatformApi, { PlatformMetricQueryBody } from '@/api/platform'
import { toAsyncWait } from '@/utils/common'

/** One chart = one metric. Series lines come from groupBy (default instance). */
export interface ChartPanelSpec {
  /** Chart title; defaults to shortLabel(metric) */
  title?: string
  /** Exactly one metric name (qualified). Prefer this over prefixes. */
  metric?: string
  metrics?: string[]
  metricPrefixes?: string[]
  metricSuffixes?: string[]
  /** Default ['instance']; use ['dim'] for write signal / Doris host */
  groupBy?: string[]
  /** Exact dim filter sent to API (e.g. write signal trace/metric/log). */
  dims?: string[]
  value?: PlatformMetricQueryBody['value']
  components?: string[]
  height?: number
  unit?: string
  hint?: string
  /** Multiply series values for display (e.g. 0.001 for µs → ms). Storage stays unchanged. */
  scale?: number
  /** Divide each bucket sum by stepSeconds → per-second rate (e.g. TPS, bytes/s). */
  asRate?: boolean
  /** When groupBy includes metric or dim, map tag value → legend label (key order = series order). */
  seriesNameMap?: Record<string, string>
}

export interface SummaryCardSpec {
  title: string
  metrics?: string[]
  metricPrefixes?: string[]
  metricSuffixes?: string[]
  value?: PlatformMetricQueryBody['value']
  components?: string[]
  metric?: string
  nameSuffix?: string
  digits?: number
  /** Display suffix appended to the formatted value, e.g. `%`. */
  unit?: string
  tone?: 'default' | 'ok' | 'warn' | 'danger'
  /** Display total / windowSeconds (e.g. inbound TPS). */
  asRate?: boolean
  rateUnit?: string
}

export interface SectionSpec {
  title: string
  desc?: string
  panels: ChartPanelSpec[]
}

export function formatEpochSec(epochSec: number): string {
  return dayjs(epochSec * 1000).format('YYYY-MM-DD HH:mm')
}

export function shortMetricLabel(metric: string): string {
  if (!metric) {
    return '-'
  }
  // strip component prefix ingest.|web.
  return metric.replace(/^(ingest|web)\./, '')
}

export function seriesDisplayName(
  series: any,
  groupBy: string[],
  seriesNameMap?: Record<string, string>,
): string {
  const tags = series?.tags || {}
  if (seriesNameMap) {
    const metricKey = tags.metric != null ? String(tags.metric) : ''
    if (metricKey && seriesNameMap[metricKey]) {
      return seriesNameMap[metricKey]
    }
    const dimKey = tags.dim != null ? String(tags.dim) : ''
    if (dimKey && seriesNameMap[dimKey]) {
      return seriesNameMap[dimKey]
    }
  }
  if (groupBy.length === 1) {
    const key = groupBy[0]
    const v = tags[key]
    if (v != null && String(v).trim() !== '') {
      return String(v)
    }
  }
  if (groupBy.length > 1) {
    return groupBy
      .map(g => tags[g])
      .filter((v: any) => v != null && String(v).trim() !== '')
      .join(' · ') || series?.name || '-'
  }
  return series?.name || tags.metric || '-'
}

export function seriesToChartSource(
  series: any[],
  groupBy: string[] = ['instance'],
  scale = 1,
  stepSeconds = 0,
  asRate = false,
  seriesNameMap?: Record<string, string>,
): Array<{ name: string; data: Array<{ key: string; value: number | null }> }> {
  if (!Array.isArray(series)) {
    return []
  }
  const factor = scale && Number.isFinite(scale) && scale !== 0 ? scale : 1
  const rateDiv = asRate && stepSeconds > 0 ? stepSeconds : 1
  const mapped = series.map((s: any) => {
    const tags = s?.tags || {}
    // Prefer groupBy key for legend ordering (dim / metric / instance).
    const orderKey = groupBy.length === 1 && tags[groupBy[0]] != null
      ? String(tags[groupBy[0]])
      : (tags.metric != null ? String(tags.metric)
        : tags.dim != null ? String(tags.dim)
          : tags.instance != null ? String(tags.instance) : '')
    return {
      name: seriesDisplayName(s, groupBy, seriesNameMap),
      data: (s.points || []).map((p: any) => ({
        key: formatEpochSec(Number(p.t) || 0),
        value: p.v == null ? null : (Number(p.v) * factor) / rateDiv,
      })),
      _orderKey: orderKey,
    }
  }).filter(s => s.data.some((d: { key: string; value: number | null }) => d.value != null))
  if (seriesNameMap) {
    const order = Object.keys(seriesNameMap)
    const known = mapped.filter(s => order.includes(s._orderKey))
    known.sort((a, b) => order.indexOf(a._orderKey) - order.indexOf(b._orderKey))
    return known.map(({ name, data }) => ({ name, data }))
  }
  return mapped.map(({ name, data }) => ({ name, data }))
}

export function resolveGroupBy(spec: ChartPanelSpec): string[] {
  if (spec.groupBy && spec.groupBy.length) {
    return spec.groupBy
  }
  return ['instance']
}

export function resolveSingleMetric(spec: ChartPanelSpec): string | null {
  if (spec.metric) {
    return spec.metric
  }
  if (spec.metrics && spec.metrics.length === 1) {
    return spec.metrics[0]
  }
  return null
}

export async function queryPlatformSeries(
  time: { fromTime: string; toTime: string; interval?: number },
  spec: ChartPanelSpec,
  extra: Partial<PlatformMetricQueryBody> = {},
) {
  const single = resolveSingleMetric(spec)
  const groupBy = resolveGroupBy(spec)
  const body: PlatformMetricQueryBody = {
    fromTime: time.fromTime,
    toTime: time.toTime,
    metrics: single ? [single] : spec.metrics,
    metricPrefixes: single ? undefined : spec.metricPrefixes,
    metricSuffixes: single ? undefined : spec.metricSuffixes,
    groupBy,
    dims: spec.dims,
    value: spec.value || 'auto',
    components: spec.components,
    stepSeconds: time.interval || 60,
    fill: true,
    ...extra,
  }
  const { result, error } = await toAsyncWait(PlatformApi.query(body))
  if (error || !result || result.status !== 200) {
    return []
  }
  const step = Number(result.data?.stepSeconds) || body.stepSeconds || 60
  return seriesToChartSource(
    result.data?.series || [],
    groupBy,
    spec.scale ?? 1,
    step,
    !!spec.asRate,
    spec.seriesNameMap,
  )
}

/** Expand prefixes into concrete metric names (for one-chart-per-metric pages). */
export async function listPlatformMetrics(
  time: { fromTime: string; toTime: string },
  opts: {
    metricPrefixes?: string[]
    metricSuffixes?: string[]
    components?: string[]
    limit?: number
  },
): Promise<string[]> {
  const { result, error } = await toAsyncWait(PlatformApi.tagValues({
    fromTime: time.fromTime,
    toTime: time.toTime,
    tag: 'metric',
    metricPrefixes: opts.metricPrefixes,
    metricSuffixes: opts.metricSuffixes,
    components: opts.components,
    limit: opts.limit || 100,
  }))
  if (error || !result || result.status !== 200) {
    return []
  }
  return (result.data?.values || []).slice().sort()
}

export function panelsFromMetrics(
  metrics: string[],
  base: Omit<ChartPanelSpec, 'metric' | 'metrics' | 'title'> & { titleFn?: (m: string) => string },
): ChartPanelSpec[] {
  const { titleFn, ...rest } = base
  return metrics.map(metric => ({
    ...rest,
    metric,
    title: titleFn ? titleFn(metric) : shortMetricLabel(metric),
  }))
}

/**
 * Order GC metrics as: per collector count then time (so each collector fits one visual row when 3-col).
 * Input may be any `*.system.gc.*` names discovered in range.
 */
export function gcPanelsFromMetrics(
  metrics: string[],
  component: 'ingest' | 'web',
): ChartPanelSpec[] {
  const prefix = `${component}.system.gc.`
  const names = new Set<string>()
  for (const m of metrics) {
    if (m.startsWith(`${prefix}count.`)) {
      names.add(m.slice(`${prefix}count.`.length))
    } else if (m.startsWith(`${prefix}time.`)) {
      names.add(m.slice(`${prefix}time.`.length))
    }
  }
  const ordered = Array.from(names).sort()
  const panels: ChartPanelSpec[] = []
  for (const name of ordered) {
    panels.push({
      metric: `${prefix}count.${name}`,
      title: `GC count · ${name}`,
      value: 'cnt',
      components: [component],
      hint: '按实例',
    })
    panels.push({
      metric: `${prefix}time.${name}`,
      title: `GC time · ${name}`,
      value: 'cnt',
      unit: 'ms',
      components: [component],
      hint: '按实例',
    })
  }
  return panels
}

export async function queryPlatformSummary(
  time: { fromTime: string; toTime: string },
  spec: SummaryCardSpec,
  extra: Partial<PlatformMetricQueryBody> = {},
) {
  const body: PlatformMetricQueryBody = {
    fromTime: time.fromTime,
    toTime: time.toTime,
    metrics: spec.metrics,
    metricPrefixes: spec.metricPrefixes,
    metricSuffixes: spec.metricSuffixes,
    groupBy: ['metric'],
    value: spec.value || 'auto',
    components: spec.components,
    ...extra,
  }
  const { result, error } = await toAsyncWait(PlatformApi.summary(body))
  if (error || !result || result.status !== 200) {
    return null
  }
  const items: any[] = result.data?.items || []
  let filtered = items
  if (spec.metric) {
    filtered = items.filter(i => i.tags?.metric === spec.metric || i.name === spec.metric)
  } else if (spec.nameSuffix) {
    filtered = items.filter(i => {
      const name = String(i.tags?.metric || i.name || '')
      return name.endsWith(spec.nameSuffix as string)
    })
  }
  if (filtered.length === 1) {
    return filtered[0]?.value ?? null
  }
  let sum = 0
  let any = false
  for (const item of filtered) {
    if (item.value != null && !Number.isNaN(Number(item.value))) {
      sum += Number(item.value)
      any = true
    }
  }
  return any ? sum : null
}

export function formatCardValue(value: number | null | undefined, digits = 0): string {
  if (value == null || Number.isNaN(Number(value))) {
    return '—'
  }
  const n = Number(value)
  if (Math.abs(n) >= 10000) {
    return n.toLocaleString(undefined, { maximumFractionDigits: digits })
  }
  if (digits > 0) {
    return n.toFixed(digits)
  }
  if (Number.isInteger(n)) {
    return String(n)
  }
  return n.toFixed(2)
}

/** Window length in seconds from portal time strings. */
export function timeWindowSeconds(time: { fromTime: string; toTime: string }): number {
  const from = dayjs(time.fromTime)
  const to = dayjs(time.toTime)
  if (!from.isValid() || !to.isValid()) {
    return 0
  }
  const sec = to.diff(from, 'second')
  return sec > 0 ? sec : 0
}
