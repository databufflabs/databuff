import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { execSync } from 'child_process';
import { createRequire } from 'module';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const { parse: parseSfc } = require('@vue/compiler-sfc');
const ROOT = path.join(__dirname, '..');
const SRC = path.join(ROOT, 'src');
const OUT_DIR = path.join(SRC, 'i18n', 'generated');
const MANIFEST = path.join(OUT_DIR, 'manifest.json');

const SCAN_DIRS = ['views', 'components', 'utils'];
const SOURCE_EXT = /\.(vue|ts|js)$/;
const CHINESE_RE = /[\u4e00-\u9fff]/;
const WRITE_MESSAGES_ONLY = process.env.I18N_MESSAGES_ONLY === '1';
const QUOTE_RE = /(['"`])((?:\\.|(?!\1).)*[\u4e00-\u9fff](?:\\.|(?!\1).)*)\1/g;
const KEYABLE_PROPS = new Set([
  'label',
  'title',
  'describe',
  'description',
  'headerDescribe',
  'normalText',
  'errorText',
  'warningText',
  'placeholder',
  'emptyText',
  'content',
  'message',
  'name',
  'text',
  'nameCn',
  'desc',
  'group',
  'category',
  'confirmButtonText',
  'cancelButtonText',
  'inputPlaceholder',
  'activeText',
  'inactiveText',
]);
const SCRIPT_EXPR_PROPS = new Set([
  'labelText',
  'baseInfo',
  'fullInfo',
  'name',
  'title',
  '_client',
  '_send',
  '_receive',
  'formatter',
  'text',
]);
const TEMPLATE_SKIP_ATTRS = new Set([
  'class',
  'style',
  'code',
  'id',
  'key',
  'ref',
  'slot',
  'slot-scope',
  'v-if',
  'v-else-if',
  'v-show',
  'v-for',
  'v-model',
]);

const GLOSSARY = {
  保存: 'Save',
  取消: 'Cancel',
  确定: 'Confirm',
  确认: 'Confirm',
  提交: 'Submit',
  查询: 'Search',
  搜索: 'Search',
  重置: 'Reset',
  刷新: 'Refresh',
  导出: 'Export',
  导入: 'Import',
  新增: 'Add',
  新建: 'Create',
  编辑: 'Edit',
  删除: 'Delete',
  复制: 'Copy',
  查看: 'View',
  关闭: 'Close',
  开启: 'Enable',
  启用: 'Enable',
  禁用: 'Disable',
  返回: 'Back',
  操作: 'Actions',
  状态: 'Status',
  名称: 'Name',
  类型: 'Type',
  描述: 'Description',
  内容: 'Content',
  备注: 'Remarks',
  时间: 'Time',
  单位: 'Unit',
  版本: 'Version',
  成功: 'Success',
  失败: 'Failed',
  错误: 'Error',
  正常: 'Normal',
  异常: 'Abnormal',
  警告: 'Warning',
  在线: 'Online',
  下线: 'Offline',
  离线: 'Offline',
  待处理: 'Pending',
  处理中: 'Processing',
  已关闭: 'Closed',
  重要: 'Critical',
  次要: 'Minor',
  无数据: 'No data',
  暂无数据: 'No data',
  加载中: 'Loading...',
  清空筛选: 'Clear filters',
  请输入: 'Please enter',
  请选择: 'Please select',
  告警: 'Alert',
  服务: 'Service',
  服务实例: 'Service instance',
  接口: 'Endpoint',
  主机: 'Host',
  容器: 'Container',
  进程: 'Process',
  集群: 'Cluster',
  节点: 'Node',
  指标: 'Metric',
  日志: 'Log',
  网络: 'Network',
  数据库: 'Database',
  缓存: 'Cache',
  消息队列: 'Message queue',
  外部服务: 'External service',
  管理域: 'Management domain',
  用户名: 'Username',
  密码: 'Password',
  角色: 'Role',
  权限: 'Permission',
  组织: 'Organization',
  通知: 'Notification',
  邮箱: 'Email',
  手机号: 'Mobile',
  钉钉: 'DingTalk',
  微信: 'WeChat',
  企业微信: 'WeCom',
  规则: 'Rule',
  策略: 'Policy',
  静默: 'Silence',
  收敛: 'Convergence',
  响应: 'Response',
  检测: 'Detection',
  分析: 'Analysis',
  详情: 'Details',
  列表: 'List',
  拓扑: 'Topology',
  链路: 'Trace',
  配置: 'Configuration',
  部署: 'Deployment',
  安装: 'Install',
  全部: 'All',
  自定义: 'Custom',
  其他: 'Other',
  停用: 'Disable',
  来源: 'Source',
  内置: 'Built-in',
  引用: 'References',
  分类: 'Categories',
  工具: 'Tool',
  本地: 'Local',
  远程: 'Remote',
  协议: 'Protocol',
  注册表: 'Registry',
  实现方法: 'Implementation',
  平台: 'Platform',
  专家: 'Expert',
  已启用: 'Enabled',
  查看: 'View',
  显示配置: 'Display Configuration',
  显示数量: 'Display Count',
  显示排行前: 'Show top',
  的服务: ' services',
  染色阈值: 'Color Threshold',
  请输入显示数量: 'Please enter display count',
  请输入染色阈值: 'Please enter color threshold',
  告警状态: 'Alerts',
  异常状态: 'Exceptions',
  告警数: 'Alert Count',
  异常数: 'Exception Count',
  保存失败: 'Save failed',
  服务名称: 'Service Name',
  活跃: 'Active',
  不活跃: 'Inactive',
  快捷筛选: 'Quick filters',
  等级: 'Severity',
  处理: 'Handle',
  根因: 'Root cause',
  发现: 'Found',
  条数据: 'records',
  告警总数: 'Total alerts',
  '已解决 (自动)': 'Resolved (auto)',
  '已解决(自动)': 'Resolved (auto)',
  事件数量: 'Event count',
  持续时间: 'Duration',
  所属: 'In',
  管理域: 'Management domain',
  分区名: 'Partition name',
  业务系统: 'Business system',
  应用: 'Application',
  无: 'None',
  检测规则: 'Detection rules',
  告警类型: 'Alert type',
  告警描述: 'Alert description',
  告警等级: 'Alert severity',
  告警ID: 'Alert ID',
  首次触发: 'First triggered',
  最新触发: 'Last triggered',
  处理状态: 'Handling status',
  处理备注: 'Handling remarks',
  处理成功: 'Handled successfully',
  处理失败: 'Handling failed',
  请选择处理状态: 'Please select handling status',
  '您没有配置权限，请联系账号管理员申请': 'You do not have configuration permission. Please contact your account administrator.',
  健康状态: 'Health Status',
  平均响应时间: 'Avg Response Time',
  最大响应时间: 'Max Response Time',
  错误率: 'Error Rate',
  请求量: 'Request Volume',
  调用数: 'Call Count',
  调用速率: 'Call Rate',
  告警数量: 'Alert Count',
  数据来源: 'Data Source',
  收起图表: 'Collapse Chart',
  展开图表: 'Expand Chart',
  成功请求量: 'Successful Requests',
  失败请求量: 'Failed Requests',
  每分钟请求量: 'Requests Per Minute',
  每分钟调用数: 'Calls Per Minute',
  近1分钟调用速率: 'Call Rate (Last 1 Min)',
  '个 / 秒': '/ sec',
};

const ENGLISH_CLEANUP_GLOSSARY = {
  高级: 'Advanced',
  中文名: 'Chinese name',
  注释: 'Notes',
  基本判断: 'Basic condition',
  语言: 'Language',
  数量: 'Count',
  占比: 'Share',
  分布: 'Distribution',
  原因: 'Cause',
  统计: 'Statistics',
  次数: 'Count',
  添加: 'Add',
  趋势: 'Trend',
  上游: 'Upstream',
  报错: 'Error',
  错误次数: 'Error count',
  错误占比: 'Error share',
  错误原因: 'Error cause',
  错误请求: 'Error requests',
  错误请求趋势: 'Error request trend',
  错误原因分布: 'Error cause distribution',
  错误分析: 'Error analysis',
  添加到搜索: 'Add to search',
  结束: 'End',
  总量: 'Total',
  大模型: 'LLM',
  重新生成: 'Regenerate',
  思考过程: 'Thought process',
  此: 'this',
  版: 'edition',
  分钟前: 'minutes ago',
  已完成思考: 'Finished thinking',
  正在思考: 'Thinking',
  正在分析: 'Analyzing',
  折叠: 'Collapse',
  展开: 'Expand',
  待开放: 'Coming soon',
  该功能正在建设中: 'This feature is under construction',
  敬请期待: 'Stay tuned',
  恢复默认: 'Restore defaults',
  与: 'and',
  或: 'or',
  等于: 'equals',
  不等于: 'does not equal',
  包含: 'contains',
  不包含: 'does not contain',
  为空: 'is empty',
  不为空: 'is not empty',
  在列表中: 'is in list',
  不在列表中: 'is not in list',
  不区分大小写: 'case insensitive',
  区分大小写: 'case sensitive',
  条件: 'Condition',
  子条件: 'Sub-condition',
  字段值: 'Field value',
  无匹配项: 'No matches',
  无更多条件: 'No more conditions',
  收起: 'Collapse',
  参数: 'Parameter',
  存在: 'contains',
  非法: 'invalid',
  请重新输入: 'Please enter again',
  一: 'One',
  二: 'Two',
  三: 'Three',
  四: 'Four',
  五: 'Five',
  六: 'Six',
  七: 'Seven',
  八: 'Eight',
  九: 'Nine',
  十: 'Ten',
  二十: 'Twenty',
  二十一: 'Twenty-one',
  智能: 'Smart',
  告警恢复: 'Alert recovery',
  系统内置: 'System built-in',
  用户自定义: 'User-defined',
  用户导入: 'User imported',
  云服务商: 'Cloud provider',
  数据库监控: 'Database monitoring',
  网络监控: 'Network monitoring',
  集成: 'integration',
  社区插件: 'Community plugins',
  社区模板: 'Community templates',
  预置: 'Preset',
  第三方平台: 'Third-party platform',
  单指标检测: 'Single-metric detection',
  未知检测: 'Unknown detection',
  阈值检测: 'Threshold detection',
  同环比检测: 'Period-over-period detection',
  邮件: 'Email',
  短信: 'SMS',
  浏览器应用: 'Browser application',
  外部调用: 'External call',
  缓慢: 'Slow',
  格式不正确: 'Invalid format',
  格式不合法: 'Invalid format',
  事件: 'Event',
  平台: 'Platform',
  抱歉: 'Sorry',
  趋势图: 'Trend chart',
  支持: 'Supports',
  发送: 'Send',
  调试: 'Debug',
  重载: 'Reload',
  数据分析: 'Data analytics',
  健康巡检: 'Health inspection',
  手动新建: 'Create manually',
  暂无匹配的: 'No matching',
  且: 'and',
  路由: 'Routing',
  基础信息: 'Basic information',
  创建后不可修改: 'Cannot be changed after creation',
  确认删除: 'Confirm delete',
  服务地址: 'Service URL',
  传输协议: 'Transport protocol',
  请求头: 'Request headers',
  活跃: 'Active',
  处理备注: 'Handling remarks',
  持续时间: 'Duration',
  影响面分析: 'Impact analysis',
  其他信息: 'Other information',
  异常对象: 'Abnormal object',
  异常指标: 'Abnormal metric',
  接收者: 'Recipients',
  通知方式: 'Notification method',
  失败原因: 'Failure reason',
  检测类型: 'Detection type',
  接口名称: 'Endpoint name',
  来源服务: 'Source service',
  状态码: 'Status code',
  问题类型: 'Issue type',
  问题节点: 'Issue node',
  异常原因: 'Exception cause',
  推荐: 'Recommendation',
  请求: 'Request',
  地址: 'Address',
  慢: 'Slow',
  返回列表: 'Back to list',
  分钟: 'minutes',
  小时: 'hours',
  应用性能: 'Application performance',
  客户端: 'Client',
  调用阈值: 'Call threshold',
  单位毫秒: 'Unit: ms',
  使用率: 'Usage',
  单位为毫秒: 'Unit is ms',
  最小值不能低于: 'Minimum cannot be less than',
  毫秒: 'ms',
  可选: 'Optional',
  获取: 'Get',
  耗时: 'Duration',
  内存: 'Memory',
  服务端: 'Server',
  所属服务: 'Service',
  在选定时间范围内: 'within the selected time range',
  删除操作不可逆: 'Deletion cannot be undone',
  详情失败: 'Failed to load details',
  版本过低: 'Version is too old',
  链路追踪: 'Tracing',
  当前: 'Current',
  用户: 'User',
  文件: 'File',
  大脑: 'Brain',
  当前显示: 'Showing',
  将: 'will',
  开始时间: 'Start time',
  问题: 'Issue',
  平均响应时间: 'Average response time',
  端口: 'Port',
  更新成功: 'Updated successfully',
  主机名称: 'Host name',
  年: 'year',
  月: 'month',
  请慎重: 'Please proceed carefully',
  的进程: 'processes',
  是否继续: 'Continue?',
  是否: 'Whether',
  示例: 'Example',
  时为: 'is',
  分: 'minutes',
  已解决: 'Resolved',
  根因分析: 'Root cause analysis',
  指标名称: 'Metric name',
  指标描述: 'Metric description',
  自动: 'auto',
  请检查: 'Please check',
  名称或: 'name or',
  问数: 'Data query',
  巡检: 'Inspection',
  总数: 'Total',
  业务系统: 'Business system',
  检测规则: 'Detection rules',
  服务名称: 'Service name',
  告警描述: 'Alert description',
  超过: 'exceeds',
  请重试: 'Please try again',
  至: 'to',
  仅展示: 'Show only',
  到: 'to',
  锁: 'Lock',
  连接池: 'Connection pool',
  限制: 'Limit',
  读取行数: 'Read rows',
  请求数: 'Requests',
  调用: 'Calls',
  服务流: 'Service flow',
  个字符的服务名称: 'character service name',
  字母: 'letters',
  数字: 'numbers',
  更新失败: 'Update failed',
  显示名称: 'Display name',
  启停状态: 'Enabled status',
  内存使用量: 'Memory usage',
  性能剖析: 'Profiling',
  域名: 'Domain',
  操作系统: 'Operating system',
  至少包含大小写字母: 'must contain uppercase and lowercase letters',
  数字的: 'numeric',
  启动: 'Start',
  重复: 'Duplicate',
  请稍后: 'Please wait',
  要求: 'Requirement',
  英文: 'English',
  请联系管理员开启: 'Please contact an administrator to enable',
  满足: 'matches',
  的主机: 'hosts',
  的进程属于业务进程: 'processes are business processes',
  后展示: 'show after',
  消费阈值: 'Consumer threshold',
  调用链路追踪的性能阈值: 'trace performance threshold',
  环境变量: 'Environment variables',
  修改: 'Modify',
  宿主机: 'Host machine',
  关闭自动注入功能: 'Disable auto-injection',
  版本特性: 'Version features',
  下载: 'Download',
  默认关闭: 'Disabled by default',
  设为: 'Set as',
  适用于: 'Applies to',
  验证: 'Validate',
  无需修改业务代码: 'No business code changes required',
  设为管理: 'Set as managed',
  属性: 'Attributes',
  提取: 'Extract',
  哈希: 'Hash',
  过滤: 'Filter',
  转换: 'Transform',
  映射: 'Mapping',
  统计类: 'Statistical',
  复合类: 'Composite',
  聚合类: 'Aggregation',
  趋势预测: 'Trend prediction',
  容量预测: 'Capacity prediction',
  寿命预测: 'Lifetime prediction',
  原始采集: 'Raw collection',
  衍生计算: 'Derived calculation',
  预测: 'Predicted',
  合成: 'Synthetic',
  范围内好: 'Good within range',
  接近目标好: 'Good near target',
  稳定好: 'Stable is good',
  求和: 'Sum',
  对话追踪: 'Conversation tracing',
  系统: 'System',
  个人: 'Personal',
  机器人: 'Bot',
  方法: 'Method',
  根本原因: 'Root cause',
  定位准确性反馈: 'Localization accuracy feedback',
  处置建议: 'Recommended action',
  影响: 'Impact',
  定位反馈: 'Localization feedback',
  准确性: 'Accuracy',
  准确: 'Accurate',
  定位准确: 'Accurate localization',
  业务子系统: 'Business subsystem',
  业务线: 'Business line',
  对象池: 'Object pool',
  平铺展示: 'Tile view',
  属性信息: 'Attribute information',
  消费延迟: 'Consumer latency',
  设置: 'Settings',
  疑似原因: 'Suspected cause',
  对象池监控: 'Object pool monitoring',
  平均消费延迟: 'Average consumer latency',
  接收端: 'Receiver',
  线程名: 'Thread name',
  线程: 'Thread',
  资源占用: 'Resource usage',
  资源池: 'Resource pool',
  技术: 'Technology',
  抖动: 'Jitter',
  接收流量: 'Inbound traffic',
  吞吐量: 'Throughput',
  线程池: 'Thread pool',
  线程信息: 'Thread information',
  任务信息: 'Task information',
  系统负载: 'System load',
  磁盘读写吞吐量总趋势: 'Disk read/write throughput trend',
  磁盘读写延迟: 'Disk read/write latency',
  读取延迟: 'Read latency',
  消息存储端: 'Message storage endpoint',
  组件: 'Component',
  消息延迟: 'Message latency',
  主题: 'Topic',
  短链接: 'Short link',
  方法名: 'Method name',
  索引: 'Index',
  跨度组件: 'Span component',
  消息系统: 'Messaging system',
  基础设施: 'Infrastructure',
  完成系统授权: 'Complete system authorization',
  超级管理员: 'Super administrator',
  工作台: 'Workbench',
  技术组件: 'Technology component',
  插件: 'Plugin',
  停止: 'Stop',
  固定时长窗口: 'Fixed duration window',
  预览: 'Preview',
  相同: 'Same',
  完成: 'Complete',
  字段名: 'Field name',
  永久: 'Permanent',
  聚合: 'Aggregate',
  同比于: 'Compared with',
  同比增涨: 'Year-over-year increase',
  始终: 'Always',
  总和: 'Sum',
  周期性计划: 'Recurring schedule',
  任意实体对象: 'Any entity object',
  计划概览: 'Schedule overview',
  时区: 'Time zone',
  天: 'days',
  周: 'week',
  周日: 'Sunday',
  不停止: 'Do not stop',
  停止日期: 'Stop date',
  监控: 'Monitor',
  不监控: 'Do not monitor',
  动态采样: 'Dynamic sampling',
  触发: 'Trigger',
  恢复: 'Recover',
  不做标准化: 'Do not normalize',
  熔断控制: 'Circuit breaker control',
  熔断机制: 'Circuit breaker',
  解除: 'Remove',
  账户所在组: 'Account group',
  默认: 'Default',
  模型管理: 'Model management',
  默认模型: 'Default model',
  百: 'Bai',
  智: 'Zhi',
  千: 'Qian',
  登录设置: 'Login settings',
  批量停止: 'Batch stop',
  立即升级: 'Upgrade now',
  系统环境: 'System environment',
  本周: 'This week',
  仅供参考: 'For reference only',
  通过: 'via',
  从: 'from',
  访问: 'Access',
  资源概览: 'Resource overview',
  使用量: 'Usage',
  镜像: 'Image',
  接收: 'Receive',
  常规: 'General',
  系统架构: 'System architecture',
  系统内核: 'System kernel',
  模块名: 'Module name',
  网卡: 'Network interface',
  磁盘: 'Disk',
  挂载点: 'Mount point',
  总趋势: 'Overall trend',
  每核趋势: 'Per-core trend',
  磁盘读写吞吐量: 'Disk read/write throughput',
  基础架构: 'Infrastructure',
  短镜像名: 'Short image name',
  标签信息: 'Tag information',
  性能: 'Performance',
  流量: 'Traffic',
  延迟: 'Latency',
  责任人: 'Owner',
  解除绑定: 'Unbind',
  系统同步: 'System sync',
  实体管理: 'Entity management',
  提示: 'Notice',
  优先级: 'Priority',
  线性: 'Linear',
  登录授权码: 'Login authorization code',
  收件人: 'Recipient',
  编码格式: 'Encoding',
  标识: 'Identifier',
  今天: 'Today',
  昨天: 'Yesterday',
  授权: 'Authorization',
  系统设置: 'System settings',
  撤销管理: 'Revoke management',
  管理者: 'Manager',
  成员管理: 'Member management',
  成员: 'Members',
  立即生效: 'Effective immediately',
  间隔: 'Interval',
  已复制: 'Copied',
  健康度配置: 'Health score configuration',
};

const CHINESE_CHAR_FALLBACK = {
  是: 'Yes',
  否: 'No',
  的: 'of',
  个: '',
  条: '',
  中: 'in',
  上: 'up',
  下: 'down',
  前: 'before',
  后: 'after',
  左: 'left',
  右: 'right',
  新: 'new',
  旧: 'old',
  高: 'high',
  低: 'low',
  大: 'large',
  小: 'small',
  多: 'many',
  少: 'few',
  有: 'has',
  无: 'none',
  未: 'not',
  已: 'already',
  可: 'can',
  必: 'must',
  需: 'need',
  选: 'select',
  填: 'fill',
  入: 'enter',
  出: 'out',
  开: 'open',
  关: 'close',
  加: 'add',
  减: 'reduce',
  连: 'connect',
  续: 'continue',
  重: 're',
  试: 'try',
  查: 'search',
  看: 'view',
  表: 'table',
  图: 'chart',
  页: 'page',
  行: 'row',
  列: 'column',
  值: 'value',
  率: 'rate',
  数: 'number',
};

const manifest = new Map();
const zhByModule = new Map();
const enByModule = new Map();
let changedFiles = 0;

function walk(dir, acc = []) {
  if (!fs.existsSync(dir)) return acc;
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, ent.name);
    if (ent.isDirectory()) {
      if (ent.name === 'node_modules' || ent.name === 'dist' || ent.name === 'assets' || ent.name === 'i18n') continue;
      walk(p, acc);
    } else if (SOURCE_EXT.test(ent.name) && !ent.name.endsWith('.d.ts')) {
      acc.push(p);
    }
  }
  return acc;
}

