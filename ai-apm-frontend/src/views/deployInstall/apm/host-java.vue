<template>
  <div class="config-wrapper">
    <h5>{{ $t('modules.views.deployInstall.apm.s_27284e78') }}</h5>
    <p>{{ $t('modules.views.deployInstall.apm.s_47d31853') }}<a href="https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar" target="_blank" class="blue cp dib cphu">opentelemetry-javaagent.jar</a>{{ $t('modules.views.deployInstall.apm.s_b750c514') }}</p>

    <h5>{{ $t('modules.views.deployInstall.apm.s_ee768779') }}</h5>
    <p>{{ $t('modules.views.deployInstall.apm.s_a62c91f4') }} <code-view code="-javaagent" :showCopy="false" type="inline" /> {{ $t('modules.views.deployInstall.apm.s_5675239e') }}</p>
    <code-view :code="startScript" />

    <h5>{{ $t('modules.views.deployInstall.apm.s_50dc4349') }}</h5>
    <ul>
      <li>{{ $t('modules.views.deployInstall.apm.s_6a46ad97') }}</li>
      <li>{{ $t('modules.views.deployInstall.apm.s_b9c675d3') }}</li>
      <li>{{ $t('modules.views.deployInstall.apm.s_0b4630d0') }}</li>
    </ul>

    <h5>{{ $t('modules.views.deployInstall.apm.s_5e1d6dd4') }}</h5>
    <marked-view :data="envTable" />
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop } from 'vue-property-decorator';
import CodeView from '@/components/code-view.vue';
import MarkedView from '@/components/marked-view.vue';

@Component({
  components: { CodeView, MarkedView },
})
export default class HostJava extends Vue {
  @Prop({ default: '' }) private ingestGrpcEndpoint!: string;

  get resolvedIngestGrpcEndpoint () {
    if (this.ingestGrpcEndpoint) {
      return this.ingestGrpcEndpoint;
    }
    const host = window.location.hostname || '127.0.0.1';
    return `http://${host}:4317`;
  }

  get startScript () {
    return `export OTEL_SERVICE_NAME=my-java-service
export OTEL_TRACES_EXPORTER=otlp
export OTEL_METRICS_EXPORTER=otlp
export OTEL_LOGS_EXPORTER=otlp
export OTEL_EXPORTER_OTLP_PROTOCOL=grpc
export OTEL_EXPORTER_OTLP_ENDPOINT=${this.resolvedIngestGrpcEndpoint}
export OTEL_PROPAGATORS=tracecontext,baggage
export OTEL_RESOURCE_ATTRIBUTES=deployment.environment=prod

java -javaagent:/path/to/opentelemetry-javaagent.jar \\
  -jar your-application.jar`;
  }

  private envTable = `
| 变量 | 说明 |
| --- | --- |
| \`OTEL_SERVICE_NAME\` | 服务名，在平台中展示 |
| \`OTEL_EXPORTER_OTLP_ENDPOINT\` | Ingest OTLP 地址（gRPC 用 4317，HTTP 用 4318） |
| \`OTEL_EXPORTER_OTLP_PROTOCOL\` | \`grpc\` 或 \`http/protobuf\` |
| \`OTEL_TRACES_EXPORTER\` | 设为 \`otlp\` 启用 Trace 上报 |
| \`OTEL_LOGS_EXPORTER\` | 设为 \`otlp\` 启用 Log 上报 |
| \`OTEL_JAVAAGENT_DEBUG\` | 设为 \`true\` 可开启 Agent 调试日志 |
`
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
}
</style>
