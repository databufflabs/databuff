<template>
  <div class="deploy-status-wrapper">
    <div class="deploy-status flex-v">
      <div class="deploy-status-header">
        <db-tabnav
          v-model="activeName"
          :tabnavs="tabs"
          @on-change="toggleTabHandle" />
      </div>

      <component :is="activeName" :key="activeName" class="tabs-pane-wrap" />
    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Watch } from 'vue-property-decorator';
import Overview from '@/views/selfMonitor/overview/index.vue';
import Ingest from '@/views/selfMonitor/ingest/index.vue';
import Query from '@/views/selfMonitor/query/index.vue';
import Doris from '@/views/selfMonitor/doris/index.vue';

@Component({
  components: {
    overview: Overview,
    ingest: Ingest,
    query: Query,
    doris: Doris,
  },
})
export default class DeployStatus extends Vue {
  private tabs = [
    { label: '总览', value: 'overview' },
    { label: 'ingest', value: 'ingest' },
    { label: 'web', value: 'query' },
    { label: 'Doris', value: 'doris' },
  ];
  private activeName: 'overview' | 'ingest' | 'query' | 'doris' = 'overview';

  private normalizeType(type: any): 'overview' | 'ingest' | 'query' | 'doris' | null {
    if (type === 'cluster') {
      return 'ingest';
    }
    if (this.tabs.find(t => t.value === type)) {
      return type;
    }
    return null;
  }

  @Watch('$route', { immediate: true, deep: true })
  private onRouteChange(to: any, from: any) {
    if (!from || to.path === from.path) {
      const mapped = this.normalizeType(to.query.type);
      if (mapped) {
        this.activeName = mapped;
      }
    }
  }

  private created() {
    const mapped = this.normalizeType(this.$route.query.type);
    if (mapped) {
      this.activeName = mapped;
    }
  }

  private toggleTabHandle(tab: any) {
    this.activeName = tab.value;
    const { type, __ps, __nw } = this.$route.query;
    if (tab.value !== type) {
      const query: any = { __ps, __nw, type: tab.value };
      this.$router.replace({ query });
    }
  }
}
</script>

<style lang="scss" scoped>
.deploy-status-wrapper {
  flex: 1;
  padding: 14px 16px 20px;
  position: relative;
  overflow: auto;
  background: #f4f6f8;

  .deploy-status {
    height: 100%;
    min-height: 400px;
  }

  .deploy-status-header {
    margin-bottom: 14px;
    padding: 10px 12px;
    background: #fff;
    border: 1px solid #e6ebf0;
    border-radius: 10px;
    box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
  }

  .tabs-pane-wrap {
    flex: 1;
    min-height: 0;
    overflow: auto;
  }
}
</style>
