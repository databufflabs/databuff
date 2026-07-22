<template>
  <div class="config-wrapper">
    <h5>{{ $t('modules.views.configInstall.apm.s_c9331ae9') }}</h5>
    <code-view code="gem install opentelemetry-sdk opentelemetry-exporter-otlp opentelemetry-instrumentation-all" />
    <p>{{ $t('modules.views.deployInstall.apm.s_a0a6ba89') }}</p>
    <code-view code="gem 'opentelemetry-sdk'
gem 'opentelemetry-exporter-otlp'
gem 'opentelemetry-instrumentation-all'" />

    <h5>{{ $t('modules.views.deployInstall.apm.s_ee768779') }}</h5>
    <p>{{ $t('modules.views.deployInstall.apm.s_ed883236') }}</p>
    <code-view :code="startScript" />
    <code-view :code="initCode" />

    <h5>{{ $t('modules.views.deployInstall.apm.s_50dc4349') }}</h5>
    <ul>
      <li>{{ $t('modules.views.deployInstall.apm.s_6a46ad97') }}</li>
      <li>{{ $t('modules.views.deployInstall.apm.s_e42fab4b') }}</li>
    </ul>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop } from 'vue-property-decorator';
import CodeView from '@/components/code-view.vue';

@Component({
  components: { CodeView },
})
export default class HostRuby extends Vue {
  @Prop({ default: '' }) private ingestHttpEndpoint!: string;

  get resolvedIngestHttpEndpoint () {
    if (this.ingestHttpEndpoint) {
      return this.ingestHttpEndpoint;
    }
    const host = window.location.hostname || '127.0.0.1';
    return `http://${host}:4318`;
  }

  get startScript () {
    return `export OTEL_SERVICE_NAME=my-ruby-service
export OTEL_TRACES_EXPORTER=otlp
export OTEL_METRICS_EXPORTER=none
export OTEL_LOGS_EXPORTER=otlp
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
export OTEL_EXPORTER_OTLP_ENDPOINT=${this.resolvedIngestHttpEndpoint}
export OTEL_PROPAGATORS=tracecontext,baggage

bundle exec rails server`;
  }

  private initCode = `# config/initializers/opentelemetry.rb
require 'opentelemetry/sdk'
require 'opentelemetry/exporter/otlp'
require 'opentelemetry/instrumentation/all'

OpenTelemetry::SDK.configure do |c|
  c.use_all
end`
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
