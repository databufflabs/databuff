# 日志采集

> 页面: `/deploy/access?type=log`
> 文件: `src/views/deployInstall/log/index.vue`

## 页面职责

日志页提供 OpenTelemetry 日志采集与日志关联 Trace 的静态配置说明，偏「配置手册」而不是交互页面。

## 注意事项

- 这是静态说明页，没有直接调用后端接口
- 日志关联 Trace 的前置条件是应用已开启 Trace 与日志采集