function shouldScan(file) {
  const rel = path.relative(SRC, file).replace(/\\/g, '/');
  return SCAN_DIRS.some((dir) => rel.startsWith(`${dir}/`));
}

function moduleFromRel(rel) {
  const parts = rel.split('/');
  if (parts[0] === 'views' || parts[0] === 'components') {
    return parts.slice(0, Math.min(3, parts.length - 1)).join('.');
  }
  return parts.slice(0, 2).join('.');
}

function slug(text) {
  return `s_${crypto.createHash('md5').update(text).digest('hex').slice(0, 8)}`;
}

function cleanText(text) {
  return String(text)
    .replace(/\\n/g, '\n')
    .replace(/\s+/g, ' ')
    .trim();
}

function unescapeSourceText(text) {
  return String(text)
    .replace(/\\'/g, "'")
    .replace(/\\"/g, '"')
    .replace(/\\`/g, '`')
    .replace(/\\n/g, '\n');
}

function stripUiHtml(text) {
  return String(text)
    .replace(/<\/?(?:br|p|span|div|strong|em|b|i)\b[^>]*>/gi, ' ')
    .replace(/<\/?(?:br|p)\s*\/?>/gi, ' ');
}

function hasCodeLikeChars(text) {
  const source = stripUiHtml(text).replace(/\{value\d+\}/g, '');
  return /[<>={}]/.test(source);
}

function isUiText(text) {
  const source = cleanText(text);
  if (!source || !CHINESE_RE.test(source)) return false;
  if (source.includes('$text(') || source.includes('$t(') || source.includes('this.$t(') || source.includes('i18n.t(')) return false;
  if (/https?:\/\//.test(source)) return false;
  if (hasCodeLikeChars(source)) return false;
  if (/^(\/\/|\*|import\s|console\.)/.test(source)) return false;
  return true;
}

function keyFor(text, rel) {
  const source = cleanText(text);
  if (!manifest.has(source)) {
    const mod = moduleFromRel(rel);
    const key = `${mod}.${slug(source)}`;
    manifest.set(source, key);
    if (!zhByModule.has(mod)) zhByModule.set(mod, {});
    if (!enByModule.has(mod)) enByModule.set(mod, {});
    zhByModule.get(mod)[key.split('.').pop()] = source;
    enByModule.get(mod)[key.split('.').pop()] = translateToEn(source);
  }
  return manifest.get(source);
}

function keyParts(key) {
  const normalized = key.replace(/^modules\./, '');
  const parts = normalized.split('.');
  return {
    mod: parts.slice(0, -1).join('.'),
    shortKey: parts[parts.length - 1],
  };
}

function setModuleMessage(mod, shortKey, zh, en) {
  if (!zhByModule.has(mod)) zhByModule.set(mod, {});
  if (!enByModule.has(mod)) enByModule.set(mod, {});
  zhByModule.get(mod)[shortKey] = zh;
  enByModule.get(mod)[shortKey] = en;
}

function hasModuleMessage(mod, shortKey) {
  return Boolean(zhByModule.get(mod)?.[shortKey]);
}

const FIXED_MESSAGES = [
  ['views.aiPlatform.chat', '已完成思考，用时 {duration}', 'Finished thinking in {duration}'],
  ['views.aiPlatform.chat', '趋势图', 'Trend chart'],
  ['views.aiPlatform.chat', '步', 'steps'],
  ['views.aiPlatform.chat', '支持 {extensions}，单个文件不超过 {size}MB', 'Supports {extensions}. Each file must be no larger than {size} MB.'],
  ['views.aiPlatform.chat', '请输入你想要分析的问题，最多 20000 字符（可直接粘贴图片到输入框）', 'Enter the question you want to analyze, up to 20,000 characters. You can paste images directly here.'],
  ['views.aiPlatform.chat', '请选择模型', 'Select a model'],
  ['views.aiPlatform.chat', '中止', 'Stop'],
  ['views.aiPlatform.chat', '发送', 'Send'],
  ['views.aiPlatform.chat', '{greet}，{name}。有什么可以帮您？', '{greet}, {name}. How can I help?'],
  ['views.aiPlatform.chat', '单个文件不能超过 {size}MB：{name}', 'File must be no larger than {size} MB: {name}'],
  ['views.aiPlatform.chat', '仅支持上传图片文件：{name}', 'Only image files are supported: {name}'],
  ['views.aiPlatform.chat', '文件格式不支持：{name}', 'Unsupported file format: {name}'],
  ['views.aiPlatform.chat', '[已上传 {count} 个附件]', '[Uploaded {count} attachments]'],
  ['views.aiPlatform.chat', '查看参数', 'View parameters'],
  ['views.aiPlatform.chat', '正在深度思考…', 'Thinking deeply...'],
  ['views.aiPlatform.chat', '调用成功', 'Invocation succeeded'],
  ['views.aiPlatform.chat', '调用失败', 'Invocation failed'],
  ['views.aiPlatform.chat', '正在调用', 'Invoking'],
  ['views.aiPlatform.chat', '历史会话', 'History'],
  ['views.aiPlatform.chat', '新建会话', 'New session'],
  ['views.aiPlatform.chat', '已累计创建', 'Created'],
  ['views.aiPlatform.chat', '个会话', 'sessions'],
  ['views.aiPlatform.chat', '，当前', ', currently'],
  ['views.aiPlatform.chat', '个子任务', 'subtasks'],
  ['views.aiPlatform.chat', '附件', 'Attachment'],
  ['views.aiPlatform.chat', '生成文件', 'Generated files'],
  ['views.aiPlatform.chat', '尚未配置大模型，', 'LLM is not configured yet. '],
  ['views.aiPlatform.chat', '前往模型配置', 'Go to model configuration'],
  ['views.aiPlatform.chat', '上传文件', 'Upload file'],
  ['views.aiPlatform.chat', '上传图片', 'Upload image'],
  ['views.aiPlatform.chat', '选一个试试', 'Try one of these'],
  ['views.aiPlatform.chat', '换一批', 'More suggestions'],
  ['views.aiPlatform.chat', '新对话', 'New chat'],
  ['views.aiPlatform.chat', '暂无会话，发送消息后将自动创建', 'No sessions yet. Send a message to create one.'],
  ['views.aiPlatform.chat', '查询最近1小时的服务列表', 'Query service list for the last hour'],
  ['views.aiPlatform.chat', '查询第一个服务的上下游拓扑', 'Query upstream/downstream topology for the first service'],
  ['views.aiPlatform.chat', '查询每个服务最近1小时的请求量趋势图', 'Query request volume trends for each service over the last hour'],
  ['views.aiPlatform.chat', '查询每个服务最近1小时的异常量趋势图', 'Query anomaly trends for each service over the last hour'],
  ['views.aiPlatform.chat', '用户', 'User'],
  ['views.aiPlatform.chat', '调用结果', 'Invocation result'],
  ['views.aiPlatform.chat', '调用工具详情', 'Tool invocation details'],
  ['views.aiPlatform.chat', '图片', 'Image'],
  ['views.aiPlatform.chat', '文件', 'File'],
  ['views.aiPlatform.chat', '下载失败', 'Download failed'],
  ['views.aiPlatform.chat', '中止失败', 'Failed to stop'],
  ['views.aiPlatform.chat', '发送失败', 'Failed to send'],
  ['views.aiPlatform.chat', '暂无可复制内容', 'Nothing to copy'],
  ['views.aiPlatform.chat', '已复制', 'Copied'],
  ['views.aiPlatform.chat', '夜深了', "It's late"],
  ['views.aiPlatform.chat', '早上好', 'Good morning'],
  ['views.aiPlatform.chat', '中午好', 'Good afternoon'],
  ['views.aiPlatform.chat', '下午好', 'Good afternoon'],
  ['views.aiPlatform.chat', '晚上好', 'Good evening'],
  ['views.aiPlatform.chat', '智能问数', 'Intelligent Data Query'],
  ['views.aiPlatform.chat', 'AI大脑', 'AI Brain'],
  ['views.aiPlatform.chat', '智能巡检', 'Smart Inspection'],
  ['views.aiPlatform.chat', 'AI大脑', 'AI Brain'],
  ['views.aiPlatform.chat', 'DataBuff 的 7 个 AI 能力', "DataBuff's 7 AI Capabilities"],
  ['views.aiPlatform.chat', '点一个能力，立刻体验', 'Pick a capability, try it instantly'],
  ['views.aiPlatform.chat', '看得见', 'See It'],
  ['views.aiPlatform.chat', '自然语言问系统，AI 替你查数据', 'Ask in plain language, AI queries the data for you'],
  ['views.aiPlatform.chat', '最近 1 小时哪个服务最慢？列出最慢的 3 个服务。', 'Which services were slowest in the last hour? List the top 3.'],
  ['views.aiPlatform.chat', '军团协同', 'Agent Legion'],
  ['views.aiPlatform.chat', '多 Agent 协同，一支能点名派活的军团', 'Multi-agent collaboration — a legion you can dispatch by name'],
  ['views.aiPlatform.chat', '最近1小时整个集群有没有异常？请联合智能问数和智能巡检一起做综合诊断：智能问数查各服务延迟和错误率并追慢 Trace，智能巡检做分级健康检查，最后汇总成一份可转发给团队的故障报告。', 'Any anomalies in the cluster in the last hour? Run a joint diagnosis with Intelligent Data Query and Smart Inspection: Data Query checks service latency, error rates, and slow traces; Inspection runs tiered health checks; summarize into a forwardable incident report.'],
  ['views.aiPlatform.chat', '会查', 'Inspect'],
  ['views.aiPlatform.chat', '一句话出一份能转发的 HTML 巡检报告', 'One sentence yields a forwardable HTML inspection report'],
  ['views.aiPlatform.chat', '对 service-b 做一次巡检，输出完整的 HTML 巡检报告。', 'Inspect service-b and produce a complete HTML inspection report.'],
  ['views.aiPlatform.chat', '会诊断', 'Diagnose'],
  ['views.aiPlatform.chat', '根因分析，把锅分配得明明白白', 'Root cause analysis that assigns blame clearly'],
  ['views.aiPlatform.chat', 'service-a 最近 1 小时瓶颈在哪里？根因在应用、数据库还是下游？', 'Where is the bottleneck for service-a in the last hour? Is the root cause the app, the database, or a downstream service?'],
  ['views.aiPlatform.chat', '会修', 'Repair'],
  ['views.aiPlatform.chat', '运维专家 SSH 上机，自己查自己修', 'Ops expert SSHs in, investigates and fixes it itself'],
  ['views.aiPlatform.chat', '容器 ai-apm-demo 一直重启，帮我 SSH 上去排查并修好。', 'The ai-apm-demo container keeps restarting. SSH in, troubleshoot, and fix it.'],
  ['views.aiPlatform.chat', '这台机器磁盘使用率超过 90%，帮我看看是哪些日志或容器占的，能清的清掉。', 'Disk usage on this host exceeds 90%. Find out which logs or containers are using the space and clean up what you can.'],
  ['views.aiPlatform.chat', '会预测', 'Predict'],
  ['views.aiPlatform.chat', '容量分析，分清该扩容还是该优化', 'Capacity analysis — tell scale-up from optimization'],
  ['views.aiPlatform.chat', 'Redis 平均耗时 366ms 偏高，帮我判断是不是有容量瓶颈、要不要扩容，给容量规划建议。', 'Redis average latency of 366ms is high. Determine whether there is a capacity bottleneck, whether to scale up, and give capacity planning advice.'],
  ['views.aiPlatform.chat', '会答疑', 'Answer'],
  ['views.aiPlatform.chat', '产品答疑专家，读自家文档比搜索靠谱', 'Product Q&A expert — reads our own docs, more reliable than search'],
  ['views.aiPlatform.chat', 'OpenTelemetry SDK 怎么接入？告警阈值在哪配？给操作路径。', 'How do I integrate the OpenTelemetry SDK? Where do I configure alert thresholds? Give me the navigation paths.'],
  ['views.aiPlatform.chat', '对Databuff平台进行巡检，并出一份html巡检报告', 'Inspect the DataBuff platform and produce an HTML inspection report'],
  ['views.aiPlatform.chat', '立即体验', 'Try now'],
  ['views.aiPlatform.chat', '换一条', 'Another prompt'],
  ['views.aiPlatform.chat', '该专家未启用，无法体验', 'This expert is disabled and cannot be tried'],
  ['views.aiPlatform.chat', '体验', 'Try'],
  ['views.aiPlatform.chat', '最近 1 小时哪些服务有错误率升高的趋势？给出 Top 3。', 'Which services show an upward error-rate trend in the last hour? Give the top 3.'],
  ['views.aiPlatform.chat', '现在集群里有多少个服务在跑？按请求量从高到低排个序。', 'How many services are running in the cluster right now? Sort them by request volume, high to low.'],
  ['views.aiPlatform.chat', '最近 1 小时哪个服务实例的 CPU 使用率最高？列出 Top 3。', 'Which service instance had the highest CPU usage in the last hour? List the top 3.'],
  ['views.aiPlatform.chat', '帮我做一次全集群健康巡检：智能问数负责拉每个服务的 P99 延迟和错误率，智能巡检负责 JVM、线程、GC 分级检查，最后合成一份可转发给 SRE 群的报告。', 'Run a full-cluster health inspection: Intelligent Data Query pulls P99 latency and error rate for every service; Smart Inspection runs tiered JVM, thread, and GC checks; merge into a report forwardable to the SRE channel.'],
  ['views.aiPlatform.chat', '最近 30 分钟有没有慢调用？让智能问数追慢 Trace 根因，智能巡检同步检查相关服务实例健康度，汇总成一份联合诊断结论。', 'Any slow calls in the last 30 minutes? Have Intelligent Data Query chase the slow-trace root cause while Smart Inspection checks the related service instances health; summarize into a joint diagnostic conclusion.'],
  ['views.aiPlatform.chat', '对 service-a 做一次军团级会诊：智能问数查它的下游依赖和出口耗时，智能巡检查它自身实例的 JVM 和线程状态，最后给我一份可转发给开发团队的报告。', 'Run a legion-level consultation on service-a: Intelligent Data Query checks its downstream dependencies and outbound latency; Smart Inspection checks its own instances JVM and thread state; produce a report forwardable to the dev team.'],
  ['views.aiPlatform.chat', '对 service-a 做一次巡检，输出完整的 HTML 巡检报告。', 'Inspect service-a and produce a complete HTML inspection report.'],
  ['views.aiPlatform.chat', '对 MySQL 数据库 demo_apm 做一次巡检，输出完整的 HTML 巡检报告。', 'Inspect the MySQL database demo_apm and produce a complete HTML inspection report.'],
  ['views.aiPlatform.chat', '对 Redis 缓存做一次巡检，输出完整的 HTML 巡检报告。', 'Inspect the Redis cache and produce a complete HTML inspection report.'],
  ['views.aiPlatform.chat', 'service-b 最近 1 小时错误率升高，根因是什么？给出证据链。', 'service-b error rate rose in the last hour. What is the root cause? Give the evidence chain.'],
  ['views.aiPlatform.chat', '最近 1 小时 P99 延迟突然变高的服务是哪个？根因在哪一层？', 'Which service had a sudden P99 latency spike in the last hour? Which layer is the root cause in?'],
  ['views.aiPlatform.chat', 'service-a 调用 service-b 的 HTTP 接口耗时 100ms 偏高，帮我定位是网络、应用还是下游数据库的问题。', 'The HTTP call from service-a to service-b takes 100ms, which is high. Help me localize whether it is a network, application, or downstream database issue.'],
  ['views.aiPlatform.chat', '帮我检查这台机器的内存使用情况，找出占用最高的进程，如果是异常进程帮我处理掉。', 'Check this host memory usage, find the top-consuming process, and kill it if it is anomalous.'],
  ['views.aiPlatform.chat', 'DataBuff 的 ai-apm-web 容器起不来，帮我 SSH 上去 docker logs 看一下，是配置问题就帮我改好。', 'The DataBuff ai-apm-web container will not start. SSH in, check docker logs, and fix it if it is a configuration issue.'],
  ['views.aiPlatform.chat', 'MySQL demo_apm 最近 1 小时的 QPS 和连接数趋势怎么样？按当前增长速度，连接池还能撑多久？', 'What are the QPS and connection-count trends for MySQL demo_apm in the last hour? At the current growth rate, how long can the connection pool last?'],
  ['views.aiPlatform.chat', 'service-a 的请求量最近 7 天的增长趋势如何？按这个速度，下周需要扩容吗？', 'What is the 7-day request-volume growth trend for service-a? At this rate, do we need to scale up next week?'],
  ['views.aiPlatform.chat', 'Kafka 的消费延迟在持续增长，帮我判断是消费者处理能力不足还是生产端流量异常，给容量规划建议。', 'Kafka consumer latency keeps growing. Help me tell whether consumers are underpowered or producers have abnormal traffic, and give capacity planning advice.'],
  ['views.aiPlatform.chat', 'DataBuff 支持哪些语言的 SDK 接入？分别给一个最小可跑的接入示例。', 'Which language SDKs does DataBuff support for ingestion? Give a minimal runnable example for each.'],
  ['views.aiPlatform.chat', '我想给 service-a 的 P99 延迟配一个告警，超过 500ms 持续 3 分钟就触发，怎么配？给操作路径。', 'I want to configure an alert for service-a P99 latency that triggers when it exceeds 500ms for 3 minutes. How do I set it up? Give the navigation path.'],
  ['views.alarmCenter.alarmDetail', '该告警处理状态变更为【{value0}】', 'Alarm handling status changed to [{value0}]'],
  ['views.alarmCenter.problemDetail', '定位{value0}', 'Location {value0}'],
  ['views.alarmCenter.rootCauseAnalysis', '手动触发根因，分析时间段：{value0} ~ {value1}', 'Manually triggered root cause analysis. Analysis period: {value0} ~ {value1}'],
  ['views.appMonitor.cache', '存在 {value0} 个告警', '{value0} alerts exist'],
  ['views.appMonitor.relationMap', 'CPU 使用/请求/限制', 'CPU usage/request/limit'],
  ['views.appMonitor.relationMap', '内存 使用/请求/限制', 'Memory usage/request/limit'],
  ['views.configInstall.apm', '等为您的实际配置', 'with your actual configuration'],
  ['views.configInstall.dataAccess', '{value0} 条 / {value1}', '{value0} records / {value1}'],
  ['views.configInstall.dataAccess', '已应用管道不可删除', 'Applied pipelines cannot be deleted'],
  ['views.configInstall.plugin', '{value0} 集成', '{value0} integration'],
  ['views.configInstall.plugin', '安装{value0}集成可以增加 DataBuff 检测的主机数量。', 'Installing the {value0} integration can increase the number of hosts monitored by DataBuff.'],
  ['views.help.startGuide', '...还有{value0}个。', '...and {value0} more.'],
  ['views.configManage.alarm', '每隔', 'Every'],
  ['views.configManage.alarm', '每', 'Every'],
  ['views.configManage.alarm', '周期性重复', 'Periodic repeat'],
  ['views.configManage.entity', '满足如下规则的进程需要监控： 监控所有进程', 'Processes matching the following rules need monitoring: monitor all processes'],
  ['views.configManage.entity', '支持展示由插件或Datahub接入，并已关联到对应中间件的指标；按中间件类型选择需要展示的指标；拓扑包括系统拓扑、服务拓扑', 'Supports metrics ingested by plugins or Datahub and associated with the corresponding middleware. Select metrics to display by middleware type. Topologies include system topology and service topology.'],
  ['views.configManage.entity', '秒满足如下任意条件时，探针根据以下配置', 'seconds, when any of the following conditions are met, the probe uses the following configuration to'],
  ['views.configManage.entity', '秒满足如下任一条件时，', 'seconds, when any of the following conditions are met, '],
  ['views.configManage.entity', '秒满足如下全部条件时，', 'seconds, when all of the following conditions are met, '],
  ['views.configManage.login', '显示语言', 'Display language'],
  ['views.configManage.llm', '火', 'Fire'],
  ['views.configManage.llm', '请先添加模型', 'Please add a model first'],
  ['views.configStatus.agent', '更新进度：{value0}', 'Update progress: {value0}'],
  ['views.configStatus.agent', '重试', 'Retry'],
  ['views.deployInstall.otelCollector', '若环境中已部署 OpenTelemetry Collector，将应用遥测数据上报到 Collector 后， 修改 Collector 配置文件，使用', 'If OpenTelemetry Collector is already deployed, after reporting application telemetry data to Collector, modify the Collector configuration file and use'],
  ['views.help.startGuide', '你当前拥有 {value0} 台主机，', 'You currently have {value0} hosts,'],
  ['views.hide.advancedConfig', '当前按{value0}方式编辑', 'Currently editing as {value0}'],
  ['views.infrastructure.dockerDetail', '发现 {value0} 条数据（仅显示业务进程）', 'Found {value0} records (business processes only)'],
  ['views.metrics.analysis', '分析单元 {value0}', 'Analysis unit {value0}'],
  ['views.npm.topology', '最小值 {value0}', 'Minimum {value0}'],
  ['views.npm.topology', '最大值 {value0}', 'Maximum {value0}'],
  ['views.observe.scene', '详情', 'Details'],
  ['views.observe.scene', '未配置业务节点事件', 'No business node events configured'],
  ['views.sysManage.group', '加载中', 'Loading'],
  ['views.sysManage.group', '编辑规则', 'Edit rule'],
  ['views.sysManage.group', '新增规则', 'Add rule'],
  ['views.sysManage.group', '编辑管理域', 'Edit management domain'],
  ['views.sysManage.group', '新增管理域', 'Add management domain'],
  ['views.sysManage.org', '未分配用户', 'Unassigned users'],
  ['views.sysManage.org', '已分配用户', 'Assigned users'],
  ['views.sysManage.org', '组织权限控制功能启用后可通过组织控制平台用户的用户管理范围；若无需控制用户的用户管理范围，则关闭该功能，关闭后不影响组织与用户的关联关系', 'After organization permission control is enabled, organizations can control each platform user management scope. If this control is not needed, disable the feature. Disabling it does not affect organization and user relationships.'],
  ['views.sysManage.role', '编辑角色', 'Edit role'],
  ['views.sysManage.role', '请选择角色权限', 'Select role permissions'],
  ['views.appMonitor.response', '响应时间分布（{value0}）', 'Response time distribution ({value0})'],
  ['views.appMonitor.response', '响应时间分布（{value0} 所属服务：{value1}）', 'Response time distribution ({value0}, service: {value1})'],
  ['views.appMonitor.resourceDetail', '接口详情（{value0}{value1}{value2}）', 'Endpoint details ({value0}{value1}{value2})'],
  ['views.appMonitor.resourceDetail', '{value0} 所属服务：', '{value0}, service: '],
  ['views.appMonitor.resourceDetail', ' 来源服务：{value0}', ' Source service: {value0}'],
  ['views.appMonitor.serviceCallDetail', '{value0}（所属服务：{value1}）', '{value0} (service: {value1})'],
  ['views.configManage.alarm.rule', '<p>确定要删除已选中的规则吗？</p><p>删除操作不可逆，请慎重。</p>', '<p>Are you sure you want to delete the selected rules?</p><p>This action cannot be undone.</p>'],
  ['views.configManage.alarm.rule', '<p>确定要删除已选中的策略吗？</p><p>删除操作不可逆，请慎重。</p>', '<p>Are you sure you want to delete the selected policies?</p><p>This action cannot be undone.</p>'],
  ['views.sysManage.role', '角色名称：{value0}<br>角色描述：{value1}', 'Role name: {value0}<br>Role description: {value1}'],
  ['views.sysManage.group', '<p>管理域删除后，原绑定了当前管理域的账号将无法查看当前管理域对应的实体数据，是否继续删除？</p>', '<p>After deleting this management domain, accounts bound to it will no longer be able to view its entity data. Continue?</p>'],
  ['views.sysManage.group', '<p>此管理域的定义类型将变更为用户自定义类型，是否继续？</p>', '<p>The definition type of this management domain will change to user-defined. Continue?</p>'],
  ['views.appMonitor.errors.chart-group', '{value0}<br />数量：{value1}<br />占比：{value2}%', '{value0}<br />Count: {value1}<br />Share: {value2}%'],
  ['views.appMonitor.response.chart-latency', '{value0} ~ {value1}<br /> 数量： {value2}', '{value0} ~ {value1}<br /> Count: {value2}'],
  ['views.dataReport.report.components', '{value0}<br />数量：{value1}<br />占比：{value2}%', '{value0}<br />Count: {value1}<br />Share: {value2}%'],
  ['views.alarmCenter.problemAnalysis', '{value0}<br />数量：{value1}', '{value0}<br />Count: {value1}'],
  ['views.alarmCenter.eventDetail', '{value0}{value1}告警线', '{value0}{value1} alert line'],
  ['components.charts.pie-chart', '总量', 'Total'],
  ['components.charts.pie-chart-new', '总量', 'Total'],
  ['views.configInstall.dataAccess', '数据源 ( {value0} )', 'Data sources ( {value0} )'],
  ['views.configInstall.dataAccess', '处理器 ( {value0} )', 'Processors ( {value0} )'],
  ['views.observe.scene', '当前场景关联的业务事件中：{value0}已删除，请重新选择', 'The following business events linked to this scenario were deleted: {value0}. Please select again.'],
  ['views.metrics.list', '请选择{value0}级分类', 'Please select a level-{value0} category'],
  ['views.authorization', '登录您的{value0}', 'Sign in to your {value0}'],
  ['views.aiPlatform.experts', '调试 {value0}', 'Debug {value0}'],
  ['views.configManage.alarm.rule', '{value0}静默{value1}', '{value0} silence {value1}'],
  ['views.infrastructure.hostDetail', '{value0}静默{value1}', '{value0} silence {value1}'],
  ['views.dataReport.report.setting', '近 {value0} 天', 'Last {value0} days'],
  ['views.dataReport.report.components', '第 {value0} 页', 'Page {value0}'],
  ['views.appMonitor.serviceFlow', '{value0} 请求', '{value0} requests'],
  ['views.appMonitor.serviceFlow', '{value0} 次调用/请求', '{value0} calls/request'],
  ['views.sysManage.group.entity', '{value0} where ( {value1}名称 等于 {value2} )', '{value0} where ( {value1} name equals {value2} )'],
  ['views.aiPlatform.tools', '工具管理', 'Tool Management'],
  ['views.aiPlatform.tools', '管理平台本地能力与远程 MCP，供 AI 专家调用', 'Manage local platform capabilities and remote MCP for AI experts to invoke'],
  ['views.aiPlatform.tools', '工具总数', 'Total Tools'],
  ['views.aiPlatform.tools', '本地工具', 'Local Tools'],
  ['views.aiPlatform.tools', 'MCP 工具', 'MCP Tools'],
  ['views.aiPlatform.tools', '新建 MCP', 'Add MCP'],
  ['views.aiPlatform.tools', '搜索 Tool ID、名称或 MCP 地址', 'Search by Tool ID, name, or MCP endpoint'],
  ['views.aiPlatform.tools', '搜索 Tool ID、名称或实现方法', 'Search by Tool ID, name, or implementation'],
  ['views.aiPlatform.tools', 'MCP 分类', 'MCP Categories'],
  ['views.aiPlatform.tools', '本地分类', 'Local Categories'],
  ['views.aiPlatform.tools', '实现方法', 'Implementation'],
  ['views.aiPlatform.tools', '连接地址', 'Endpoint'],
  ['views.aiPlatform.tools', '协议', 'Protocol'],
  ['views.aiPlatform.tools', '类型', 'Type'],
  ['views.aiPlatform.tools', 'Tool ID 是专家调用远程 MCP 工具的稳定标识，创建后不可修改。', 'Tool ID is the stable identifier used by experts to invoke remote MCP tools. It cannot be changed after creation.'],
  ['views.aiPlatform.tools', '例如：APM 工具、业务系统', 'e.g. APM Tools, Business Systems'],
  ['views.aiPlatform.tools', '说明这个工具的输入、输出和使用场景', "Describe this tool's inputs, outputs, and usage scenarios"],
  ['views.aiPlatform.tools', 'MCP 连接', 'MCP Connection'],
  ['views.aiPlatform.tools', '当前仅支持远程 MCP，保存时会以 MCP 类型写入工具配置。', 'Only remote MCP is supported. Saved tools are stored with type MCP.'],
  ['views.aiPlatform.tools', '服务地址', 'Service URL'],
  ['views.aiPlatform.tools', '传输协议', 'Transport'],
  ['views.aiPlatform.tools', '请求头', 'Request Headers'],
  ['views.aiPlatform.tools', '参数 Schema', 'Parameter Schema'],
  ['views.aiPlatform.tools', '请输入 Tool ID', 'Please enter Tool ID'],
  ['views.aiPlatform.tools', '请输入远程 MCP 服务地址', 'Please enter the remote MCP service URL'],
  ['views.aiPlatform.tools', '请选择 MCP 传输协议', 'Please select the MCP transport protocol'],
  ['views.aiPlatform.tools', '编辑 MCP 工具', 'Edit MCP Tool'],
  ['views.aiPlatform.tools', '新建 MCP 工具', 'Add MCP Tool'],
  ['views.aiPlatform.tools', '远程 MCP 注册表', 'Remote MCP Registry'],
  ['views.aiPlatform.tools', '本地工具注册表', 'Local Tool Registry'],
  ['views.aiPlatform.tools', '当前显示 {value0} / {value1} 个 MCP 工具，可查看专家引用并调整连接配置。', 'Showing {value0} / {value1} MCP tools. View expert references and adjust connection settings.'],
  ['views.aiPlatform.tools', '当前显示 {value0} / {value1} 个本地工具，平台内置 APM 查询能力供专家直接调用。', 'Showing {value0} / {value1} local tools. Built-in APM query capabilities are available for experts to invoke directly.'],
  ['views.aiPlatform.tools', '远程 MCP 工具', 'Remote MCP Tools'],
  ['views.aiPlatform.tools', '平台本地工具', 'Platform Local Tools'],
  ['views.aiPlatform.tools', '暂无匹配的 MCP 工具', 'No matching MCP tools'],
  ['views.aiPlatform.tools', '暂无匹配的本地工具', 'No matching local tools'],
  ['views.aiPlatform.tools', '本地', 'Local'],
  ['views.aiPlatform.tools', '{value0} {value1} 引用', '{value0} {value1} References'],
  ['views.aiPlatform.tools', '{value0} 必须是 JSON 对象', '{value0} must be a JSON object'],
  ['views.aiPlatform.tools', '本地 Bean', 'Local Bean'],
  ['views.aiPlatform.tools', '内置 MCP', 'Built-in MCP'],
  ['views.aiPlatform.tools', 'APM 内置工具', 'APM Built-in Tools'],
  ['views.aiPlatform.tools', '确认删除{value0}工具 {value1}？', 'Delete {value0} tool {value1}?'],
  ['views.aiPlatform.tools', 'https://example.com/mcp 或 https://example.com/sse', 'https://example.com/mcp or https://example.com/sse'],
  ['views.aiPlatform.tools', '例如：{"Authorization":"Bearer token"}', 'e.g. {"Authorization":"Bearer token"}'],
  ['views.aiPlatform.tools', 'JSON Schema，例如：{"type":"object","properties":{}}', 'JSON Schema, e.g. {"type":"object","properties":{}}'],
  ['views.aiPlatform.experts', '数字专家', 'Digital Experts'],
  ['views.aiPlatform.experts', '配置 AI 专家的能力边界、工具与技能组合', 'Configure AI expert capabilities, tools, and skill combinations'],
  ['views.aiPlatform.experts', '专家总数', 'Total Experts'],
  ['views.aiPlatform.experts', '大脑专家', 'Brain Experts'],
  ['views.aiPlatform.experts', '新建专家', 'Create Expert'],
  ['views.aiPlatform.experts', '搜索 Expert ID、名称或 Prompt', 'Search by Expert ID, name, or prompt'],
  ['views.aiPlatform.experts', '全部', 'All'],
  ['views.aiPlatform.experts', '大脑', 'Brain'],
  ['views.aiPlatform.experts', '专家分类', 'Expert Categories'],
  ['views.aiPlatform.experts', '专家编排', 'Expert Orchestration'],
  ['views.aiPlatform.experts', '当前显示 {value0} / {value1} 个专家，可调试、重载运行时或调整能力边界。', 'Showing {value0} / {value1} experts. You can debug, reload runtime, or adjust capability boundaries.'],
  ['views.aiPlatform.experts', '数字专家编排', 'Digital Expert Orchestration'],
  ['views.aiPlatform.experts', '能力', 'Capabilities'],
  ['views.aiPlatform.experts', '状态', 'Status'],
  ['views.aiPlatform.experts', '启用', 'Enable'],
  ['views.aiPlatform.experts', '停用', 'Disable'],
  ['views.aiPlatform.experts', '操作', 'Actions'],
  ['views.aiPlatform.experts', '调试', 'Debug'],
  ['views.aiPlatform.experts', '重载', 'Reload'],
  ['views.aiPlatform.experts', '暂无匹配的数字专家', 'No matching digital experts'],
  ['views.aiPlatform.experts', '专家身份', 'Expert Identity'],
  ['views.aiPlatform.experts', '定义专家在平台内的名称、类型和启用状态。', "Define the expert's name, type, and enabled status within the platform."],
  ['views.aiPlatform.experts', '名称', 'Name'],
  ['views.aiPlatform.experts', '分类', 'Category'],
  ['views.aiPlatform.experts', '例如：大脑、数据分析、健康巡检', 'e.g. Brain, Data Analytics, Health Inspection'],
  ['views.aiPlatform.experts', '说明专家职责、适用场景和边界', "Describe the expert's responsibilities, use cases, and boundaries"],
  ['views.aiPlatform.experts', '能力组合', 'Capability Composition'],
  ['views.aiPlatform.experts', '选择专家可用的工具与 Skill，控制它的行动范围。', 'Select tools and skills available to the expert to control its scope of action.'],
  ['views.aiPlatform.experts', '工具模式', 'Tool Mode'],
  ['views.aiPlatform.experts', '白名单', 'Allowlist'],
  ['views.aiPlatform.experts', '黑名单', 'Blocklist'],
  ['views.aiPlatform.experts', '输入专家的系统提示词', "Enter the expert's system prompt"],
  ['views.aiPlatform.experts', '运行参数', 'Runtime Parameters'],
  ['views.aiPlatform.experts', '限制迭代次数、超时时间和并发子任务，避免专家运行失控。', 'Limit iterations, timeout, and concurrent subtasks to prevent runaway expert execution.'],
  ['views.aiPlatform.experts', '暴露', 'Expose'],
  ['views.aiPlatform.experts', '隐藏', 'Hide'],
  ['views.aiPlatform.experts', '取消', 'Cancel'],
  ['views.aiPlatform.experts', '请输入 Expert ID', 'Please enter Expert ID'],
  ['views.aiPlatform.experts', '请输入名称', 'Please enter name'],
  ['views.aiPlatform.experts', '编辑数字专家', 'Edit Digital Expert'],
  ['views.aiPlatform.experts', '新建数字专家', 'Create Digital Expert'],
  ['views.aiPlatform.experts', '全部分类', 'All Categories'],
  ['views.aiPlatform.experts', '默认分类', 'Default Category'],
  ['views.aiPlatform.experts', '保存成功', 'Saved successfully'],
  ['views.aiPlatform.experts', '输入调试消息', 'Enter debug message'],
  ['views.aiPlatform.experts', '调试完成', 'Debug completed'],
  ['views.aiPlatform.experts', '调试结果', 'Debug result'],
  ['views.aiPlatform.experts', '问数', 'Data Query'],
  ['views.aiPlatform.experts', '巡检', 'Inspection'],
  ['views.aiPlatform.experts', '专科专家', 'Specialist Experts'],
  ['views.aiPlatform.experts', '已触发 runtime 重载', 'Runtime reload triggered'],
  ['views.aiPlatform.experts', '确认删除专家 {value0}？', 'Delete expert {value0}?'],
  ['views.aiPlatform.experts', '删除成功', 'Deleted successfully'],
  ['views.aiPlatform.capabilities', '能力配置', 'Capability Settings'],
  ['views.aiPlatform.capabilities', '管理落地页 7 个 AI 能力的名称、文案与体验 prompt', 'Manage the names, copy, and try-it prompts of the 7 AI capabilities on the landing page'],
  ['views.aiPlatform.capabilities', '能力总数', 'Total Capabilities'],
  ['views.aiPlatform.capabilities', '一句话定位', 'One-line tagline'],
  ['views.aiPlatform.capabilities', '对应专家', 'Routed Expert'],
  ['views.aiPlatform.capabilities', 'Prompt 数', 'Prompt Count'],
  ['views.aiPlatform.capabilities', '编辑能力', 'Edit Capability'],
  ['views.aiPlatform.capabilities', '能力名称', 'Capability Name'],
  ['views.aiPlatform.capabilities', '体验 Prompt', 'Try-it Prompt'],
  ['views.aiPlatform.capabilities', '启用状态', 'Enabled Status'],
  ['views.aiPlatform.capabilities', '恢复默认成功', 'Reset to default successfully'],
  ['views.aiPlatform.capabilities', '确认恢复该能力为默认配置？当前修改将丢失。', 'Reset this capability to default? Current changes will be lost.'],
  ['views.aiPlatform.capabilities', '第 {value0} 条', 'Prompt {value0}'],
  ['views.aiPlatform.capabilities', '序号', 'Index'],
  ['views.aiPlatform.experts', '类型', 'Type'],
  ['views.aiPlatform.skills', '技能管理', 'Skill Management'],
  ['views.aiPlatform.skills', '维护专家技能包与 Markdown 指令内容', 'Maintain expert skill packages and Markdown instruction content'],
  ['views.aiPlatform.skills', 'Skill 总数', 'Total Skills'],
  ['views.aiPlatform.skills', '内置 Skill', 'Built-in Skills'],
  ['views.aiPlatform.skills', '导入 Skill', 'Import Skill'],
  ['views.aiPlatform.skills', '手动新建', 'Create Manually'],
  ['views.aiPlatform.skills', '搜索 Skill ID、名称或 URI', 'Search by Skill ID, name, or URI'],
  ['views.aiPlatform.skills', '自定义', 'Custom'],
  ['views.aiPlatform.skills', 'Skill 分类', 'Skill Categories'],
  ['views.aiPlatform.skills', '技能包列表', 'Skill Package List'],
  ['views.aiPlatform.skills', '当前显示 {value0} / {value1} 个 Skill，支持 zip 导入与在线浏览包内文件。', 'Showing {value0} / {value1} skills. Supports zip import and online browsing of package files.'],
  ['views.aiPlatform.skills', '专家技能包', 'Expert Skill Packages'],
  ['views.aiPlatform.skills', '来源', 'Source'],
  ['views.aiPlatform.skills', '内置', 'Built-in'],
  ['views.aiPlatform.skills', '文件', 'Files'],
  ['views.aiPlatform.skills', '引用', 'References'],
  ['views.aiPlatform.skills', '校验', 'Validate'],
  ['views.aiPlatform.skills', '暂无匹配的 Skill', 'No matching skills'],
  ['views.aiPlatform.skills', 'Skill 包', 'Skill Package'],
  ['views.aiPlatform.skills', '将 .zip 拖到此处，或', 'Drag a .zip file here, or'],
  ['views.aiPlatform.skills', '点击上传', 'click to upload'],
  ['views.aiPlatform.skills', '包内需包含 SKILL.md，且 frontmatter 中需声明 name', 'Package must include SKILL.md with name declared in frontmatter'],
  ['views.aiPlatform.skills', '底层 Skill 名称', 'Underlying Skill Name'],
  ['views.aiPlatform.skills', '自动从 SKILL.md 解析', 'Auto-parsed from SKILL.md'],
  ['views.aiPlatform.skills', '展示名称', 'Display Name'],
  ['views.aiPlatform.skills', '平台展示名称，可自定义', 'Platform display name, customizable'],
  ['views.aiPlatform.skills', '例如：巡检、问数、路由', 'e.g. Inspection, Data Query, Routing'],
  ['views.aiPlatform.skills', '可选，默认读取 SKILL.md description', 'Optional, defaults to SKILL.md description'],
  ['views.aiPlatform.skills', '包内文件', 'Package Files'],
  ['views.aiPlatform.skills', '导入', 'Import'],
  ['views.aiPlatform.skills', '基础信息', 'Basic Info'],
  ['views.aiPlatform.skills', 'Skill ID 为底层实际名称，创建后不可修改。', 'Skill ID is the underlying identifier and cannot be changed after creation.'],
  ['views.aiPlatform.skills', '说明这个 Skill 负责的任务边界', "Describe this skill's scope and responsibilities"],
  ['views.aiPlatform.skills', '内容位置', 'Content Location'],
  ['views.aiPlatform.skills', '手动新建时需填写 Markdown 指令文件 URI。', 'When creating manually, provide the Markdown instruction file URI.'],
  ['views.aiPlatform.skills', '引用专家', 'Referenced Experts'],
  ['views.aiPlatform.skills', '暂无可浏览文件', 'No files to browse'],
  ['views.aiPlatform.skills', '选择左侧文件查看内容', 'Select a file on the left to view its content'],
  ['views.aiPlatform.skills', '请输入 Skill ID', 'Please enter Skill ID'],
  ['views.aiPlatform.skills', '请输入展示名称', 'Please enter display name'],
  ['views.aiPlatform.skills', '请输入 Content URI', 'Please enter Content URI'],
  ['views.aiPlatform.skills', '编辑 Skill', 'Edit Skill'],
  ['views.aiPlatform.skills', '手动新建 Skill', 'Create Skill Manually'],
  ['views.aiPlatform.skills', '请先上传 Skill zip 包', 'Please upload a Skill zip package first'],
  ['views.aiPlatform.skills', '未能从 SKILL.md 解析底层 Skill 名称', 'Failed to parse underlying Skill name from SKILL.md'],
  ['views.aiPlatform.skills', '导入成功', 'Import successful'],
  ['views.aiPlatform.skills', 'Skill 文件 - {value0}', 'Skill Files - {value0}'],
  ['views.aiPlatform.skills', '暂无专家引用', 'No expert references'],
  ['views.aiPlatform.skills', 'Skill {value0} 引用', 'Skill {value0} References'],
  ['views.aiPlatform.skills', '校验通过', 'Validation passed'],
  ['views.aiPlatform.skills', '大脑路由', 'Brain Routing'],
  ['views.aiPlatform.skills', '确认删除 Skill {value0}？', 'Delete Skill {value0}?'],
  ['views.aiPlatform.skills', '大脑', 'Brain'],
  ['views.aiPlatform.skills', '问数', 'Data Query'],
  ['views.aiPlatform.skills', '巡检', 'Inspection'],
  ['views.aiPlatform.skills', '问数口径', 'Data Query Guidelines'],
  ['views.aiPlatform.skills', '巡检流程', 'Inspection Workflow'],
  ['views.aiPlatform.experts', '数据分析', 'Data Analytics'],
  ['views.aiPlatform.experts', '健康巡检', 'Health Inspection'],
  ['views.help.startGuide', '已启用', 'Enabled'],
  ['views.metrics.list', '停用', 'Disable'],
  ['views.metrics.list', '查看', 'View'],
  ['views.cockpit.tab', '显示配置', 'Display Configuration'],
  ['views.cockpit.tab', '显示数量', 'Display Count'],
  ['views.cockpit.tab', '显示排行前', 'Show top'],
  ['views.cockpit.tab', '的服务', ' services'],
  ['views.cockpit.tab', '染色阈值', 'Color Threshold'],
  ['views.cockpit.tab', '请输入显示数量', 'Please enter display count'],
  ['views.cockpit.tab', '请输入染色阈值', 'Please enter color threshold'],
  ['views.cockpit.tab', '告警状态', 'Alerts'],
  ['views.cockpit.tab', '异常状态', 'Exceptions'],
  ['views.cockpit.tab', '异常数', 'Exception Count'],
  ['views.cockpit.tab', '保存失败', 'Save failed'],
  ['views.cockpit.tab', '展示窗口内告警黄/红色出现次数', 'Yellow/red alarm occurrences in the display window'],
  ['views.cockpit.tab', '展示窗口内异常黄/红色出现次数', 'Yellow/red exception occurrences in the display window'],
  ['views.alarmCenter.problemDetail', '告警数', 'Alert Count'],
  ['views.appMonitor.serviceFlow', '服务名称', 'Service Name'],
  ['views.alarmCenter.alarm', '活跃', 'Active'],
  ['views.alarmCenter.alarm', '不活跃', 'Inactive'],
  ['views.alarmCenter.alarm', '快捷筛选', 'Quick filters'],
  ['views.alarmCenter.alarm', '无', 'None'],
  ['views.alarmCenter.alarm', '处理状态', 'Handling status'],
  ['views.alarmCenter.alarm', '待处理', 'Pending'],
  ['views.alarmCenter.alarm', '处理中', 'Processing'],
  ['views.alarmCenter.alarm', '已关闭', 'Closed'],
  ['views.alarmCenter.alarm', '已解决 (自动)', 'Resolved (auto)'],
  ['views.alarmCenter.alarm', '告警等级', 'Alert severity'],
  ['views.alarmCenter.alarm', '重要', 'Critical'],
  ['views.alarmCenter.alarm', '次要', 'Minor'],
  ['views.alarmCenter.alarm', '无数据', 'No data'],
  ['views.alarmCenter.alarm', '检测规则', 'Detection rules'],
  ['views.alarmCenter.alarm', '服务名称', 'Service name'],
  ['views.alarmCenter.alarm', '告警处理', 'Handle alert'],
  ['views.alarmCenter.alarm', '关闭', 'Close'],
  ['views.alarmCenter.alarm', '处理备注', 'Handling remarks'],
  ['views.alarmCenter.alarm', '请选择处理状态', 'Please select handling status'],
  ['views.alarmCenter.alarm', '处理成功', 'Handled successfully'],
  ['views.alarmCenter.alarm', '处理失败', 'Handling failed'],
  ['views.alarmCenter.alarm', '您没有配置权限，请联系账号管理员申请', 'You do not have configuration permission. Please contact your account administrator.'],
  ['views.alarmCenter.alarm', '告警', 'Alert'],
  ['views.alarmCenter.alarm', '{value0} 活跃', '{value0} active'],
  ['views.alarmCenter.alarm', '发现 {value0} 条数据', 'Found {value0} records'],
  ['views.appMonitor.cache', '健康状态', 'Health Status'],
  ['views.appMonitor.cache', '平均响应时间', 'Avg Response Time'],
  ['views.appMonitor.cache', '错误率', 'Error Rate'],
  ['views.appMonitor.cache', '调用数', 'Call Count'],
  ['views.appMonitor.external', '告警数量', 'Alert Count'],
  ['views.appMonitor.external', '调用速率', 'Call Rate'],
  ['views.appMonitor.external', '近1分钟调用速率', 'Call Rate (Last 1 Min)'],
  ['views.appMonitor.external', '最大响应时间', 'Max Response Time'],
  ['views.appMonitor.relationMap', '请求量', 'Request Volume'],
  ['views.appMonitor.service', '收起图表', 'Collapse Chart'],
  ['views.appMonitor.service', '展开图表', 'Expand Chart'],
  ['views.appMonitor.service', '成功请求量', 'Successful Requests'],
  ['views.appMonitor.service', '失败请求量', 'Failed Requests'],
  ['views.appMonitor.service', '每分钟请求量', 'Requests Per Minute'],
  ['views.appMonitor.service', '数据来源', 'Data Source'],
  ['views.appMonitor.database', '每分钟调用数', 'Calls Per Minute'],
  ['views.appMonitor.database', '个 / 秒', '/ sec'],
  ['views.alarmCenter.alarm', '告警总数', 'Total alerts'],
  ['views.alarmCenter.alarm', '根因分析 (DeepSeek版)', 'Root cause analysis (DeepSeek)'],
  ['views.alarmCenter.alarm', '告警ID', 'Alert ID'],
  ['views.alarmCenter.alarm', '所属管理域', 'Management domain'],
  ['views.alarmCenter.alarm', '首次触发时间', 'First triggered at'],
  ['views.alarmCenter.alarm', '触发时间', 'Trigger time'],
  ['views.alarmCenter.alarm', '结束时间', 'End time'],
  ['views.alarmCenter.alarm', '持续时间', 'Duration'],
  ['views.alarmCenter.alarm', '事件数量', 'Event count'],
  ['views.alarmCenter.alarm', '应用', 'Application'],
  ['views.alarmCenter.alarm', '业务系统', 'Business system'],
  ['views.alarmCenter.alarm', '服务', 'Service'],
  ['views.alarmCenter.alarm', '服务实例', 'Service instance'],
  ['views.alarmCenter.alarm', '进程', 'Process'],
  ['views.alarmCenter.alarm', '主机', 'Host'],
  ['views.alarmCenter.alarm', '分区名', 'Partition name'],
  ['views.alarmCenter.alarm', '告警类型', 'Alert type'],
  ['views.alarmCenter.alarm', '告警描述', 'Alert description'],
  ['utils.filters', '已解决 (自动)', 'Resolved (auto)'],
  ['views.appMonitor.errors', '服务的错误数统计Top5', 'Top 5 Services by Error Count'],
  ['views.appMonitor.errors', '错误原因分布', 'Error Cause Distribution'],
  ['views.appMonitor.errors', '报错请求Top', 'Top Error Requests'],
  ['views.appMonitor.errors', '错误次数', 'Error Count'],
  ['views.appMonitor.errors', '错误占比', 'Error Share'],
  ['views.appMonitor.errors', '添加到搜索', 'Add to Search'],
  ['views.appMonitor.errors', '按错误原因', 'By Error Cause'],
  ['views.appMonitor.errors', '按接口名称', 'By Endpoint Name'],
  ['views.appMonitor.errorDetail', '错误请求趋势', 'Error Request Trend'],
  ['views.appMonitor.errorDetail', '错误请求', 'Error Requests'],
  ['views.appMonitor.errorDetail', '错误原因', 'Error Cause'],
  ['views.appMonitor.errorDetail', '上游请求', 'Upstream Requests'],
  ['views.appMonitor.errorDetail', '所属主机', 'Host'],
  ['views.appMonitor.errorDetail', '发生时间', 'Time'],
  ['views.appMonitor.errorDetail', '数据加载中', 'Loading...'],
  ['views.appMonitor.errorDetail', '无更多数据', 'No more data'],
  ['views.appMonitor.errorDetail', '未找到关联的 Trace 信息', 'No related trace found'],
  ['views.appMonitor.serviceAnalysis', '错误分析', 'Error Analysis'],
  ['views.appMonitor.serviceAnalysis', '添加到搜索', 'Add to Search'],
  ['views.appMonitor.serviceDetail', '服务实例名称', 'Service Instance Name'],
  ['views.appMonitor.errors', '服务实例名称', 'Service Instance Name'],
  ['views.aiMonitor.errors', '错误分析', 'Error Analysis'],
];

function applyGlossaryReplacements(text, glossary) {
  let out = text;
  for (const key of Object.keys(glossary).sort((a, b) => b.length - a.length)) {
    out = out.split(key).join(` ${glossary[key]} `);
  }
  return out;
}

function seedFixedMessages() {
  for (const [mod, source, en] of FIXED_MESSAGES) {
    const key = `${mod}.${slug(source)}`;
    manifest.set(source, key);
    setModuleMessage(mod, key.split('.').pop(), source, en);
  }
}

function translateToEn(text) {
  if (GLOSSARY[text]) return GLOSSARY[text];
  const glossary = { ...GLOSSARY, ...ENGLISH_CLEANUP_GLOSSARY };
  let out = applyGlossaryReplacements(text, glossary);
  return cleanGeneratedEnglish(out
    .replace(/，/g, ', ')
    .replace(/。/g, '.')
    .replace(/：/g, ': ')
    .replace(/；/g, '; ')
    .replace(/（/g, ' (')
    .replace(/）/g, ')')
    .replace(/？/g, '?'));
}

function translateChineseSegment(segment) {
  const exact = ENGLISH_CLEANUP_GLOSSARY[segment] || GLOSSARY[segment];
  if (exact) return exact;
  const glossary = { ...GLOSSARY, ...ENGLISH_CLEANUP_GLOSSARY };
  let out = applyGlossaryReplacements(segment, glossary);
  if (!CHINESE_RE.test(out)) return out.trim();
  const fallback = [...out]
    .map((char) => (CHINESE_RE.test(char) ? (CHINESE_CHAR_FALLBACK[char] || '') : char))
    .join('');
  return fallback.trim() || 'Text';
}

function cleanGeneratedEnglish(text) {
  return String(text)
    .replace(/[\u4e00-\u9fff]+/g, (segment) => translateChineseSegment(segment))
    .replace(/！/g, '!')
    .replace(/、/g, ', ')
    .replace(/【/g, '[')
    .replace(/】/g, ']')
    .replace(/「|『/g, '"')
    .replace(/」|』/g, '"')
    .replace(/\s+([,.:;!?])/g, '$1')
    .replace(/([([])\s+/g, '$1')
    .replace(/\s+([\])])/g, '$1')
    .replace(/\s{2,}/g, ' ')
    .trim();
}

function nest(flatModules) {
  const root = {};
  for (const [mod, messages] of [...flatModules.entries()].sort()) {
    const parts = mod.split('.');
    let cur = root;
    parts.forEach((part, idx) => {
      if (!cur[part]) cur[part] = {};
      if (idx === parts.length - 1) Object.assign(cur[part], messages);
      cur = cur[part];
    });
  }
  return root;
}

function parseModuleExport(filePath) {
  if (!fs.existsSync(filePath)) return null;
  const raw = fs.readFileSync(filePath, 'utf8');
  const json = raw
    .replace(/^\/\*\* Auto-generated[^\n]*\n/, '')
    .replace(/^export default /, '')
    .replace(/\s*as Record<[^>]+>;\s*$/, '');
  return JSON.parse(json);
}

function flattenMessageTree(zhNode, enNode, prefix) {
  if (!zhNode || typeof zhNode !== 'object') return;
  for (const [key, value] of Object.entries(zhNode)) {
    if (key.startsWith('s_') && typeof value === 'string') {
      const mod = prefix;
      const shortKey = key;
      const currentEn = typeof enNode?.[key] === 'string' ? enNode[key] : '';
      const en = currentEn && !CHINESE_RE.test(currentEn) ? currentEn : translateToEn(value);
      setModuleMessage(mod, shortKey, value, en);
      const existingKey = [...manifest.entries()].find(([, k]) => k === `${mod}.${shortKey}`);
      if (!existingKey) manifest.set(value, `${mod}.${shortKey}`);
    } else if (value && typeof value === 'object') {
      flattenMessageTree(value, enNode?.[key], prefix ? `${prefix}.${key}` : key);
    }
  }
}

function loadExistingState() {
  if (fs.existsSync(MANIFEST)) {
    const data = JSON.parse(fs.readFileSync(MANIFEST, 'utf8'));
    for (const [source, key] of Object.entries(data)) {
      manifest.set(source, key);
    }
  }
  const zhTree = parseModuleExport(path.join(OUT_DIR, 'zh-modules.ts'));
  const enTree = parseModuleExport(path.join(OUT_DIR, 'en-modules.ts'));
  if (zhTree) flattenMessageTree(zhTree, enTree, '');
}

function getI18nMigrationBaseCommit() {
  try {
    return execSync('git rev-list --max-parents=0 HEAD', {
      cwd: ROOT,
      encoding: 'utf8',
    })
      .trim()
      .split('\n')[0];
  } catch {
    return null;
  }
}

function tExpr(key, inScript = false) {
  if (!inScript) return `$t('modules.${key}')`;
  return `i18n.t('modules.${key}') as string`;
}

function templateTemplateLiteralToI18n(raw, rel) {
  if (!CHINESE_RE.test(raw) || raw.includes('\n')) return null;
  const values = [];
  const source = raw.replace(/\$\{([^{}]*(?:\{[^{}]*\}[^{}]*)*)\}/g, (_m, expr) => {
    const name = `value${values.length}`;
    values.push({ name, expr: expr.trim() });
    return `{${name}}`;
  });
  if (!CHINESE_RE.test(source)) return null;
  if (hasCodeLikeChars(source)) return null;
  const params = values.map(({ name, expr }) => `${name}: ${expr}`).join(', ');
  const key = keyFor(source, rel);
  return `$t('modules.${key}'${params ? `, { ${params} }` : ''})`;
}

function templateExpressionWithI18nStrings(expr, rel) {
  let next = expr.replace(/`((?:\\.|[^`])*[\u4e00-\u9fff](?:\\.|[^`])*)`/g, (m, raw) => {
    return templateTemplateLiteralToI18n(raw, rel) || m;
  });
  next = next.replace(/(['"])((?:\\.|(?!\1).)*[\u4e00-\u9fff](?:\\.|(?!\1).)*)\1/g, (m, _q, text) => {
    if (!isUiText(text)) return m;
    return tExpr(keyFor(text, rel));
  });
  return next;
}

function scriptTemplateLiteralToI18n(raw, rel, options = {}) {
  if (!CHINESE_RE.test(raw)) return null;
  if (raw.includes('\n') && !options.allowMultiline) return null;
  const values = [];
  const source = raw.replace(/\$\{([^{}]*(?:\{[^{}]*\}[^{}]*)*)\}/g, (_m, expr) => {
    const name = `value${values.length}`;
    values.push({ name, expr: expr.trim() });
    return `{${name}}`;
  });
  if (!CHINESE_RE.test(source)) return null;
  if (options.allowMultiline && /(^|\n)\s*(#|\||kubectl|curl|sudo|docker|export|pip|npm|yarn|dotnet|java|go\s|php\s)/i.test(source)) return null;
  if (hasCodeLikeChars(source)) return null;
  const params = values.map(({ name, expr }) => `${name}: ${expr}`).join(', ');
  const key = keyFor(source, rel);
  return `i18n.t('modules.${key}'${params ? `, { ${params} }` : ''}) as string`;
}

function hasNestedTemplateLiteral(expr) {
  return (expr.match(/`/g) || []).length > 2;
}

function isBrokenI18nExpression(expr) {
  return /as string[`$]/.test(expr) || /\$t\([^)]*\)\s*as string[^'"\s,)]+/.test(expr);
}

function scriptExpressionWithI18nStrings(expr, rel) {
  if (hasNestedTemplateLiteral(expr)) return expr;
  let next = expr.replace(/`((?:\\.|[^`])*[\u4e00-\u9fff](?:\\.|[^`])*)`/g, (m, raw) => {
    return scriptTemplateLiteralToI18n(raw, rel) || m;
  });
  next = next.replace(/(['"])((?:\\.|(?!\1).)*[\u4e00-\u9fff](?:\\.|(?!\1).)*)\1/g, (m, _q, text) => {
    if (!isUiText(text)) return m;
    return tExpr(keyFor(text, rel), true);
  });
  return next;
}

function templateTextWithInterpolations(text, rel) {
  const leading = text.match(/^\s*/)?.[0] || '';
  const trailing = text.match(/\s*$/)?.[0] || '';
  const body = text.trim();
  if (!body || !body.includes('{{') || !CHINESE_RE.test(body)) return null;

  const values = [];
  const source = body.replace(/\{\{\s*([\s\S]*?)\s*\}\}/g, (_m, expr) => {
    const name = `value${values.length}`;
    values.push({ name, expr: expr.trim() });
    return `{${name}}`;
  });
  if (!CHINESE_RE.test(source)) return null;
  const params = values.map(({ name, expr }) => `${name}: ${expr}`).join(', ');
  const key = keyFor(source, rel);
  return `${leading}{{ $t('modules.${key}'${params ? `, { ${params} }` : ''}) }}${trailing}`;
}

function dynamicTExpr(expr) {
  const trimmed = expr.trim();
  const match = trimmed.match(/^(.*)\.(label|title|describe|description|headerDescribe|normalText|errorText|warningText|placeholder|emptyText|content|message|name|text|nameCn|desc|group|category)$/);
  if (!match) return null;
  const keyExpr = `${match[1]}.${match[2]}Key`;
  return `${keyExpr} ? $t(${keyExpr}) : ${trimmed}`;
}

function maskTemplateCodeAttrs(template) {
  const chunks = [];
  const masked = template.replace(/(\s:?code=)(["'])([\s\S]*?)\2/g, (m) => {
    const token = `__I18N_CODE_ATTR_${chunks.length}__`;
    chunks.push(m);
    return token;
  });
  return {
    masked,
    restore: (content) => chunks.reduce((acc, chunk, index) => acc.replace(`__I18N_CODE_ATTR_${index}__`, chunk), content),
  };
}

function replaceTextCalls(content, rel) {
  return content
    .replace(/\$text\(\s*(['"`])((?:\\.|(?!\1).)*[\u4e00-\u9fff](?:\\.|(?!\1).)*)\1\s*\)/g, (_m, _q, text) => {
      if (!isUiText(text)) return _m;
      return tExpr(keyFor(text, rel));
    })
    .replace(/\$text\(\s*([^()]+?)\s*\)/g, (_m, expr) => {
      const replacement = dynamicTExpr(expr);
      return replacement || expr;
    });
}

function transformTemplate(template, rel) {
  const codeAttrMask = maskTemplateCodeAttrs(template);
  let result = replaceTextCalls(codeAttrMask.masked, rel);

  result = result.replace(/\{\{\s*([^{}]*[\u4e00-\u9fff][^{}]*)\s*\}\}/g, (m, expr) => {
    const next = templateExpressionWithI18nStrings(expr, rel);
    return next === expr ? m : `{{ ${next} }}`;
  });

  result = result.replace(/(\s)(:?)([\w-]+)=("([^"]*[\u4e00-\u9fff][^"]*)"|'([^']*[\u4e00-\u9fff][^']*)')/g, (m, space, bind, attr, raw, dText, sText) => {
    const text = dText ?? sText;
    if (TEMPLATE_SKIP_ATTRS.has(attr) || attr.startsWith('@')) return m;
    if (bind) {
      const next = templateExpressionWithI18nStrings(text, rel);
      return next === text ? m : `${space}${bind}${attr}=${raw[0]}${next}${raw[0]}`;
    }
    if (!isUiText(text)) return m;
    return `${space}:${attr}="${tExpr(keyFor(text, rel))}"`;
  });

  result = result.replace(/(:[\w-]+)=["'](['"`])([^'"`]*[\u4e00-\u9fff][^'"`]*)\2["']/g, (m, attr, _q, text) => {
    if (TEMPLATE_SKIP_ATTRS.has(attr.replace(/^:/, ''))) return m;
    if (!isUiText(text)) return m;
    return `${attr}="${tExpr(keyFor(text, rel))}"`;
  });

  result = result.replace(/\{\{\s*([a-zA-Z_$][\w.$]*\.(?:label|title|describe|description|headerDescribe|normalText|errorText|warningText|placeholder|emptyText|content|message|name))\s*\}\}/g, (_m, expr) => {
    const replacement = dynamicTExpr(expr);
    return replacement ? `{{ ${replacement} }}` : _m;
  });

  result = result.replace(/>([^<]*(?:\{\{[^<]*[\u4e00-\u9fff]|[\u4e00-\u9fff][^<]*\{\{)[^<]*)</g, (m, text) => {
    const replacement = templateTextWithInterpolations(text, rel);
    return replacement ? `>${replacement}<` : m;
  });

  result = result.replace(/>([^<>{}]*[\u4e00-\u9fff][^<>{}]*)</g, (m, text) => {
    if (!isUiText(text)) return m;
    const leading = text.match(/^\s*/)?.[0] || '';
    const trailing = text.match(/\s*$/)?.[0] || '';
    return `>${leading}{{ ${tExpr(keyFor(text, rel))} }}${trailing}<`;
  });

  return codeAttrMask.restore(result);
}

function addI18nImport(script) {
  if (!script.includes('i18n.t(') || /import\s+i18n\s+from\s+['"]@\/i18n['"]/.test(script)) return script;
  const importMatch = script.match(/(<script[\s\S]*?>\s*)(import[\s\S]*?;\s*)/);
  if (importMatch) {
    return script.replace(importMatch[0], `${importMatch[1]}import i18n from '@/i18n';\n${importMatch[2]}`);
  }
  const firstImportIndex = script.search(/\bimport\b/);
  if (firstImportIndex >= 0) {
    return `${script.slice(0, firstImportIndex)}import i18n from '@/i18n';\n${script.slice(firstImportIndex)}`;
  }
  const withScriptTag = script.replace(/(<script[\s\S]*?>)/, `$1\nimport i18n from '@/i18n';`);
  if (withScriptTag !== script) return withScriptTag;
  return `import i18n from '@/i18n';\n${script}`;
}

function removeUnusedTranslateImport(script) {
  if (script.includes('translateText(')) return script;
  return script
    .replace(/import\s+\{\s*translateText\s*\}\s+from\s+['"]@\/i18n['"];\n?/g, '')
    .replace(/import\s+i18n,\s*\{\s*translateText\s*\}\s+from\s+['"]@\/i18n['"];/g, "import i18n from '@/i18n';");
}

function transformScript(script, rel, isVue) {
  let result = script;

  result = result.replace(/\bthis\.\$text\(\s*(['"`])((?:\\.|(?!\1).)*[\u4e00-\u9fff](?:\\.|(?!\1).)*)\1\s*\)(?:\s+as\s+string)?/g, (m, _q, text) => {
    if (!isUiText(text)) return m;
    return tExpr(keyFor(text, rel), true);
  });

  result = result.replace(/\btranslateText\(\s*(['"`])((?:\\.|(?!\1).)*[\u4e00-\u9fff](?:\\.|(?!\1).)*)\1\s*\)(?:\s+as\s+string)?/g, (m, _q, text) => {
    if (!isUiText(text)) return m;
    return tExpr(keyFor(text, rel), true);
  });

  result = result.replace(/\b(this\.\$message\.(?:success|error|warning|info))\(\s*(['"`])((?:\\.|(?!\2).)*[\u4e00-\u9fff](?:\\.|(?!\2).)*)\2\s*\)/g, (m, fn, _q, text) => {
    if (!isUiText(text)) return m;
    return `${fn}(${tExpr(keyFor(text, rel), true)})`;
  });

  result = result.replace(/\b(this\.\$(?:confirm|alert|prompt))\(\s*(['"`])((?:\\.|(?!\2).)*[\u4e00-\u9fff](?:\\.|(?!\2).)*)\2/g, (m, fn, _q, text) => {
    if (!isUiText(text)) return m;
    return `${fn}(${tExpr(keyFor(text, rel), true)}`;
  });

  result = result.replace(/\bthis\.\$(?:alert|confirm|prompt|message\.\w+)\([^\n;]*[\u4e00-\u9fff][^\n;]*\)/g, (m) => {
    const next = scriptExpressionWithI18nStrings(m, rel);
    return isBrokenI18nExpression(next) ? m : next;
  });

  result = result.replace(/\bnew\s+Error\(([^)\n]*[\u4e00-\u9fff][^)\n]*)\)/g, (m, expr) => {
    const next = scriptExpressionWithI18nStrings(expr, rel);
    return next === expr ? m : `new Error(${next})`;
  });

  result = result.replace(/,\s*(['"`])((?:\\.|(?!\1).)*[\u4e00-\u9fff](?:\\.|(?!\1).)*)\1\s*,/g, (m, _q, text) => {
    if (!isUiText(text) || text.length > 20) return m;
    return `, ${tExpr(keyFor(text, rel), true)},`;
  });

  result = result.replace(/,\s*(['"`])((?:\\.|(?!\1).)*[\u4e00-\u9fff](?:\\.|(?!\1).)*)\1\s*(\))/g, (m, _q, text, suffix) => {
    if (!isUiText(text) || text.length > 30) return m;
    return `, ${tExpr(keyFor(text, rel), true)}${suffix}`;
  });

  result = result.replace(new RegExp(`\\b(${[...SCRIPT_EXPR_PROPS].join('|')})\\s*:\\s*([^\n,}]*[\\u4e00-\\u9fff][^\n,}]*)([,}])`, 'g'), (m, prop, expr, suffix) => {
    const next = scriptExpressionWithI18nStrings(expr, rel);
    return next === expr ? m : `${prop}: ${next}${suffix}`;
  });

  result = result.replace(new RegExp(`\\b(${[...SCRIPT_EXPR_PROPS].join('|')})\\s*:\\s*(\`(?:\\\\.|[^\`])*[\\u4e00-\\u9fff](?:\\\\.|[^\`])*\`)`, 'g'), (m, prop, expr) => {
    const raw = expr.slice(1, -1);
    const next = scriptTemplateLiteralToI18n(raw, rel, { allowMultiline: true });
    return next ? `${prop}: ${next}` : m;
  });

  result = result.replace(/([:[,]\s*)(['"])((?:\\.|(?!\2).)*[\u4e00-\u9fff](?:\\.|(?!\2).)*)\2(\s*[,}\]])/g, (m, prefix, _q, text, suffix) => {
    if (!isUiText(text)) return m;
    return `${prefix}${tExpr(keyFor(text, rel), true)}${suffix}`;
  });

  result = result.replace(new RegExp(`\\b(${[...KEYABLE_PROPS].join('|')})\\s*:\\s*(['"\`])((?:\\\\.|(?!\\2).)*[\\u4e00-\\u9fff](?:\\\\.|(?!\\2).)*)\\2`, 'g'), (m, prop, quote, text, offset, full) => {
    const after = full.slice(offset + m.length, offset + m.length + 80);
    if (new RegExp(`^\\s*,\\s*${prop}Key\\s*:`).test(after)) return m;
    if (quote === '`' && text.includes('${')) {
      const next = scriptTemplateLiteralToI18n(text, rel);
      return next ? `${prop}: ${next}` : m;
    }
    if (!isUiText(text)) return m;
    return `${prop}: ${quote}${text}${quote}, ${prop}Key: 'modules.${keyFor(text, rel)}'`;
  });

  result = result.replace(/\breturn\s+(`(?:\\.|[^`])*[\u4e00-\u9fff](?:\\.|[^`])*`)/g, (m, expr) => {
    const raw = expr.slice(1, -1);
    const next = scriptTemplateLiteralToI18n(raw, rel);
    return next ? `return ${next}` : m;
  });
  result = result.replace(/\breturn\s+(['"])((?:\\.|(?!\1).)*[\u4e00-\u9fff](?:\\.|(?!\1).)*)\1/g, (m, _q, text) => {
    if (!isUiText(text)) return m;
    return `return ${tExpr(keyFor(text, rel), true)}`;
  });

  result = result.replace(/\b(return|(?:const|let)\s+[$A-Z_a-z][$\w]*\s*=(?!=)|(?:this\.)?[$A-Z_a-z][$\w]*(?:\.[A-Z_a-z_$][\w$]*)+\s*=(?!=))\s*([^\n;]*[\u4e00-\u9fff][^\n;]*)(;?)/g, (m, prefix, expr, semi) => {
    if (hasNestedTemplateLiteral(expr)) return m;
    const next = scriptExpressionWithI18nStrings(expr, rel);
    if (next === expr || isBrokenI18nExpression(next)) return m;
    return `${prefix} ${next}${semi}`;
  });

  result = result.replace(/\b(new\s+Error\()\s*(['"`])((?:\\.|(?!\2).)*[\u4e00-\u9fff](?:\\.|(?!\2).)*)\2\s*(\))/g, (m, prefix, _q, text, suffix) => {
    if (!isUiText(text)) return m;
    return `${prefix}${tExpr(keyFor(text, rel), true)}${suffix}`;
  });

  result = addI18nImport(result);
  result = removeUnusedTranslateImport(result);
  return result;
}

function collectStrings(content, rel) {
  let m;
  while ((m = QUOTE_RE.exec(content))) {
    if (isUiText(m[2])) keyFor(m[2], rel);
  }
}

function transformFile(file) {
  const rel = path.relative(SRC, file).replace(/\\/g, '/');
  let content = fs.readFileSync(file, 'utf8');
  if (!CHINESE_RE.test(content) && !content.includes('$text(') && !content.includes('translateText(') && !content.includes('i18n.t(')) return;
  collectStrings(content, rel);
  const original = content;

  if (file.endsWith('.vue')) {
    const { descriptor } = parseSfc(content, { sourceMap: false });
    const replacements = [];
    if (descriptor.template) {
      replacements.push({
        start: descriptor.template.loc.start.offset,
        end: descriptor.template.loc.end.offset,
        value: transformTemplate(descriptor.template.content, rel),
      });
    }
    [descriptor.script, descriptor.scriptSetup].filter(Boolean).forEach((block) => {
      replacements.push({
        start: block.loc.start.offset,
        end: block.loc.end.offset,
        value: transformScript(block.content, rel, true),
      });
    });
    replacements
      .sort((a, b) => b.start - a.start)
      .forEach(({ start, end, value }) => {
        content = content.slice(0, start) + value + content.slice(end);
      });
  } else {
    content = transformScript(content, rel, false);
  }

  if (content !== original) {
    fs.writeFileSync(file, content);
    changedFiles++;
  }
}

function collectModuleRefs(files) {
  const refs = new Set();
  const refRe = /['"]((?:modules\.)?[A-Za-z0-9_.-]+\.s_[a-f0-9]{8})['"]/g;
  for (const file of files) {
    const content = fs.readFileSync(file, 'utf8');
    let match;
    while ((match = refRe.exec(content))) {
      const key = match[1].startsWith('modules.') ? match[1] : `modules.${match[1]}`;
      refs.add(key);
    }
  }
  return refs;
}

function addCandidate(candidates, text) {
  const source = cleanText(unescapeSourceText(text));
  if (!isUiText(source)) return;
  const key = slug(source);
  if (!candidates.has(key) || candidates.get(key).length < source.length) {
    candidates.set(key, source);
  }
}

function addInterpolatedCandidate(candidates, text, interpolationRe) {
  let idx = 0;
  const source = cleanText(unescapeSourceText(text).replace(interpolationRe, () => `{value${idx++}}`));
  addCandidate(candidates, source);
}

function extractDeletedLineTexts(line, candidates) {
  const raw = line.replace(/^-/, '');
  let match;
  while ((match = QUOTE_RE.exec(raw))) {
    addCandidate(candidates, match[2]);
    if (match[2].includes('${')) {
      addInterpolatedCandidate(candidates, match[2], /\$\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}/g);
    }
  }
  QUOTE_RE.lastIndex = 0;

  const simpleStringRe = /(['"`])((?:\\.|(?!\1)[^\n])*[\u4e00-\u9fff](?:\\.|(?!\1)[^\n])*)\1/g;
  while ((match = simpleStringRe.exec(raw))) {
    addCandidate(candidates, match[2]);
    if (match[1] === '`' && match[2].includes('${')) {
      addInterpolatedCandidate(candidates, match[2], /\$\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}/g);
    }
  }

  const singleStringRe = /'((?:\\.|[^'\n])*[\u4e00-\u9fff](?:\\.|[^'\n])*)'/g;
  while ((match = singleStringRe.exec(raw))) {
    addCandidate(candidates, match[1]);
  }

  const backtickStringRe = /`((?:\\.|[^`\n])*[\u4e00-\u9fff](?:\\.|[^`\n])*)`/g;
  while ((match = backtickStringRe.exec(raw))) {
    addCandidate(candidates, match[1]);
    if (match[1].includes('${')) {
      addInterpolatedCandidate(candidates, match[1], /\$\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}/g);
    }
  }

  const interpolatedTextNodeRe = />([^<]*\{\{[\s\S]*?[\u4e00-\u9fff][^<]*|[^<]*[\u4e00-\u9fff][^<]*\{\{[\s\S]*?[^<]*)</g;
  while ((match = interpolatedTextNodeRe.exec(raw))) {
    addInterpolatedCandidate(candidates, match[1], /\{\{\s*[\s\S]*?\s*\}\}/g);
  }

  const textNodeRe = />([^<]*[\u4e00-\u9fff][^<]*)/g;
  while ((match = textNodeRe.exec(raw.replace(/\{\{[\s\S]*?\}\}/g, '  ')))) {
    addCandidate(candidates, match[1]);
    match[1].split(/\s{2,}/).forEach((piece) => addCandidate(candidates, piece));
  }

  const withoutBlocks = raw
    .replace(/\{\{[\s\S]*?\}\}/g, ' ')
    .replace(/<script[\s\S]*?<\/script>/g, ' ')
    .replace(/<style[\s\S]*?<\/style>/g, ' ')
    .replace(/<[^>]+>/g, ' ');
  withoutBlocks
    .split(/[{}[\]();,|?:=+*/]/)
    .forEach((part) => {
      addCandidate(candidates, part);
      part.split(/\s{2,}/).forEach((piece) => addCandidate(candidates, piece));
    });
}

function collectDeletedTextCandidates() {
  const candidates = new Map();
  const diffRanges = ['-- src'];
  const baseCommit = getI18nMigrationBaseCommit();
  if (baseCommit) diffRanges.push(`${baseCommit}..HEAD -- src`);

  for (const range of diffRanges) {
    let diff = '';
    try {
      diff = execSync(`git diff --no-ext-diff --unified=0 ${range}`, {
        cwd: ROOT,
        encoding: 'utf8',
        maxBuffer: 1024 * 1024 * 64,
      });
    } catch {
      continue;
    }
    diff.split('\n').forEach((line) => {
      if (!line.startsWith('-') || line.startsWith('---')) return;
      if (!CHINESE_RE.test(line)) return;
      extractDeletedLineTexts(line, candidates);
    });
  }
  return candidates;
}

function collectExistingMessagesByShortKey() {
  const byShortKey = new Map();
  for (const [mod, messages] of zhByModule.entries()) {
    const enMessages = enByModule.get(mod) || {};
    for (const [shortKey, zh] of Object.entries(messages)) {
      if (!byShortKey.has(shortKey)) {
        const currentEn = enMessages[shortKey];
        byShortKey.set(shortKey, { zh, en: currentEn && !CHINESE_RE.test(currentEn) ? currentEn : translateToEn(zh) });
      }
    }
  }
  return byShortKey;
}

function seedReferencedMessages(files) {
  const refs = collectModuleRefs(files);
  const deletedCandidates = collectDeletedTextCandidates();
  const existingByShortKey = collectExistingMessagesByShortKey();
  for (const ref of refs) {
    const { mod, shortKey } = keyParts(ref);
    if (hasModuleMessage(mod, shortKey)) continue;
    const existing = existingByShortKey.get(shortKey);
    if (existing) {
      setModuleMessage(mod, shortKey, existing.zh, existing.en);
      continue;
    }
    const source = deletedCandidates.get(shortKey);
    if (source) {
      manifest.set(source, `${mod}.${shortKey}`);
      setModuleMessage(mod, shortKey, source, translateToEn(source));
    }
  }
}

function writeMessages() {
  fs.mkdirSync(OUT_DIR, { recursive: true });
  fs.writeFileSync(MANIFEST, JSON.stringify(Object.fromEntries([...manifest.entries()].sort()), null, 2));
  const writeTree = (obj, name) => {
    fs.writeFileSync(
      path.join(OUT_DIR, name),
      `/** Auto-generated - run: yarn i18n:sync */\nexport default ${JSON.stringify(obj, null, 2)} as Record<string, unknown>;\n`,
    );
  };
  writeTree(nest(zhByModule), 'zh-modules.ts');
  writeTree(nest(enByModule), 'en-modules.ts');
}

loadExistingState();
const files = walk(SRC).filter(shouldScan);
if (!WRITE_MESSAGES_ONLY) {
  files.forEach(transformFile);
}
seedFixedMessages();
seedReferencedMessages(files);
writeMessages();
console.log(`Normalized ${changedFiles} files, ${manifest.size} locale entries`);
