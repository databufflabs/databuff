<template>
  <div class="config-wrapper">
    <h5>{{ $t('modules.views.deployInstall.apm.s_ebb87f05') }}</h5>
    <p>{{ $t('modules.views.deployInstall.apm.s_0551858f') }}</p>
    <code-view code="curl -L -O https://github.com/open-telemetry/opentelemetry-dotnet-instrumentation/releases/latest/download/otel-dotnet-auto-install.sh
chmod +x otel-dotnet-auto-install.sh
./otel-dotnet-auto-install.sh" />

    <h5>{{ $t('modules.views.deployInstall.apm.s_ee768779') }}</h5>
    <p>{{ $t('modules.views.deployInstall.apm.s_b811c2cb') }}</p>
    <code-view :code="startScript" />

    <h5>{{ $t('modules.views.deployInstall.apm.s_50dc4349') }}</h5>
    <ul>
      <li>{{ $t('modules.views.deployInstall.apm.s_6a46ad97') }}</li>
      <li>{{ $t('modules.views.deployInstall.apm.s_e42fab4b') }}</li>
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
export default class HostNet extends Vue {
  @Prop({ default: '' }) private ingestHttpEndpoint!: string;

  get resolvedIngestHttpEndpoint () {
    if (this.ingestHttpEndpoint) {
      return this.ingestHttpEndpoint;
    }
    const host = window.location.hostname || '127.0.0.1';
    return `http://${host}:4318`;
  }

  get startScript () {
    return `export OTEL_SERVICE_NAME=my-net-service
export OTEL_TRACES_EXPORTER=otlp
export OTEL_METRICS_EXPORTER=none
export OTEL_LOGS_EXPORTER=otlp
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
export OTEL_EXPORTER_OTLP_ENDPOINT=${this.resolvedIngestHttpEndpoint}
export OTEL_PROPAGATORS=tracecontext,baggage

. $HOME/.otel-dotnet-auto/instrument.sh
dotnet run`;
  }

  private envTable = `
| 变量 | 说明 |
| --- | --- |
| \`OTEL_SERVICE_NAME\` | 服务名，在平台中展示 |
| \`OTEL_EXPORTER_OTLP_ENDPOINT\` | Ingest OTLP HTTP 地址（4318） |
| \`OTEL_EXPORTER_OTLP_PROTOCOL\` | 设为 \`http/protobuf\` |
| \`OTEL_TRACES_EXPORTER\` | 设为 \`otlp\` 启用 Trace 上报 |
| \`OTEL_LOGS_EXPORTER\` | 设为 \`otlp\` 启用 Log 上报 |
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
