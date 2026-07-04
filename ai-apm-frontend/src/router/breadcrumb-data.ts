interface BreadcrumbPath {
  // 当前path: 除自己以外的面包屑项path
  [key: string]: string[]
}

// 告警中心
const alarmCenter: BreadcrumbPath = {
  // 告警列表
  '/alarmCenter/alarm': ['/alarmCenter'],
  // 告警详情
  '/alarmCenter/alarmDetail': ['/alarmCenter', '/alarmCenter/alarm'],
  // 告警通知
  '/alarmCenter/notice': ['/alarmCenter'],
  // 故障列表
  '/alarmCenter/rootCause': ['/alarmCenter'],
  // 根因分析
  '/alarmCenter/rootCauseAnalysis': ['/alarmCenter', '/alarmCenter/rootCause'],
  // 问题详情
  '/alarmCenter/problemDetail': ['/alarmCenter', '/alarmCenter/rootCause'],
}

// 应用性能
const appMonitor: BreadcrumbPath = {
  // 全局拓扑
  '/appMonitor/globalTopology': ['/appMonitor'],
  // 业务系统
  // 服务
  '/appMonitor/service': ['/appMonitor'],
  // 数据库
  '/appMonitor/database': ['/appMonitor'],
  '/appMonitor/database/detail': ['/appMonitor', '/appMonitor/database'],
  // 消息队列
  '/appMonitor/msgQueue': ['/appMonitor'],
  '/appMonitor/msgQueue/detail': ['/appMonitor', '/appMonitor/msgQueue'],
  // 缓存
  '/appMonitor/cache': ['/appMonitor'],
  '/appMonitor/cache/detail': ['/appMonitor', '/appMonitor/cache'],
  // 外部服务
  '/appMonitor/external': ['/appMonitor'],
  '/appMonitor/external/detail': ['/appMonitor', '/appMonitor/external'],
  // 详细分析 or 接口分析
  '/appMonitor/serviceAnalysis': ['/appMonitor'],
  // 错误分析
  '/appMonitor/errors': ['/appMonitor'],
  // 服务流
  '/appMonitor/serviceFlow': ['/appMonitor'],
  // 错误详情
  '/appMonitor/errorDetail': ['/appMonitor', '/appMonitor/errors'],
  // 服务详情
  '/appMonitor/serviceDetail': ['/appMonitor', '/appMonitor/service'],
  // 服务实例详情
  '/appMonitor/serviceInstance': ['/appMonitor', '/appMonitor/service', '/appMonitor/serviceDetail'],
  // 响应时间分布
  '/appMonitor/response': ['/appMonitor', '/appMonitor/service'],
  // 业务系统调用分析
  // 服务调用分析
  '/appMonitor/serviceCall': ['/appMonitor', '/appMonitor/service'],
  // 请求调用出入口详情
  '/appMonitor/serviceCallDetail': ['/appMonitor', '/appMonitor/service', '/appMonitor/serviceCall'],
  // 请求详情 or 接口详情
  '/appMonitor/resourceDetail': ['/appMonitor', '/appMonitor/serviceAnalysis'],
  // 链路追踪
  '/appMonitor/trace': ['/appMonitor'],
  // 日志分析
  '/appMonitor/logs': ['/appMonitor'],
  // 调用链详情
  '/appMonitor/traceDetail': ['/appMonitor', '/appMonitor/trace'],
  // Profiling
  '/appMonitor/hotMethods': ['/appMonitor', '/appMonitor/serviceAnalysis'],
  // 线程池监控
  '/appMonitor/threadPool': ['/appMonitor', '/appMonitor/service'],
  // 对象池监控
  '/appMonitor/objectPool': ['/appMonitor', '/appMonitor/service'],
  // HTTP连接池监控
  '/appMonitor/httpConnPool': ['/appMonitor', '/appMonitor/service'],
  // 数据库连接池监控
  '/appMonitor/dbConnPool': ['/appMonitor', '/appMonitor/service'],
}

// AI 可观测
const aiMonitor: BreadcrumbPath = {
  '/aiMonitor/applications': ['/aiMonitor'],
  '/aiMonitor/topology': ['/aiMonitor'],
  '/aiMonitor/skillCalls': ['/aiMonitor'],
  '/aiMonitor/toolCalls': ['/aiMonitor'],
  '/aiMonitor/modelCalls': ['/aiMonitor'],
  '/aiMonitor/sessions': ['/aiMonitor'],
  '/aiMonitor/tokens': ['/aiMonitor'],
  '/aiMonitor/errors': ['/aiMonitor'],
}

