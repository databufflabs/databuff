<template>
  <div class="config-wrapper">
    <p>
      {{ $t('modules.views.deployInstall.otelCollector.s_c69c12e7') }} <code-view code="otlp" :showCopy="false" type="inline" /> {{ $t('modules.views.deployInstall.otelCollector.s_621261f6') }}
    </p>

    <h5>{{ $t('modules.views.deployInstall.otelCollector.s_7b237ce1') }}</h5>
    <p>{{ $t('modules.views.deployInstall.otelCollector.s_74b2557a') }}</p>
    <marked-view :data="endpointTableText" />

    <h5>{{ $t('modules.views.deployInstall.otelCollector.s_8958beb6') }}</h5>
    <p>{{ $t('modules.views.deployInstall.otelCollector.s_a80c60da') }} <code-view code="exporters" :showCopy="false" type="inline" /> {{ $t('modules.views.deployInstall.otelCollector.s_204948ff') }} <code-view code="service.pipelines" :showCopy="false" type="inline" /> {{ $t('modules.views.deployInstall.otelCollector.s_ad54972f') }}</p>
    <p><span class="fw-500">{{ $t('modules.views.deployInstall.otelCollector.s_65eec16f') }}</span></p>
    <code-view :code="collectorConfigGrpc" />
    <p><span class="fw-500">{{ $t('modules.views.deployInstall.otelCollector.s_0d4e8fc2') }}</span></p>
    <code-view :code="collectorConfigHttp" />
    <p>{{ $t('modules.views.deployInstall.otelCollector.s_a726375e') }} <code-view code="OTEL_EXPORTER_OTLP_ENDPOINT" :showCopy="false" type="inline" /> {{ $t('modules.views.deployInstall.otelCollector.s_4a635926') }}</p>
  </div>
</template>

<script lang="ts">
import { Vue, Component } from 'vue-property-decorator';
import CodeView from '@/components/code-view.vue';
import MarkedView from '@/components/marked-view.vue';

@Component({
  components: { CodeView, MarkedView },
})
export default class DeployOtelCollector extends Vue {
  get ingestHost () {
    return window.location.hostname || '127.0.0.1';
  }

  get ingestHttpEndpoint () {
    return `http://${this.ingestHost}:4318`;
  }

  get ingestGrpcEndpoint () {
    return `${this.ingestHost}:4317`;
  }

  get endpointTableText () {
    return `
| 协议 | 端口 | 地址 |
| --- | --- | --- |
| OTLP gRPC | 4317 | \`${this.ingestGrpcEndpoint}\` |
| OTLP HTTP | 4318 | \`${this.ingestHttpEndpoint}\` |
`;
  }

  get collectorConfigGrpc () {
    return `exporters:
  otlp/databuff:
    endpoint: ${this.ingestGrpcEndpoint}
    tls:
      insecure: true

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch]
      exporters: [otlp/databuff]
    metrics:
      receivers: [otlp]
      processors: [batch]
      exporters: [otlp/databuff]
    logs:
      receivers: [otlp]
      processors: [batch]
      exporters: [otlp/databuff]`;
  }

  get collectorConfigHttp () {
    return `exporters:
  otlphttp/databuff:
    endpoint: ${this.ingestHttpEndpoint}
    tls:
      insecure: true

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch]
      exporters: [otlphttp/databuff]
    metrics:
      receivers: [otlp]
      processors: [batch]
      exporters: [otlphttp/databuff]
    logs:
      receivers: [otlp]
      processors: [batch]
      exporters: [otlphttp/databuff]`;
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

  p {
    margin: 5px 0;
  }

  :deep(.code-view-wrapper.block) {
    margin-bottom: 10px;
  }
}
</style>
