import http from '../utils/axios'
import { AxiosPromise, AxiosRequestConfig, CancelToken, CancelTokenSource } from 'axios'
import type { PortalResourceMetricQuery, PortalServiceMetricQuery } from '../utils/portal-service-query'
interface DBResult {
  status: number;
  message: string;
  data: any;
}

export default {
  /**
   * 获取服务列表
   */
  getServiceList: (data: any): AxiosPromise => {
    return http.request({
      url: '/service/list',
      method: 'post',
      data
    })
  },

  /**
   * 获取服务图表数据
   */
  getServiceGraph: (data: PortalServiceMetricQuery & Record<string, unknown>, cancelToken?: CancelToken) => {
    const options: AxiosRequestConfig = {
      url: '/service/graph_stats',
      method: 'post',
      data,
    };
    if (cancelToken) {
      options.cancelToken = cancelToken;
    }
    return http.request(options)
  },
  /**
   * 获取服务响应时间分布图表数据
   */
  getServiceLatencyGraph: (data: any) => {
    return http.request({
      url: '/service/distribution_stats',
      method: 'post',
      data
    })
  },

  /**
   * 获取服务端点列表数据
   */
  getEndpointList: (data: any) => {
    return http.request({
      url: '/service/endpoints',
      method: 'post',
      data
    })
  },
  /**
   * 获取资源的来源服务
   */
  getReqContributorService: (data: any) => {
    return http.request({
      url: '/service/reqContributorService',
      method: 'post',
      data
    })
  },

  /**
   * 获取服务详情
   */
  getServiceDetail: (data: any) => {
    return http.request({
      url: '/service/serviceInfo',
      method: 'post',
      data
    })
  },
  /**
   * 获取调用链参数
   */
  getSpanParams: (data?: any) => {
    return http.request({
      url: '/trace/query_params',
      method: 'post',
      data
    })
  },
  /**
   * 获取调用链列表
   */
   getSpanList: (data?: any) => {
    return http.request({
      url: '/trace/list',
      method: 'post',
      data
    })
  },
  /**
   * 获取事件调用链列表
   */
   getEventSpanList: (data?: any) => {
    return http.request({
      url: '/trace/search/list',
      method: 'post',
      data
    })
  },
  /**
   * 获取调用链请求统计图表
   */
   getSpanRequestGraph: (data?: any) => {
    return http.request({
      url: '/trace/cnt_graph_stats',
      method: 'post',
      data
    })
  },
  /**
   * 获取调用链错误统计图表
   */
   getSpanErrorGraph: (data?: any) => {
    return http.request({
      url: '/trace/error_cnt_graph_stats',
      method: 'post',
      data
    })
  },
  /**
   * 获取调用链响应时间图表
   */
   getSpanResponseTimeGraph: (data?: any) => {
    return http.request({
      url: '/trace/graph_stats',
      method: 'post',
      data
    })
  },
  /**
   * 获取调用链图表
   */
   getTraceSpans: (data?: any) => {
    return http.request({
      url: '/trace/spans',
      method: 'post',
      data
    })
  },
  getSpanFlow: (data: any): AxiosPromise<DBResult> => {
    return http.request({
      method: 'post',
      url: '/trace/serviceFlow',
      data
    })
  },
  /**
   * 获取服务请求贡献Top
   */
  getServiceReqTop: (data: any) => {
    return http.request({
      method: 'post',
      url: '/service/reqTop',
      data
    })
  },
  /**
   * 获取慢sql top sql
   */
  getSlowSqlTop (data: any) {
    return http.request({
      url: '/service/slowSqlTopList',
      method: 'post',
      data
    })
  },
  /**
   * 接口别名
   */
  updateRequestAlias (data: any) {
    return http.request({
      url: '/slowInterface/updateResourceAlias',
      method: 'post',
      data
    })
  },
  /**
   * 获取接口 上下游关系
   */
  slowApiRelation (data: PortalResourceMetricQuery & { componentType?: string }) {
    return http.request({
      url: '/slowInterface/getResourceRelations',
      method: 'post',
      data
    })
  },
  /**
   * 获取接口趋势图
   */
  traceTrend (data: any) {
    return http.request({
      url: '/trace/allCnt',
      method: 'post',
      data
    })
  },
  /**
   * 获取慢接口趋势图
   */
  traceSlowTrend (data: any) {
    return http.request({
      url: '/trace/slowCnt',
      method: 'post',
      data
    })
  },
  /**
   * 获取错误接口趋势图
   */
  traceErrorTrend (data: any) {
    return http.request({
      url: '/trace/errorCnt',
      method: 'post',
      data
    })
  },
  /**
   * 智能分析指标
   */
  mannualAi (data: { service: string, serviceId: string, fromTime: number, toTime: number, serviceInstance?: string }) {
    return http.request({
      url: '/root/syncAnalyse',
      method: 'post',
      data
    })
  },
}
