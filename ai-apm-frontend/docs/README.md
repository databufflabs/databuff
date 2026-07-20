# Databuff 文档中心

> 最后更新: 2026-04-05
> 说明: 本页只为当前仓库中已经存在的文档提供可点击导航；其余内容以规划说明呈现，避免死链。

## 项目简介

Databuff 是一个可观测性平台，提供基础设施、应用性能、用户体验、日志、指标与智能告警等能力。

| 属性 | 值 |
|------|-----|
| 当前版本 | 2.9.2 |
| 框架 | Vue 2.7 + TypeScript |
| 构建工具 | Vite 6.3 |
| UI 组件库 | Element UI 2.15 |

---

## 已完成文档

### 架构文档

| 文档 | 说明 |
|------|------|
| [架构概述](architecture/overview.md) | 项目整体架构与分层 |
| [技术栈](architecture/tech-stack.md) | 运行环境、依赖与技术选型 |
| [目录结构](architecture/directory-structure.md) | 当前代码仓目录组织方式 |

### 路由系统

| 文档 | 说明 |
|------|------|
| [路由概述](router/README.md) | 路由系统整体设计 |
| [路由类型](router/route-types.md) | TypeScript 类型定义 |
| [路由数据](router/route-data.md) | 菜单与路由配置说明 |
| [面包屑](router/breadcrumb.md) | 面包屑路径配置 |
| [时间选择器](router/time-options.md) | 时间范围选项与 URL 参数 |

### 智能告警模块

| 文档 | 说明 |
|------|------|
| [模块概览](modules/alarm-center/README.md) | 智能告警模块入口与导航关系 |
| [告警列表](modules/alarm-center/alarm-list.md) | 告警列表页 |
| [告警详情](modules/alarm-center/alarm-detail.md) | 告警详情页 |
| [通知记录](modules/alarm-center/notice.md) | 通知记录页 |
| [问题列表](modules/alarm-center/problem-list.md) | 问题列表页 |
| [问题详情](modules/alarm-center/problem-detail.md) | 问题详情页 |
| [问题分析](modules/alarm-center/problem-analysis.md) | 问题分析页 |
| [根因分析](modules/alarm-center/root-cause-analysis.md) | 手动根因分析页 |
| [事件详情](modules/alarm-center/event-detail.md) | 事件详情组件/独立页说明 |

### 应用性能监控模块

| 文档 | 说明 |
|------|------|
| [模块总览](modules/app-monitor/README.md) | `appMonitor` 页面矩阵与主链路 |
| [服务监控](modules/app-monitor/service.md) | 服务列表与主入口页 |
| [接口调用分析](modules/app-monitor/service-call.md) | 服务间接口调用与池化视图 |
| [服务流](modules/app-monitor/service-flow.md) | 服务流拓扑 |
| [链路追踪](modules/app-monitor/trace.md) | Trace 查询与下钻 |
| [调用链详情](modules/app-monitor/trace-detail.md) | Span 链路详情页 |
| [线程列表](modules/app-monitor/thread.md) | 线程剖析结果详情 |
| [错误分析](modules/app-monitor/errors.md) | 错误趋势与列表 |
| [错误详情](modules/app-monitor/error-detail.md) | 错误场景下钻 |
| [数据库监控](modules/app-monitor/database.md) | 数据库列表 |
| [缓存监控](modules/app-monitor/cache.md) | 缓存列表 |
| [消息队列](modules/app-monitor/msg-queue.md) | MQ / Topic 列表 |
| [HTTP 连接池](modules/app-monitor/http-conn-pool.md) | HTTP 连接池指标 |
| [数据库连接池](modules/app-monitor/db-conn-pool.md) | 数据库连接池指标 |
| [系统拓扑](modules/app-monitor/topology.md) | 全局系统拓扑 |
| [空间地图](modules/app-monitor/relation-map.md) | 多层级空间地图 |
| [诊断分析](modules/app-monitor/diagnostic.md) | Dump 与线程剖析入口 |
| [业务系统](modules/app-monitor/business-system.md) | 业务系统管理与主视图 |
| [业务系统调用分析](modules/app-monitor/business-call.md) | 业务/服务调用分析 |

