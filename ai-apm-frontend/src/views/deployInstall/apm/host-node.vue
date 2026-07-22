<template>
  <div class="config-wrapper">
    <h5>{{ $t('modules.views.configInstall.apm.s_c9331ae9') }}</h5>
    <code-view code="npm init -y
npm install express axios
npm install --save @opentelemetry/api @opentelemetry/auto-instrumentations-node" />

    <h5>{{ $t('modules.views.deployInstall.apm.s_c1752ac1') }}</h5>
    <p>{{ $t('modules.views.deployInstall.apm.s_23c1f399') }} <code-view code="NODE_OPTIONS" :showCopy="false" type="inline" /> {{ $t('modules.views.deployInstall.apm.s_277c2ccb') }}</p>
    <code-view :code="startScript" />

    <h5>{{ $t('modules.views.deployInstall.apm.s_792d3044') }}</h5>
    <code-view :code="expressApp" />

    <h5>{{ $t('modules.views.deployInstall.apm.s_fbf960f3') }}</h5>
    <ul>
      <li>{{ $t('modules.views.deployInstall.apm.s_d5c0ebde') }} <code-view code="http://localhost:7001/" :showCopy="false" type="inline" /> {{ $t('modules.views.deployInstall.apm.s_c6df4586') }}</li>
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
export default class HostNode extends Vue {
  @Prop({ default: '' }) private ingestHttpEndpoint!: string;

  get resolvedIngestHttpEndpoint () {
    if (this.ingestHttpEndpoint) {
      return this.ingestHttpEndpoint;
    }
    const host = window.location.hostname || '127.0.0.1';
    return `http://${host}:4318`;
  }

  get startScript () {
    return `export OTEL_SERVICE_NAME=my-node-service
export OTEL_TRACES_EXPORTER=otlp
export OTEL_METRICS_EXPORTER=none
export OTEL_LOGS_EXPORTER=otlp
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
export OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=${this.resolvedIngestHttpEndpoint}/v1/traces
export OTEL_EXPORTER_OTLP_LOGS_ENDPOINT=${this.resolvedIngestHttpEndpoint}/v1/logs
export OTEL_NODE_RESOURCE_DETECTORS=env,host,os
export OTEL_PROPAGATORS=tracecontext,baggage
export NODE_OPTIONS="--require @opentelemetry/auto-instrumentations-node/register"

node main.js`;
  }

  private expressApp = `"use strict";
const axios = require("axios").default;
const express = require("express");
const app = express();

app.get("/", async (req, res) => {
  const result = await axios.get("http://localhost:7001/hello");
  return res.status(200).send(result.data);
});

app.get("/hello", (req, res) => {
  res.json({ code: 200, msg: "success" });
});

app.listen(7001, () => {
  console.log("Listening on http://localhost:7001");
});`
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
