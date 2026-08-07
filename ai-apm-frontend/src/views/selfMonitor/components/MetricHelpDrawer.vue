<template>
  <el-drawer
    :visible.sync="visibleProxy"
    direction="rtl"
    size="560px"
    :with-header="true"
    :title="doc ? doc.title : '指标说明'"
    custom-class="sm-help-drawer"
    append-to-body
    destroy-on-close
  >
    <div v-if="doc" class="sm-help">
      <section v-if="doc.metrics && doc.metrics.length" class="sm-help-block">
        <h4 class="sm-help-h">相关指标</h4>
        <ul class="sm-help-metrics">
          <li v-for="m in doc.metrics" :key="m"><code>{{ m }}</code></li>
        </ul>
      </section>

      <section class="sm-help-block">
        <h4 class="sm-help-h">统计逻辑</h4>
        <p v-for="(line, i) in doc.logic" :key="'l' + i" class="sm-help-p">{{ line }}</p>
      </section>

      <section v-if="doc.tips && doc.tips.length" class="sm-help-block">
        <h4 class="sm-help-h">怎么看</h4>
        <ul class="sm-help-list">
          <li v-for="(t, i) in doc.tips" :key="'t' + i">{{ t }}</li>
        </ul>
      </section>

      <section v-if="doc.params && doc.params.length" class="sm-help-block">
        <h4 class="sm-help-h">相关环境变量</h4>
        <div v-for="(p, i) in doc.params" :key="'p' + i" class="sm-help-param">
          <div class="sm-help-param-name"><code>{{ p.name }}</code></div>
          <div v-if="p.defaultValue" class="sm-help-param-def">默认 {{ p.defaultValue }}</div>
          <p class="sm-help-p">{{ p.meaning }}</p>
        </div>
      </section>

      <p class="sm-help-foot">修改环境变量后需重启对应组件（ingest / web / Doris）生效。</p>
    </div>
  </el-drawer>
</template>

<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import { MetricHelpDoc } from '../metricHelp';

@Component
export default class MetricHelpDrawer extends Vue {
  @Prop({ default: false }) public visible!: boolean;
  @Prop({ default: null }) public doc!: MetricHelpDoc | null;

  private visibleProxy = false;

  @Watch('visible', { immediate: true })
  private onVisible(v: boolean) {
    this.visibleProxy = v;
  }

  @Watch('visibleProxy')
  private onProxy(v: boolean) {
    if (v !== this.visible) {
      this.$emit('update:visible', v);
    }
  }
}
</script>

<style lang="scss" scoped>
.sm-help {
  padding: 4px 4px 24px;
  color: #334155;
  font-size: 13px;
  line-height: 1.55;
}

.sm-help-block {
  margin-bottom: 20px;
}

.sm-help-h {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 650;
  color: #0f172a;
}

.sm-help-p {
  margin: 0 0 8px;
  color: #475569;
}

.sm-help-metrics {
  margin: 0;
  padding: 0;
  list-style: none;

  li {
    margin-bottom: 6px;
  }

  code {
    font-size: 12px;
    background: #f1f5f9;
    color: #0f766e;
    padding: 2px 6px;
    border-radius: 4px;
    word-break: break-all;
  }
}

.sm-help-list {
  margin: 0;
  padding-left: 18px;
  color: #475569;

  li + li {
    margin-top: 6px;
  }
}

.sm-help-param {
  padding: 10px 12px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  margin-bottom: 8px;
}

.sm-help-param-name {
  code {
    font-size: 12px;
    font-weight: 600;
    color: #1e293b;
    word-break: break-all;
  }
}

.sm-help-param-def {
  margin: 4px 0 6px;
  font-size: 12px;
  color: #94a3b8;
}

.sm-help-foot {
  margin: 8px 0 0;
  font-size: 12px;
  color: #94a3b8;
}
</style>

<style lang="scss">
.sm-help-drawer {
  .el-drawer__header {
    margin-bottom: 12px;
    padding: 16px 20px 0;
    font-weight: 650;
    color: #0f172a;
  }

  .el-drawer__body {
    padding: 0 20px 20px;
  }
}
</style>