### 基础设施模块

| 文档 | 说明 |
|------|------|
| [模块总览](modules/infrastructure/README.md) | `infrastructure` 页面矩阵与下钻关系 |
| [主机监控](modules/infrastructure/host.md) | 主机列表、主机地图与详情 |
| [Kubernetes 集群](modules/infrastructure/cluster.md) | 集群列表与详情 |
| [Node 监控](modules/infrastructure/node.md) | Node 列表、摘要图与详情 |
| [Namespace](modules/infrastructure/namespace.md) | 命名空间列表与详情 |
| [Workload](modules/infrastructure/workload.md) | 工作负载列表与详情 |
| [Pod](modules/infrastructure/pod.md) | Pod 列表与详情 |
| [Docker 容器](modules/infrastructure/docker.md) | 容器列表与详情 |
| [进程监控](modules/infrastructure/process.md) | 进程树与详情 |
| [Kubernetes Service](modules/infrastructure/service.md) | Service 列表与详情 |
| [技术组件](modules/infrastructure/tech.md) | 未开放路由页面说明 |

### 配置管理模块

| 文档 | 说明 |
|------|------|
| [模块总览](modules/config-manage/README.md) | `configManage` 菜单结构与静态设置页 |
| [实体管理](modules/config-manage/entity.md) | 服务/业务系统/进程/请求/拓扑配置 |
| [告警配置](modules/config-manage/alarm.md) | 检测规则 |
| [AI 配置](modules/config-manage/ai.md) | 外接大模型配置 |
| [环境标签](modules/config-manage/env-tag.md) | 环境标签开关、列表与编辑页 |

### 系统管理模块

| 文档 | 说明 |
|------|------|
| [模块总览](modules/sys-manage/README.md) | `sysManage` 页面矩阵与复用页关系 |
| [账户管理](modules/sys-manage/account.md) | 用户、密码与通知绑定 |
| [角色权限](modules/sys-manage/role.md) | 角色树、权限、管理域、环境 |
| [组织管理](modules/sys-manage/org.md) | 组织与成员管理 |
| [管理域](modules/sys-manage/group.md) | 管理域与未分配实体 |
| [许可证管理](modules/sys-manage/license.md) | License 信息与上传更新 |
| [通知配置](modules/sys-manage/notice.md) | 邮件/短信/钉钉/企微/Socket |
| [基础设置](modules/sys-manage/basic.md) | 时间同步与页面超时 |
| [健康度配置](modules/sys-manage/health.md) | 评分规则与颜色分级 |
| [操作审计](modules/sys-manage/operation-audit.md) | 时间线审计与跨模块下钻 |
| [系统事件](modules/sys-manage/system-event.md) | 系统事件列表与详情入口 |
| [系统规则](modules/sys-manage/system-rule.md) | 系统检测规则与设置页复用 |

### 配置安装模块

| 文档 | 说明 |
|------|------|
| [模块总览](modules/config-install/README.md) | `deployInstall` 数据接入 tab |
| [APM 配置](modules/config-install/apm.md) | OpenTelemetry / OTLP 接入 |
| [日志采集](modules/config-install/log.md) | 日志采集与 Trace 关联说明 |

### 数据报表模块

| 文档 | 说明 |
|------|------|
| [模块总览](modules/data-report/README.md) | `dataReport` 页面与静态页关系 |
| [仪表盘](modules/data-report/dashboard.md) | Dashboard iframe 嵌入页 |
| [报告](modules/data-report/report.md) | 报告列表、模板列表与设置页 |

### 网络性能模块

| 文档 | 说明 |
|------|------|
| [模块总览](modules/npm/README.md) | `npm` 页面矩阵与下钻关系 |
| [网络分析](modules/npm/analysis.md) | 网络连接趋势与列表分析 |
| [网络拓扑](modules/npm/topology.md) | 节点/边拓扑与分析页下钻 |
| [DNS 分析](modules/npm/dns.md) | DNS 请求与错误分析 |

