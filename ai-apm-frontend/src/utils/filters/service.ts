
import i18n from '@/i18n';

/**
 * 服务类型
 * @param {type}
 * @return {typeCn}
 */
export const ServiceTypeFilter = (type: string) => {
  switch (type) {
    case 'browser':
      return i18n.t('modules.utils.filters.s_ef367e82') as string;
    case 'ios':
      return 'IOS';
    case 'android':
      return 'Android';
    case 'web':
    case 'custom': // legacy; display as Web
      return 'Web';
    case 'db':
      return i18n.t('modules.utils.filters.s_68051bf4') as string;
    case 'cache':
      return i18n.t('modules.utils.filters.s_e80c310e') as string;
    case 'mq':
      return 'MQ';
    case 'remote':
      return i18n.t('modules.utils.filters.s_71f31c96') as string;
    case 'instance':  // 问题详情相关页面使用
      return i18n.t('modules.utils.filters.s_71673bab') as string;
    default:
      return type || '-'
  }
}


/**
 * 请求类型
 * @param {type}
 * @return {typeCn}
 */
export const RequestTypeFilter = (type: string) => {
  switch (type) {
    case 'service.http':
      return 'HTTP';
    case 'service.rpc':
      return 'RPC';
    case 'service.mq':
      return 'MQ';
    case 'service.db':
      return 'SQL';
    case 'service.redis':
      return 'Redis';
    case 'service.config':
      return i18n.t('modules.utils.filters.s_224e2ccd') as string;
    case 'service.remote':
      return i18n.t('modules.utils.filters.s_71f31c96') as string;
    case 'service.other':
      return i18n.t('modules.utils.filters.s_0d98c747') as string;
    case 'service.browser':
      return i18n.t('modules.utils.filters.s_19a4e4d6') as string;
    case 'service.android':
      return i18n.t('modules.utils.filters.s_f33b9b2e') as string;
    case 'service.ios':
      return i18n.t('modules.utils.filters.s_15999355') as string;
    default:
      return type || '-'
  }
}

/**
 * 服务分析类型
 * @param {type}
 * @return {typeCn}
 */
export const ServiceAnalysisFilter = (type: string) => {
  switch (type) {
    case 'db':
      return i18n.t('modules.utils.filters.s_8eaab894') as string;
    case 'cache':
      return i18n.t('modules.utils.filters.s_933ae52e') as string;
    default:
      return i18n.t('modules.utils.filters.s_181e1375') as string
  }
}

/**
 * span状态
 * @param {status}
 * @return {statusCn}
 */
export const SpanStatusFilter = (status: number|boolean) => {
  switch (String(status)) {
    case '0':
    case 'false':
      return i18n.t('modules.components.db-table.s_fd6e80f1') as string
    case '1':
    case 'true':
      return i18n.t('modules.utils.filters.s_7030ff64') as string
    case '2':
      return i18n.t('modules.utils.filters.s_1efeae37') as string
    default:
      return '-'
  }
}
