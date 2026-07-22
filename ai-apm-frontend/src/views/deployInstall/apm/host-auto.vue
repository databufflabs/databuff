<template>
  <div class="config-wrapper">
    <p class="ref-notice">{{ $t('modules.views.deployInstall.apm.s_89ce7c99') }} <a href="https://github.com/open-telemetry/opentelemetry-injector" target="_blank" class="blue cp dib cphu">opentelemetry-injector</a> {{ $t('modules.views.deployInstall.apm.s_bcf59e95') }}<span class="fw-500">{{ $t('modules.views.deployInstall.apm.s_163b3012') }}</span>{{ $t('modules.views.deployInstall.apm.s_d4bec86a') }}</p>
    <p>
      <a href="https://github.com/open-telemetry/opentelemetry-injector" target="_blank" class="blue cp dib cphu">OpenTelemetry Injector</a>
      {{ $t('modules.views.deployInstall.apm.s_23c1f399') }} <code-view code="LD_PRELOAD" :showCopy="false" type="inline" /> {{ $t('modules.components.matching-criteria.s_a7185263') }}
      <code-view code="/etc/ld.so.preload" :showCopy="false" type="inline" />
      {{ $t('modules.views.deployInstall.apm.s_137b3918') }}
    </p>
    <p>{{ $t('modules.views.deployInstall.apm.s_5718d29e') }}</p>

    <h5>{{ $t('modules.views.deployInstall.apm.s_0a668632') }}</h5>
    <p>{{ $t('modules.views.deployInstall.apm.s_0cc05f42') }} <a href="https://github.com/open-telemetry/opentelemetry-injector/releases" target="_blank" class="blue cp dib cphu">GitHub Releases</a> {{ $t('modules.views.deployInstall.apm.s_97ac8711') }}</p>
    <code-view :code="installScript" />

    <h5>{{ $t('modules.views.deployInstall.apm.s_5651c303') }}</h5>
    <p>{{ $t('modules.views.configInstall.dataAccess.s_95b351c8') }} <code-view code="/etc/opentelemetry/injector/default_env.conf" :showCopy="false" type="inline" />{{ $t('modules.views.deployInstall.apm.s_577ac4e1') }} <code-view code="OTEL_" :showCopy="false" type="inline" /> {{ $t('modules.views.deployInstall.apm.s_db36b985') }}</p>
    <code-view :code="defaultEnvConf" />
    <p>{{ $t('modules.views.deployInstall.apm.s_e32a66bb') }}</p>

    <h5>{{ $t('modules.views.deployInstall.apm.s_164d90aa') }}</h5>
    <p><span class="fw-500">{{ $t('modules.views.deployInstall.apm.s_083da0f9') }}</span> {{ $t('modules.views.deployInstall.apm.s_aaf2754a') }}</p>
    <code-view code="echo /usr/lib/opentelemetry/libotelinject.so >> /etc/ld.so.preload" />
    <p><span class="fw-500">{{ $t('modules.views.deployInstall.apm.s_1a43617e') }}</span> {{ $t('modules.views.deployInstall.apm.s_85ac5888') }}</p>
    <code-view :code="ldPreloadExample" />

    <h5>{{ $t('modules.views.deployInstall.apm.s_cbc6b9b1') }}</h5>
    <p>{{ $t('modules.views.deployInstall.apm.s_f29f0069') }} <code-view code="/etc/opentelemetry/injector/injector.conf" :showCopy="false" type="inline" /> {{ $t('modules.views.deployInstall.apm.s_ef764db7') }} <code-view code="OTEL_INJECTOR_CONFIG_FILE" :showCopy="false" type="inline" /> {{ $t('modules.views.deployInstall.apm.s_e8908a02') }}</p>
    <code-view :code="injectorConf" />
    <marked-view :data="agentPathTable" />

    <h5>{{ $t('modules.views.deployInstall.apm.s_d6d21c3b') }}</h5>
    <p>{{ $t('modules.views.deployInstall.apm.s_edf9a5ff') }} <code-view code="injector.conf" :showCopy="false" type="inline" /> {{ $t('modules.views.deployInstall.apm.s_f4ccd2a6') }}</p>
    <marked-view :data="filterEnvTable" />
    <p>{{ $t('modules.views.deployInstall.apm.s_85c3b3ab') }} <code-view code="/app/*" :showCopy="false" type="inline" /> {{ $t('modules.views.deployInstall.apm.s_139b79be') }} <code-view code="*.jar" :showCopy="false" type="inline" /> {{ $t('modules.views.deployInstall.apm.s_91a90078') }} <code-view code="/app/system/*" :showCopy="false" type="inline" />：</p>
    <code-view :code="filterConfExample" />

    <h5>{{ $t('modules.views.deployInstall.apm.s_36c14154') }}</h5>
    <marked-view :data="disableTable" />

    <h5>{{ $t('modules.views.deployInstall.apm.s_95d897d8') }}</h5>
    <ul>
      <li>{{ $t('modules.views.deployInstall.apm.s_e83a256e') }} <code-view code="/usr/lib/opentelemetry/libotelinject.so" :showCopy="false" type="inline" /> {{ $t('modules.views.deployInstall.apm.s_124723a1') }} <code-view code="/etc/ld.so.preload" :showCopy="false" type="inline" /> {{ $t('modules.views.deployInstall.apm.s_81685948') }} <code-view code="LD_PRELOAD" :showCopy="false" type="inline" /> {{ $t('modules.views.deployInstall.apm.s_f775b006') }}</li>
      <li>{{ $t('modules.views.deployInstall.apm.s_81bf2ffe') }}</li>
      <li>{{ $t('modules.views.deployInstall.apm.s_948c8c9e') }}</li>
      <li>{{ $t('modules.views.deployInstall.apm.s_484e2314') }} <code-view code="OTEL_INJECTOR_LOG_LEVEL=debug" :showCopy="false" type="inline" />{{ $t('modules.views.deployInstall.apm.s_0c8f1ebb') }}</li>
    </ul>

    <h5>{{ $t('modules.views.deployInstall.apm.s_3d9c8eb1') }}</h5>
    <marked-view :data="faqTable" />
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop } from 'vue-property-decorator';
import CodeView from '@/components/code-view.vue';
import MarkedView from '@/components/marked-view.vue';