### 日志分析模块

| 文档 | 说明 |
|------|------|
| [模块总览](modules/log/README.md) | `log` 单页模块能力概览 |
| [日志分析](modules/log/analysis.md) | 搜索、筛选、上下文和日志对比 |

### 指标体系模块

| 文档 | 说明 |
|------|------|
| [模块总览](modules/metrics/README.md) | `metrics` 页面矩阵与模块能力 |
| [指标列表](modules/metrics/list.md) | 分类树、指标详情与核心配置 |
| [指标分析](modules/metrics/analysis.md) | 分析单元配置与图表预览 |

### 驾驶舱模块

| 文档 | 说明 |
|------|------|
| [模块总览](modules/cockpit/README.md) | `cockpit` 单路由多页签结构 |
| [工作台](modules/cockpit/workbench.md) | 实体趋势与告警总览 |
| [业务系统](modules/cockpit/business.md) | 业务系统健康卡片与指标配置 |
| [告警](modules/cockpit/alarm.md) | 业务系统 / 服务告警概览 |
| [故障排查](modules/cockpit/fault.md) | 告警 / 异常趋势与根因分析入口 |

### 业务观测模块

| 文档 | 说明 |
|------|------|
| [模块总览](modules/observe/README.md) | `observe` 场景/事件能力总览 |
| [业务场景](modules/observe/scene.md) | 场景树、业务地图与转化漏斗 |
| [场景地图编辑](modules/observe/scene-manage.md) | 场景图编辑与 KPI 配置 |
| [业务事件](modules/observe/event.md) | 事件列表与事件编辑弹窗 |

### 访问体验模块

| 文档 | 说明 |
|------|------|
| [模块总览](modules/rum/README.md) | `rum` 页面矩阵与静态详情页关系 |
| [应用管理](modules/rum/application.md) | 应用列表、接入、配置、详情 |
| [页面分析](modules/rum/page.md) | 页面趋势、别名与页面详情 |
| [行为分析](modules/rum/action.md) | 用户行为趋势、别名与详情 |
| [JS 错误](modules/rum/js-error.md) | 错误趋势、详情与追踪 |
| [请求分析](modules/rum/request.md) | 请求趋势、接口详情与样本追踪 |
| [链路追踪](modules/rum/trace.md) | RUM Trace 查询 |
| [会话分析](modules/rum/session.md) | 会话趋势、详情与移动追踪页 |
| [资源分析](modules/rum/resource.md) | 资源统计与配置 |
| [用户分析](modules/rum/user.md) | 用户画像与会话/错误联动 |
| [帮助中心](modules/rum/help.md) | Web / iOS / Android 接入帮助 |

### 登录模块

| 文档 | 说明 |
|------|------|
| [模块总览](modules/login/README.md) | 登录页结构、登录流程与路由守卫关系 |

### 授权模块

| 文档 | 说明 |
|------|------|
| [模块总览](modules/authorization/README.md) | License 授权状态、上传流程与超管初始化 |

### 个人中心模块

| 文档 | 说明 |
|------|------|
| [模块总览](modules/personal/README.md) | 当前用户资料、密码与通知接收方式维护 |

### 帮助中心模块

| 文档 | 说明 |
|------|------|
| [模块总览](modules/help/README.md) | `help` 页面矩阵与未开放页面说明 |
| [入门指南](modules/help/start-guide.md) | 新用户主机、仪表盘、插件、账户引导 |
| [时间同步](modules/help/time-sync.md) | 时间同步背景、命令与公共 NTP 列表 |
| [NTP 服务](modules/help/setup-ntp.md) | 自建 NTP 服务端与客户端配置 |
| [Nginx Tracing 说明](modules/help/nginx-tracing.md) | 代码存在但当前未开放的说明页 |

### 共享组件文档

