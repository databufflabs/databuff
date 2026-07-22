<template>
  <div class="config-wrapper">
    <div class="mb-10">{{ $t('modules.views.configInstall.apm.s_4a5265dc') }}</div>

    <div class="agent-list flex-h mb-10">
      <div
        :class="['agent-item env-item', activeName === 'host' ? 'active' : '']"
        @click="toggleActive('host')">
        <span class="agent-icon db-icon-host-fill"></span>
        <div class="agent-text">
          <span class="name">{{ $t('modules.views.configInstall.apm.s_de396473') }}</span>
          <span class="desc">{{ $t('modules.views.deployInstall.apm.s_e1875349') }}</span>
        </div>
      </div>
      <div
        :class="['agent-item env-item', activeName === 'container' ? 'active' : '']"
        @click="toggleActive('container')">
        <span class="agent-icon db-icon-container-fill"></span>
        <div class="agent-text">
          <span class="name">{{ $t('modules.views.deployInstall.apm.s_553c3b7e') }}</span>
          <span class="desc">{{ $t('modules.views.deployInstall.apm.s_56f5c4fc') }}</span>
        </div>
      </div>
    </div>

    <h5>{{ $t('modules.views.deployInstall.apm.s_27abe810') }}</h5>
    <p>{{ $t('modules.views.deployInstall.apm.s_6962ee60') }}</p>
    <marked-view :data="endpointTableText" />

    <template v-if="activeName === 'host'">
      <h5>{{ $t('modules.views.deployInstall.apm.s_50ed1cac') }}</h5>
      <db-radio v-model="injectMode" :options="injectTabs" @change="onInjectModeChange" />

      <template v-if="injectMode === 'manual'">
        <h5>{{ $t('modules.views.deployInstall.apm.s_109c57fc') }}</h5>
        <div class="agent-list flex-h logo-list">
          <div
            v-for="(t, i) in langList"
            :key="i"
            @click="toggleLang(t.name)"
            :class="['agent-item', { active: langActive === t.name }]">
            <span :class="t.icon" class="agent-icon mr-5"></span>
            {{ t.iconText }}
          </div>
        </div>
        <keep-alive>
          <component
            :is="langComp"
            class="mt-10"
            :ingest-host="ingestHost"
            :ingest-http-endpoint="ingestHttpEndpoint"
            :ingest-grpc-endpoint="ingestGrpcEndpoint" />
        </keep-alive>
      </template>

      <keep-alive v-else>
        <host-auto
          class="mt-10"
          :ingest-host="ingestHost"
          :ingest-http-endpoint="ingestHttpEndpoint"
          :ingest-grpc-endpoint="ingestGrpcEndpoint" />
      </keep-alive>
    </template>

    <keep-alive v-else>
      <kubernetes :ingest-http-endpoint="ingestHttpEndpoint" />
    </keep-alive>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Watch } from 'vue-property-decorator';
import i18n from '@/i18n';
import MarkedView from '@/components/marked-view.vue';
import HostJava from './host-java.vue';
import HostPython from './host-python.vue';
import HostNode from './host-node.vue';
import HostGo from './host-go.vue';
import HostNet from './host-net.vue';
import HostPhp from './host-php.vue';
import HostRuby from './host-ruby.vue';
import HostAuto from './host-auto.vue';
import Kubernetes from './kubernetes.vue';

@Component({
  components: {
    MarkedView,
    HostJava,
    HostPython,
    HostNode,
    HostGo,
    HostNet,
    HostPhp,
    HostRuby,
    HostAuto,
    Kubernetes,
  },
})
export default class DeployApm extends Vue {
  private activeName: 'host' | 'container' = 'host';
  private langActive = 'java';
  private injectMode: 'auto' | 'manual' = 'manual';

  private injectTabs = [
    { label: i18n.t('modules.views.configInstall.apm.s_c7d68fb7') as string, labelKey: 'modules.views.configInstall.apm.s_c7d68fb7', value: 'manual', describe: i18n.t('modules.views.deployInstall.apm.s_8989faac') as string, describeKey: 'modules.views.deployInstall.apm.s_8989faac' },
    { label: i18n.t('modules.views.configInstall.apm.s_d2f66d9b') as string, labelKey: 'modules.views.configInstall.apm.s_d2f66d9b', value: 'auto', describe: i18n.t('modules.views.deployInstall.apm.s_b73a9477') as string, describeKey: 'modules.views.deployInstall.apm.s_b73a9477' },
  ]