@Component({
  components: { CodeView, MarkedView },
})
export default class HostAuto extends Vue {
  @Prop({ default: '' }) private ingestHost!: string;
  @Prop({ default: '' }) private ingestHttpEndpoint!: string;
  @Prop({ default: '' }) private ingestGrpcEndpoint!: string;

  get resolvedIngestHost () {
    return this.ingestHost || window.location.hostname || '127.0.0.1';
  }

  get resolvedIngestGrpcEndpoint () {
    if (this.ingestGrpcEndpoint) {
      return this.ingestGrpcEndpoint;
    }
    return `http://${this.resolvedIngestHost}:4317`;
  }

  get resolvedIngestHttpEndpoint () {
    if (this.ingestHttpEndpoint) {
      return this.ingestHttpEndpoint;
    }
    return `http://${this.resolvedIngestHost}:4318`;
  }

  get installScript () {
    return `# 前往 Releases 页下载对应架构的安装包：
# https://github.com/open-telemetry/opentelemetry-injector/releases

# Debian / Ubuntu (amd64)
wget https://github.com/open-telemetry/opentelemetry-injector/releases/download/v0.9.0/opentelemetry-injector_0.9.0_amd64.deb
sudo dpkg -i opentelemetry-injector_0.9.0_amd64.deb

# RHEL / CentOS / Rocky Linux (x86_64)
wget https://github.com/open-telemetry/opentelemetry-injector/releases/download/v0.9.0/opentelemetry-injector-0.9.0-1.x86_64.rpm
sudo rpm -i opentelemetry-injector-0.9.0-1.x86_64.rpm`;
  }

  get defaultEnvConf () {
    return `OTEL_SERVICE_NAME=my-service
OTEL_TRACES_EXPORTER=otlp
OTEL_METRICS_EXPORTER=otlp
OTEL_LOGS_EXPORTER=otlp
OTEL_EXPORTER_OTLP_PROTOCOL=grpc
OTEL_EXPORTER_OTLP_ENDPOINT=${this.resolvedIngestGrpcEndpoint}
OTEL_PROPAGATORS=tracecontext,baggage
OTEL_RESOURCE_ATTRIBUTES=deployment.environment=prod`;
  }

  get ldPreloadExample () {
    return `LD_PRELOAD=/usr/lib/opentelemetry/libotelinject.so java -jar myapp.jar
LD_PRELOAD=/usr/lib/opentelemetry/libotelinject.so node app.js
LD_PRELOAD=/usr/lib/opentelemetry/libotelinject.so dotnet MyApp.dll`;
  }

