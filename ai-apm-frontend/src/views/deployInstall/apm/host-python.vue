<template>
  <div class="config-wrapper">
    <h5>{{ $t('modules.views.configInstall.apm.s_c9331ae9') }}</h5>
    <code-view code="pip install opentelemetry-distro opentelemetry-exporter-otlp
opentelemetry-bootstrap -a install" />

    <h5>{{ $t('modules.views.deployInstall.apm.s_77f6ca83') }}</h5>
    <p>{{ $t('modules.views.configInstall.apm.s_ecff77a8') }} <code-view code="opentelemetry-instrument" :showCopy="false" type="inline" /> {{ $t('modules.views.deployInstall.apm.s_d7574769') }}</p>
    <code-view :code="startScript" />

    <h5>{{ $t('modules.views.deployInstall.apm.s_93459283') }}</h5>
    <code-view :code="flaskApp" />

    <h5>{{ $t('modules.views.deployInstall.apm.s_fbf960f3') }}</h5>
    <ul>
      <li>{{ $t('modules.views.deployInstall.apm.s_1a6aa24e') }} <code-view code="curl http://localhost:8070/hello" :showCopy="false" type="inline" /> {{ $t('modules.views.deployInstall.apm.s_ec048ab7') }}</li>
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
export default class HostPython extends Vue {
  @Prop({ default: '' }) private ingestHttpEndpoint!: string;

  get resolvedIngestHttpEndpoint () {
    if (this.ingestHttpEndpoint) {
      return this.ingestHttpEndpoint;
    }
    const host = window.location.hostname || '127.0.0.1';
    return `http://${host}:4318`;
  }

  get startScript () {
    return `export OTEL_SERVICE_NAME=my-python-service
export OTEL_TRACES_EXPORTER=otlp
export OTEL_METRICS_EXPORTER=none
export OTEL_LOGS_EXPORTER=otlp
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
export OTEL_EXPORTER_OTLP_ENDPOINT=${this.resolvedIngestHttpEndpoint}
export OTEL_PROPAGATORS=tracecontext,baggage

opentelemetry-instrument python app.py`;
  }

  private flaskApp = `from flask import Flask
import requests

app = Flask(__name__)

@app.route("/hello")
def hello():
    requests.get("https://example.com/")
    return "ok"

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8070)`
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