  private langList = [
    { name: 'java', icon: 'db-icon-java', iconText: 'Java' },
    { name: 'python', icon: 'db-icon-python', iconText: 'Python' },
    { name: 'node', icon: 'db-icon-nodejs', iconText: 'Node.js' },
    { name: 'go', icon: 'db-icon-go', iconText: 'Go' },
    { name: 'net', icon: 'db-icon-dotnet', iconText: '.NET' },
    { name: 'php', icon: 'db-icon-php', iconText: 'PHP' },
    { name: 'ruby', icon: 'db-icon-ruby', iconText: 'Ruby' },
  ]

  get ingestHost () {
    return window.location.hostname || '127.0.0.1';
  }

  get ingestHttpEndpoint () {
    return `http://${this.ingestHost}:4318`;
  }

  get ingestGrpcEndpoint () {
    return `http://${this.ingestHost}:4317`;
  }

  get endpointTableText () {
    return `
| 协议 | 默认端口 | 示例地址 |
| --- | --- | --- |
| OTLP gRPC | 4317 | \`${this.ingestGrpcEndpoint}\` |
| OTLP HTTP | 4318 | \`${this.ingestHttpEndpoint}\` |
`;
  }

  get langComp () {
    const map: Record<string, string> = {
      java: 'host-java',
      python: 'host-python',
      node: 'host-node',
      go: 'host-go',
      net: 'host-net',
      php: 'host-php',
      ruby: 'host-ruby',
    };
    return map[this.langActive] || '';
  }

  @Watch('$route', { immediate: true, deep: true })
  private onRouteChange (to: any, from: any) {
    if (!from || to.path === from.path) {
      const { env, lang, inject } = to.query
      this.setActive(env as any, lang as any, inject as any)
    }
  }

  private onInjectModeChange () {
    this.syncRouteQuery()
  }

  private syncRouteQuery () {
    const { __ps, __nw } = this.$route.query
    this.$router.replace({
      query: {
        __ps,
        __nw,
        type: 'apm',
        env: this.activeName,
        inject: this.activeName === 'host' ? this.injectMode : undefined,
        lang: this.activeName === 'host' && this.injectMode === 'manual' ? this.langActive : undefined,
      },
    });
  }

  private toggleActive(name: 'host' | 'container') {
    const { __ps, __nw } = this.$route.query
    this.$router.replace({
      query: {
        __ps,
        __nw,
        type: 'apm',
        env: name,
        inject: name === 'host' ? this.injectMode : undefined,
        lang: name === 'host' && this.injectMode === 'manual' ? this.langActive : undefined,
      },
    });
  }

  private toggleLang(name: string) {
    const { __ps, __nw } = this.$route.query
    this.$router.replace({
      query: {
        __ps,
        __nw,
        type: 'apm',
        env: 'host',
        inject: 'manual',
        lang: name,
      },
    });
  }

  private setActive (env?: string, lang?: string, inject?: string) {
    this.activeName = env === 'container' ? 'container' : 'host';
    if (this.activeName === 'host') {
      this.injectMode = inject === 'auto' ? 'auto' : 'manual';
      const names = this.langList.map(t => t.name);
      this.langActive = typeof lang === 'string' && names.includes(lang) ? lang : 'java';
    }
  }
}
</script>

<style lang="scss" scoped>
.agent-item {
  margin: 0 16px 10px 0;
  width: 20%;
  max-width: 145px;
  height: 80px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: var(--bg-color02);
  border: 1px solid var(--border-color-lighter);
  border-radius: 4px;
  transition: all 0.3s;
  color: var(--color-text-primary);
  font-size: 14px;
  user-select: none;
  cursor: pointer;
  &:last-child {
    margin-right: 0;
  }

  &.active,
  &:hover {
    background-color: var(--bg-color);
    border-color: var(--bg-color);
    color: var(--color-primary);
    box-shadow: 0px 4px 10px 0px rgba(119, 122, 126, 0.2);
  }

  &.active {
    cursor: auto;
  }

  .agent-icon {
    font-size: 32px;
    margin-right: 10px;
  }
}

.env-item {
  padding-left: 15px;
  width: 350px;
  max-width: none;
  .agent-icon {
    width: 58px;
    height: 58px;
    line-height: 58px;
    text-align: center;
  }
  .agent-text {
    flex: 1;
    .name {
      display: block;
      margin-bottom: 4px;
      font-size: 16px;
    }
    .desc {
      font-size: 13px;
      line-height: 18px;
    }
  }
}

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

  .logo-list .agent-item {
    width: 14%;
    min-width: 120px;
    max-width: 145px;
  }

  :deep(.code-view-wrapper.block) {
    margin-bottom: 10px;
  }
}
</style>