  private injectorConf = `dotnet_auto_instrumentation_agent_path_prefix=/usr/lib/opentelemetry/dotnet
jvm_auto_instrumentation_agent_path=/usr/lib/opentelemetry/jvm/javaagent.jar
nodejs_auto_instrumentation_agent_path=/usr/lib/opentelemetry/nodejs/node_modules/@opentelemetry/auto-instrumentations-node/build/src/register.js`

  private agentPathTable = `
| 运行时 | 注入方式 | 包内默认路径 |
| --- | --- | --- |
| Java | 追加 \`-javaagent\` 至 \`JAVA_TOOL_OPTIONS\` | \`/usr/lib/opentelemetry/jvm/javaagent.jar\` |
| Node.js | 追加 \`--require\` 至 \`NODE_OPTIONS\` | \`.../auto-instrumentations-node/.../register.js\` |
| .NET | 设置 \`CORECLR_PROFILER\` 等环境变量 | \`/usr/lib/opentelemetry/dotnet\` |
| Python | 追加路径至 \`PYTHONPATH\`（默认关闭） | 需自行配置 \`python_auto_instrumentation_agent_path_prefix\` |
`

  private filterEnvTable = `
| 配置项 / 环境变量 | 说明 |
| --- | --- |
| \`include_paths\` / \`OTEL_INJECTOR_INCLUDE_PATHS\` | 可执行文件路径 glob，逗号分隔（OR） |
| \`exclude_paths\` / \`OTEL_INJECTOR_EXCLUDE_PATHS\` | 排除路径 glob |
| \`include_with_arguments\` / \`OTEL_INJECTOR_INCLUDE_WITH_ARGUMENTS\` | 匹配命令行参数 glob |
| \`exclude_with_arguments\` / \`OTEL_INJECTOR_EXCLUDE_WITH_ARGUMENTS\` | 排除命令行参数 glob |
`

  private filterConfExample = `include_paths=/app/*
exclude_paths=/app/system/*
include_with_arguments=*.jar`

  private disableTable = `
| 场景 | 配置 |
| --- | --- |
| 禁用特定运行时 | \`auto_instrumentation_disabled=dotnet,python\` 或 \`OTEL_INJECTOR_AUTO_INSTRUMENTATION_DISABLED=dotnet,python\` |
| 禁用全部自动埋点 | \`auto_instrumentation_disabled=*\` |
| 完全跳过 Injector | 启动前设置 \`OTEL_INJECTOR_DISABLED=true\` |
| 启用 Python 自动埋点 | 在 \`injector.conf\` 中配置 \`python_auto_instrumentation_agent_path_prefix\`，并确保路径下含 \`glibc\` / \`musl\` 子目录 |
`

  get faqTable () {
    return `
| 现象 | 处理建议 |
| --- | --- |
| 无 Trace 数据 | 确认 \`default_env.conf\` 中 \`OTEL_EXPORTER_OTLP_ENDPOINT\` 为 \`${this.resolvedIngestGrpcEndpoint}\`（gRPC）或 HTTP 端点 \`${this.resolvedIngestHttpEndpoint}\`；确认 Ingest 4317/4318 端口从主机可达 |
| .NET 未注入 | Injector 会检查 \`*.deps.json\`，若已引用 \`OpenTelemetry*\` 包则跳过；可设置 debug 日志排查 |
| 误注入系统进程 | 使用 \`include_paths\` / \`exclude_paths\` 缩小范围，或对单进程使用 \`LD_PRELOAD\` 而非全局 \`/etc/ld.so.preload\` |
| Python 未生效 | Python 默认关闭，需手动配置 Agent 路径；注意依赖冲突风险 |
| 仅需 HTTP 协议 | 在 \`default_env.conf\` 中设置 \`OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf\` 及 \`OTEL_EXPORTER_OTLP_ENDPOINT=${this.resolvedIngestHttpEndpoint}\` |
`;
  }
}
</script>

<style lang="scss" scoped>
.config-wrapper {
  font-size: 13px;
  line-height: 24px;

  h5 {
    margin: 0 0 10px;
    font-size: 14px;
    font-weight: 500;
    color: var(--color-text-primary);
  }

  * + h5 {
    margin-top: 20px;
  }

  p, ul {
    margin: 5px 0;
  }

  ul {
    padding-left: 20px;
  }

  .ref-notice {
    margin: 0 0 10px;
    padding: 8px 12px;
    background: var(--bg-color02);
    border-left: 3px solid var(--color-warning, #e6a23c);
    color: var(--color-text-secondary);
    line-height: 22px;
  }
}
</style>
