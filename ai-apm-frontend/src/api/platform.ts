import http from '../utils/axios'
import { AxiosPromise } from 'axios'

export interface PlatformMetricQueryBody {
  fromTime?: string
  toTime?: string
  from?: number
  to?: number
  metrics?: string[]
  metricPrefixes?: string[]
  metricPrefix?: string | string[]
  metricSuffixes?: string[]
  metricSuffix?: string | string[]
  components?: string[]
  component?: string
  instances?: string[]
  instance?: string
  dims?: string[]
  dim?: string
  groupBy?: string[]
  value?: 'auto' | 'cnt' | 'sum' | 'avg' | 'max' | 'min' | 'gauge'
  stepSeconds?: number
  fill?: boolean
  tag?: string
  limit?: number
}

/** Generic metric_platform APIs — all self-monitor pages share these. */
export default {
  query: (data: PlatformMetricQueryBody): AxiosPromise => {
    return http.request({
      url: '/platform/metrics/query',
      method: 'post',
      data,
    })
  },
  summary: (data: PlatformMetricQueryBody): AxiosPromise => {
    return http.request({
      url: '/platform/metrics/summary',
      method: 'post',
      data,
    })
  },
  tagValues: (data: PlatformMetricQueryBody): AxiosPromise => {
    return http.request({
      url: '/platform/metrics/tagValues',
      method: 'post',
      data,
    })
  },
}