| 文档 | 说明 |
|------|------|
| [组件总览](components/README.md) | `src/components/` 共享组件索引 |
| [Charts](components/charts/index.md) | 图表组件目录与 `BasicChart` 总览 |
| [DbTable](components/business/db-table.md) | 增强表格组件 |
| [DbTabnav](components/business/db-tabnav.md) | 轻量页签导航 |
| [DbMenu](components/business/db-menu.md) | 平台左侧菜单组件 |
| [DbRadio](components/business/db-radio.md) | 滑块式分段选择器 |
| [Dialog Template](components/business/dialog-template.md) | 弹窗模板骨架 |
| [IpInput](components/form/ip-input.md) | IPv4 分段输入组件 |
| [Metric Select](components/form/metric-select.md) | 指标选择器与详情 tooltip |
| [Metric Cascader](components/form/metric-cascader.md) | 指标分类级联选择器 |
| [Metric Unit Cascader](components/form/metric-unit-cascader.md) | 指标单位级联选择器 |
| [Query Filter](components/filter/query-filter.md) | URL 联动型筛选组件 |
| [Scroll Select](components/form/scroll-select.md) | 带滚动加载的选择器 |
| [Code View](components/display/code-view.md) | 代码块与行内代码展示 |
| [Marked View](components/display/marked-view.md) | Markdown 渲染组件 |
| [Statistic](components/display/statistic.md) | 轻量统计值展示 |
| [Text Expand](components/display/text-expand.md) | 长文本折叠/展开 |
| [Collapse Tags](components/display/collapse-tags.md) | 标签折叠展示 |
| [Metric Info Tooltip](components/display/metric-info-tooltip.md) | 指标详情卡片 |
| [Matching Criteria](components/filter/matching-criteria.md) | 条件编排组件 |
| [Cont Wrapper](components/layout/cont-wrapper.md) | 页面内容容器 |
| [Router View Temp](components/layout/router-view-temp.md) | 纯占位路由容器 |
| [Chat AI](components/special/chat-ai.md) | AI 根因分析抽屉 |
| [Flame Chart](components/special/flame-chart.md) | 火焰图底层渲染库 |
| [Db Icon Button](components/special/db-icon-button.md) | 轻量图标按钮 |

### Store 文档

| 文档 | 说明 |
|------|------|
| [Store 总览](store/README.md) | `src/store/` 结构与模块索引 |
| [User Store](store/user.md) | 登录态、菜单、角色、管理域、Logo 配置 |
| [Common Store](store/common.md) | 指标分类、标签映射、环境标签缓存 |
| [Global Store](store/global.md) | 全局时间、刷新、事件栈与图表栈 |
| [Service Store](store/service.md) | 服务基础信息、业务树与服务映射 |

### Utils 文档

| 文档 | 说明 |
|------|------|
| [Utils 总览](utils/README.md) | `src/utils/` 工具目录索引 |
| [axios](utils/axios.md) | Axios 实例、请求/响应拦截器、环境标签注入 |
| [auth](utils/auth.md) | 授权状态判断器 `AuthBuilder` |
| [cookie](utils/cookie.md) | 登录态、管理域、环境标签 Cookie/LocalStorage 操作 |
| [time-format](utils/time-format.md) | 时间范围与 interval 计算 |
| [unit-data](utils/unit-data.md) | 单位元数据和格式化比例 |
| [common](utils/common.md) | 复制、异步包装、EventBus 等通用工具 |
| [regexp](utils/regexp.md) | IP、XSS、命名规则等正则与校验函数 |
| [browser](utils/browser.md) | 浏览器识别与最低版本校验 |
| [version](utils/version.md) | 版本号比较与排序 |
| [echarts](utils/echarts.md) | ECharts 按需注册入口 |

### 开发指南

| 文档 | 说明 |
|------|------|
| [快速开始](guides/getting-started.md) | 环境准备、启动命令、入口认知与阅读顺序 |
| [编码规范](guides/coding-standards.md) | 当前仓库约定下的代码风格与分层原则 |
| [组件开发指南](guides/component-development.md) | 共享组件与页面私有组件的拆分建议 |
| [API 开发指南](guides/api-development.md) | `src/api/*` 的组织方式与请求封装约定 |
| [文档维护指南](guides/documentation-maintenance.md) | 代码变更与文档更新的映射关系、模板与检查清单 |
| [测试指南](guides/testing.md) | 当前仓库的构建校验与人工回归建议 |