// 基础设施
const infrastructure: BreadcrumbPath = {
  // 主机
  '/infrastructure/host': ['/infrastructure'],
  // 主机详情
  '/infrastructure/hostDetail': ['/infrastructure', '/infrastructure/host'],
  // Docker容器
  '/infrastructure/docker': ['/infrastructure'],
  // 容器详情
  '/infrastructure/dockerDetail': ['/infrastructure', '/infrastructure/docker'],
  // 进程
  '/infrastructure/process': ['/infrastructure'],
  // 进程详情
  '/infrastructure/processDetail': ['/infrastructure', '/infrastructure/process'],
  // Kubernetes集群
  '/infrastructure/cluster': ['/infrastructure'],
  // Kubernetes详情
  '/infrastructure/clusterDetail': ['/infrastructure', '/infrastructure/cluster'],
  // Namespace
  '/infrastructure/namespace': ['/infrastructure', '/infrastructure/cluster'],
  // Namespace详情
  '/infrastructure/namespaceDetail': ['/infrastructure', '/infrastructure/cluster', '/infrastructure/namespace'],
  // Workloads
  '/infrastructure/workload': ['/infrastructure', '/infrastructure/cluster'],
  // Workload详情
  '/infrastructure/workloadDetail': ['/infrastructure', '/infrastructure/cluster', '/infrastructure/workload'],
  // Pods
  '/infrastructure/pod': ['/infrastructure', '/infrastructure/cluster'],
  // Pod详情
  '/infrastructure/podDetail': ['/infrastructure', '/infrastructure/cluster', '/infrastructure/pod'],
  // Node分析
  '/infrastructure/node': ['/infrastructure', '/infrastructure/cluster'],
  // Node详情
  '/infrastructure/nodeDetail': ['/infrastructure', '/infrastructure/cluster', '/infrastructure/node'],
  // Kubernetes Services
  '/infrastructure/service': ['/infrastructure', '/infrastructure/cluster', '/infrastructure/namespace'],
  // Kubernetes Service详情
  '/infrastructure/serviceDetail': ['/infrastructure', '/infrastructure/cluster', '/infrastructure/namespace', '/infrastructure/service'],
}

// 网络性能
const npm: BreadcrumbPath = {
  // 网络分析
  '/npm/analysis': ['/npm'],
  // 网络拓扑
  '/npm/topology': ['/npm'],
  // DNS分析
  '/npm/dns': ['/npm'],
}

// 安装部署
const deployInstall: BreadcrumbPath = {
  '/deploy/access': ['/deploy'],
  '/deploy/status': ['/deploy'],
}

// 配置管理
const config: BreadcrumbPath = {
  // 部署状态
  '/config/status': ['/config'],
  // 更新包管理
  '/config/agentPackages': ['/config', '/config/status'],
  // 运行日志
  '/config/runLog': ['/config', '/config/status'],
  // 配置管理
  '/config/manage': ['/config'],
  // 实体监控
  '/config/entity': ['/config', '/config/manage'],
  // 服务监控
  '/config/service': ['/config', '/config/manage', '/config/entity'],
  // 业务系统监控
  // 进程监控
  '/config/process': ['/config', '/config/manage', '/config/entity'],
  // 请求监控
  // 拓扑配置
  '/config/relationest': ['/config', '/config/manage'],
  // 告警配置
  '/config/alarm': ['/config', '/config/manage'],
  // 检测规则
  '/config/rule': ['/config', '/config/manage', '/config/alarm'],
  '/configManage/alarm/rulePreset': ['/config', '/config/manage', '/config/alarm'],
  '/configManage/alarm/ruleSetting': ['/config', '/config/manage', '/config/alarm'],
  '/configManage/alarm/responseSetting': ['/config', '/config/manage', '/config/alarm'],
  // AI配置
  '/config/ai': ['/config', '/config/manage'],
  '/config/llm': ['/config', '/config/manage'],
  '/config/login': ['/config', '/config/manage'],

  // 角色管理
  '/sysManage/role': ['/config', '/config/manage'],
  // License管理
  '/sysManage/license': ['/config', '/config/manage'],
  // 通知管理
  '/sysManage/notice': ['/config', '/config/manage'],
  // 系统设置
  '/sysManage/setting': ['/config', '/config/manage'],
  // 基础设置
  '/sysManage/basic': ['/config', '/config/manage', '/sysManage/setting'],
  // 系统事件
  '/sysManage/systemEvent': ['/config', '/config/manage', '/sysManage/setting'],
  // 系统事件详情
  '/sysManage/eventDetail': ['/config', '/config/manage', '/sysManage/setting', '/sysManage/systemEvent'],
  // 检测规则
  '/sysManage/systemRule': ['/config', '/config/manage', '/sysManage/setting', '/sysManage/systemEvent'],
  // 新建规则
  '/sysManage/ruleSetting': ['/config', '/config/manage', '/sysManage/setting', '/sysManage/systemEvent', '/sysManage/systemRule'],
  // 操作审计
  '/sysManage/operationAudit': ['/config', '/config/manage'],
}

const breadcrumbPathData: BreadcrumbPath = {
  ...alarmCenter,
  ...appMonitor,
  ...aiMonitor,
  ...infrastructure,
  ...npm,
  ...deployInstall,
  ...config,
};

export default breadcrumbPathData;
