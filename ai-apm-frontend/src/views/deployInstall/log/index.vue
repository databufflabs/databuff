<template>
  <div class="config-wrapper">
    <p>
      DataBuff Ingest 已支持 OTLP Logs（gRPC <code-view code="4317" :showCopy="false" type="inline" /> / HTTP <code-view code="4318" :showCopy="false" type="inline" />）。
      日志写入 Doris 后可在 Trace 详情侧栏按 <code-view code="trace_id" :showCopy="false" type="inline" /> / <code-view code="span_id" :showCopy="false" type="inline" /> 关联查看。
    </p>

    <h5>OTLP 端点</h5>
    <marked-view :data="endpointTableText" />

    <h5>Java Agent / SDK</h5>
    <p>在已启用 Trace 的基础上增加日志导出：</p>
    <code-view :code="javaAgentEnv" />

    <h5>OTel Collector（logs pipeline）</h5>
    <code-view :code="collectorConfig" />

    <p class="describe mt-12">
      关联 Trace 时，请确保 LogRecord 携带与 Span 一致的 trace/span 上下文（OTel Log Bridge 或 MDC 注入 <code-view code="trace_id" :showCopy="false" type="inline" />）。
    </p>
  </div>
</template>

<script lang="ts">
import { Vue, Component } from 'vue-property-decorator';
import CodeView from '@/components/code-view.vue';
import MarkedView from '@/components/marked-view.vue';

@Component({
  components: { CodeView, MarkedView },
})
export default class DeployLog extends Vue {
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
| 协议 | 路径 / 端口 | 地址 |
| --- | --- | --- |
| OTLP gRPC Logs | 4317 | \`${this.ingestGrpcEndpoint}\` |
| OTLP HTTP Logs | /v1/logs @ 4318 | \`${this.ingestHttpEndpoint}/v1/logs\` |
`;
  }

  get javaAgentEnv () {
    return `export OTEL_LOGS_EXPORTER=otlp
export OTEL_EXPORTER_OTLP_ENDPOINT=http://${this.ingestHost}:4318
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`;
  }

  get collectorConfig () {
    return `exporters:
  otlphttp/databuff_logs:
    endpoint: ${this.ingestHttpEndpoint}
    tls:
      insecure: true

service:
  pipelines:
    logs:
      receivers: [otlp]
      processors: [batch]
      exporters: [otlphttp/databuff_logs]`;
  }
}
</script>

<style lang="scss" scoped>
.config-wrapper {
  padding: 8px 16px 24px;
  line-height: 1.7;
  h5 {
    margin: 20px 0 8px;
    font-size: 14px;
  }
}
</style>