### API 模块

| 文档 | 说明 |
|------|------|
| [API 总览](api/README.md) | `src/api/` 目录总览与调用约定 |
| [Agent API](api/agent.md) | Agent 升级、日志、安装配置 |
| [Alarm API](api/alarm.md) | 告警、策略、通知、系统事件、AI |
| [APM API](api/apm.md) | 服务概览、调用链、慢接口、智能分析 |
| [Business System API](api/bs.md) | 业务系统、空间地图、拓扑、业务线 |
| [Config API](api/config.md) | 高级配置、AI 配置、平台展示配置 |
| [Data Access API](api/data-access.md) | 管道、模板、处理器、调试 |
| [Data Collect API](api/data-coll.md) | 请求属性采集配置 |
| [Env Tag API](api/env-tag.md) | 环境标签与角色绑定 |
| [Group API](api/group.md) | 管理域、规则、自动分组 |
| [Service API](api/service.md) | 服务、调用分析、Dump、线程分析 |
| [Infrastructure API](api/infrastructure.md) | 主机、容器、进程接口 |
| [Kubernetes API](api/kubernetes.md) | K8s 资源查询 |
| [Monitor API](api/monitor.md) | 检测规则、预设规则、系统规则 |
| [Metric API](api/metric.md) | 指标查询与核心配置 |
| [Log API](api/log.md) | 日志检索与筛选条件 |
| [Notice API](api/notice.md) | 系统级通知通道配置 |
| [NPM API](api/npm.md) | 网络性能监控与 DNS 分析 |
| [Plugin API](api/plugin.md) | 插件管理与指标 |
| [Process API](api/process.md) | 进程采集/识别规则 |
| [Report API](api/report.md) | 报告与模板管理 |
| [Scene API](api/scene.md) | 业务场景、事件、漏斗、KPI |
| [User API](api/user.md) | 登录、用户、组织、单点登录 |
| [System API](api/system.md) | 系统管理、角色、账户、审计 |
| [Root Cause API](api/root-cause.md) | 问题、根因分析、影响面分析 |

### 结构规划

| 文档 | 说明 |
|------|------|
| [文档目录规划](DOC-STRUCTURE.md) | 后续补全文档时的目录规划 |

---

## 规划中的文档目录

当前无待创建的顶层文档目录，后续以补充 `components` 余下文档为主。

---

## 当前文档结构

