<template>
  <div class="config-wrapper">
    <h5>{{ $t('modules.views.deployInstall.apm.s_00cc821b') }}</h5>
    <code-view code="pecl install opentelemetry
pecl install grpc" />
    <p>{{ $t('modules.views.deployInstall.apm.s_6d435582') }}</p>
    <code-view code="extension=opentelemetry.so
extension=grpc.so" />

    <h5>{{ $t('modules.views.deployInstall.apm.s_3bc1cba2') }}</h5>
    <code-view code="composer require open-telemetry/sdk open-telemetry/exporter-otlp open-telemetry/opentelemetry-auto-slim php-http/guzzle7-adapter open-telemetry/transport-grpc" />

    <h5>{{ $t('modules.views.deployInstall.apm.s_9f39a4e6') }}</h5>
    <p>{{ $t('modules.views.deployInstall.apm.s_5852ffc5') }}</p>
    <code-view :code="startScript" />

    <h5>{{ $t('modules.views.deployInstall.apm.s_fbf960f3') }}</h5>
    <ul>
      <li>{{ $t('modules.views.deployInstall.apm.s_6a46ad97') }}</li>
      <li>{{ $t('modules.views.deployInstall.apm.s_69c358c6') }}</li>
    </ul>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop } from 'vue-property-decorator';
import CodeView from '@/components/code-view.vue';

@Component({
  components: { CodeView },
})
export default class HostPhp extends Vue {
  @Prop({ default: '' }) private ingestGrpcEndpoint!: string;

  get resolvedIngestGrpcEndpoint () {
    if (this.ingestGrpcEndpoint) {
      return this.ingestGrpcEndpoint;
    }
    const host = window.location.hostname || '127.0.0.1';
    return `http://${host}:4317`;
  }

  get startScript () {
    return `export OTEL_PHP_AUTOLOAD_ENABLED=true
export OTEL_SERVICE_NAME=my-php-service
export OTEL_TRACES_EXPORTER=otlp
export OTEL_METRICS_EXPORTER=none
export OTEL_LOGS_EXPORTER=otlp
export OTEL_EXPORTER_OTLP_PROTOCOL=grpc
export OTEL_EXPORTER_OTLP_ENDPOINT=${this.resolvedIngestGrpcEndpoint}
export OTEL_PROPAGATORS=tracecontext,baggage

php -S 0.0.0.0:8080 -t public`;
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
}
</style>