```text
docs/
├── README.md
├── DOC-STRUCTURE.md
├── api/
│   ├── README.md
│   ├── agent.md
│   ├── alarm.md
│   ├── apm.md
│   ├── bs.md
│   ├── config.md
│   ├── data-access.md
│   ├── data-coll.md
│   ├── env-tag.md
│   ├── group.md
│   ├── infrastructure.md
│   ├── kubernetes.md
│   ├── log.md
│   ├── metric.md
│   ├── monitor.md
│   ├── notice.md
│   ├── npm.md
│   ├── plugin.md
│   ├── process.md
│   ├── report.md
│   ├── root-cause.md
│   ├── scene.md
│   ├── service.md
│   ├── system.md
│   └── user.md
├── architecture/
│   ├── overview.md
│   ├── tech-stack.md
│   └── directory-structure.md
├── components/
│   ├── README.md
│   ├── charts/
│   │   └── index.md
│   ├── business/
│   │   ├── db-table.md
│   │   ├── db-tabnav.md
│   │   ├── db-menu.md
│   │   ├── db-radio.md
│   │   └── dialog-template.md
│   ├── form/
│   │   ├── ip-input.md
│   │   ├── metric-cascader.md
│   │   ├── metric-select.md
│   │   ├── metric-unit-cascader.md
│   │   └── scroll-select.md
│   ├── display/
│   │   ├── code-view.md
│   │   ├── collapse-tags.md
│   │   ├── marked-view.md
│   │   ├── metric-info-tooltip.md
│   │   ├── statistic.md
│   │   └── text-expand.md
│   ├── filter/
│   │   ├── matching-criteria.md
│   │   └── query-filter.md
│   ├── layout/
│   │   ├── cont-wrapper.md
│   │   └── router-view-temp.md
│   └── special/
│       ├── chat-ai.md
│       ├── db-icon-button.md
│       └── flame-chart.md
├── store/
│   ├── README.md
│   ├── common.md
│   ├── global.md
│   ├── service.md
│   └── user.md
├── utils/
│   ├── README.md
│   ├── auth.md
│   ├── axios.md
│   ├── browser.md
│   ├── common.md
│   ├── cookie.md
│   ├── echarts.md
│   ├── regexp.md
│   ├── time-format.md
│   ├── unit-data.md
│   └── version.md
├── guides/
│   ├── api-development.md
│   ├── coding-standards.md
│   ├── component-development.md
│   ├── documentation-maintenance.md
│   ├── getting-started.md
│   └── testing.md
├── modules/
│   ├── data-report/
│   │   ├── README.md
│   │   ├── dashboard.md
│   │   └── report.md
│   ├── npm/
│   │   ├── README.md
│   │   ├── analysis.md
│   │   ├── dns.md
│   │   └── topology.md
│   ├── log/
│   │   ├── README.md
│   │   └── analysis.md
│   ├── metrics/
│   │   ├── README.md
│   │   ├── analysis.md
│   │   └── list.md
│   ├── cockpit/
│   │   ├── README.md
│   │   ├── alarm.md
│   │   ├── business.md
│   │   ├── fault.md
│   │   └── workbench.md
│   ├── observe/
│   │   ├── README.md
│   │   ├── event.md
│   │   ├── scene-manage.md
│   │   └── scene.md
│   ├── rum/
│   │   ├── README.md
│   │   ├── action.md
│   │   ├── application.md
│   │   ├── help.md
│   │   ├── js-error.md
│   │   ├── page.md
│   │   ├── request.md
│   │   ├── resource.md
│   │   ├── session.md
│   │   ├── trace.md
│   │   └── user.md
│   ├── config-status/
│   │   ├── README.md
│   │   ├── agent-packages.md
│   │   ├── agent.md
│   │   └── run-log.md
│   ├── login/
│   │   └── README.md
│   ├── authorization/
│   │   └── README.md
│   ├── personal/
│   │   └── README.md
│   ├── help/
│   │   ├── README.md
│   │   ├── nginx-tracing.md
│   │   ├── setup-ntp.md
│   │   ├── start-guide.md
│   │   └── time-sync.md
│   ├── config-install/
│   │   ├── README.md
│   │   ├── agent.md
│   │   ├── apm.md
│   │   ├── data-access.md
│   │   ├── log.md
│   │   ├── npm.md
│   │   └── plugin.md
│   ├── sys-manage/
│   │   ├── README.md
│   │   ├── account.md
│   │   ├── basic.md
│   │   ├── group.md
│   │   ├── health.md
│   │   ├── license.md
│   │   ├── notice.md
│   │   ├── operation-audit.md
│   │   ├── org.md
│   │   ├── role.md
│   │   ├── system-event.md
│   │   └── system-rule.md
│   ├── config-manage/
│   │   ├── README.md
│   │   ├── ai.md
│   │   ├── alarm.md
│   │   ├── entity.md
│   │   └── env-tag.md
│   ├── infrastructure/
│   │   ├── README.md
│   │   ├── cluster.md
│   │   ├── docker.md
│   │   ├── host.md
│   │   ├── namespace.md
│   │   ├── node.md
│   │   ├── pod.md
│   │   ├── process.md
│   │   ├── service.md
│   │   ├── tech.md
│   │   └── workload.md
│   ├── app-monitor/
│   │   ├── README.md
│   │   ├── business-call.md
│   │   ├── business-system.md
│   │   ├── cache.md
│   │   ├── database.md
│   │   ├── db-conn-pool.md
│   │   ├── diagnostic.md
│   │   ├── error-detail.md
│   │   ├── errors.md
│   │   ├── http-conn-pool.md
│   │   ├── msg-queue.md
│   │   ├── relation-map.md
│   │   ├── service-call.md
│   │   ├── service-flow.md
│   │   ├── service.md
│   │   ├── thread.md
│   │   ├── topology.md
│   │   ├── trace-detail.md
│   │   └── trace.md
│   └── alarm-center/
│       ├── README.md
│       ├── alarm-list.md
│       ├── alarm-detail.md
│       ├── notice.md
│       ├── problem-list.md
│       ├── problem-detail.md
│       ├── problem-analysis.md
│       ├── root-cause-analysis.md
│       └── event-detail.md
└── router/
    ├── README.md
    ├── route-types.md
    ├── route-data.md
    ├── breadcrumb.md
    └── time-options.md
```

---

## 文档状态

| 目录 | 状态 | 进度 |
|------|------|------|
| architecture | ✅ 已完成 | 3/3 |
| modules | ✅ 已完成 | 107/107 |
| router | ✅ 已完成 | 5/5 |
| api | ✅ 已完成 | 25/25 |
| components | ✅ 已完成 | 25/25 |
| store | ✅ 已完成 | 5/5 |
| utils | ✅ 已完成 | 10/10 |
| guides | ✅ 已完成 | 6/6 |

---

## 更新日志

| 日期 | 更新内容 |
|------|----------|
| 2026-04-05 | 初始化文档结构，完成架构文档 |
| 2026-04-05 | 完成路由系统文档（5 个文件） |
| 2026-04-05 | 完成智能告警模块文档（10 个文件） |
| 2026-04-05 | 修正文档导航死链，并按代码现状校正文档内容 |
| 2026-04-05 | 新增 API 模块文档（8 个文件） |
| 2026-04-05 | 继续补充 API 模块文档（APM、Monitor、Log、Notice） |
| 2026-04-05 | 继续补充 API 模块文档（Config、Group、EnvTag、DataAccess） |
| 2026-04-05 | 补全剩余 API 文档并完成 `src/api/` 全量覆盖 |
| 2026-04-05 | 新增应用性能监控模块文档（19 个文件） |
| 2026-04-05 | 新增基础设施模块文档（11 个文件） |
| 2026-04-05 | 新增配置管理模块文档（5 个文件） |
| 2026-04-05 | 新增系统管理模块文档（12 个文件） |
| 2026-04-05 | 新增配置安装模块文档（7 个文件） |
| 2026-04-05 | 新增数据报表模块文档（3 个文件） |
| 2026-04-05 | 新增网络性能模块文档（4 个文件） |
| 2026-04-05 | 新增日志分析模块文档（2 个文件） |
| 2026-04-05 | 新增指标体系模块文档（3 个文件） |
| 2026-04-05 | 新增驾驶舱模块文档（5 个文件） |
| 2026-04-05 | 新增业务观测模块文档（4 个文件） |
| 2026-04-05 | 新增访问体验模块文档（11 个文件） |
| 2026-04-05 | 新增部署状态模块文档（4 个文件） |
| 2026-04-05 | 新增登录与授权模块文档（2 个文件） |
| 2026-04-05 | 新增个人中心与帮助中心模块文档（6 个文件），完成业务模块文档收尾 |
| 2026-04-05 | 新增共享组件文档第一批（8 个文件） |
| 2026-04-05 | 新增 Store 模块文档（5 个文件） |
| 2026-04-05 | 新增 Utils 文档（11 个文件） |
| 2026-04-05 | 新增开发指南文档（5 个文件），完成 `guides` 目录落地 |
| 2026-04-05 | 补全剩余共享组件文档（17 个文件），完成 `components` 目录落地 |
| 2026-04-05 | 完成一轮全文档一致性复查，补齐 `DOC-STRUCTURE.md` 目录树并确认链接有效 |
| 2026-04-05 | 新增文档维护指南，沉淀代码变更与文档更新映射及模板规范 |
