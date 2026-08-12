<template>
  <div id="app" class="scroll_bar_style">
    <router-view />

    <!-- 主题切换遮罩动画 -->
    <div class="theme-toggle-mask" :class="{ active: themeChanged }">
      <span v-show="theme === 'light'" class="db-icon-sun theme-icon"></span>
      <span v-show="theme !== 'light'" class="db-icon-moon theme-icon"></span>
    </div>

    <div class="hidden-trigger-dom db-icon db-icon-default theme-colors"></div>
  </div>
</template>

<script lang="ts">
import { Component, Vue, Watch } from 'vue-property-decorator';
import { State } from 'vuex-class';
import dayjs from 'dayjs';
import locale from 'element-ui/lib/locale';
import elementZh from 'element-ui/lib/locale/lang/zh-CN';
import elementEn from 'element-ui/lib/locale/lang/en';
import { getToken } from '@/utils/jsCookie';
import { startSessionIdleWatcher } from '@/utils/sessionIdle';
import { AppLocale, hasStoredLocale, setAppLocale } from '@/i18n';
import { toAsyncWait } from '@/utils/common';
import UserApi from '@/api/user';

@Component
export default class App extends Vue {
  @State('theme') private theme!: 'dark' | 'light';
  @State('themeChanged') private themeChanged!: boolean;

  @Watch('themeChanged')
  private WatchThemeChanged(value: boolean) {
    if (value) {
      setTimeout(() => {
        this.$store.commit('UPDATE_THEME_CHANGED', false);
      }, 1200)
    }
  }

  private async created() {
    // 加载主题
    // let themeName: 'dark' | 'light' = 'light';
    const themeName: 'dark' | 'light' = 'light';
    // if (window && window.localStorage) {
    //   const theme = window.localStorage.getItem('DATABUFF_THEME')
    //   if (theme === 'dark' || theme === 'light') {
    //     themeName = theme
    //   }
    // }
    this.$store.commit('UPDATE_THEME', themeName);
    document.documentElement.setAttribute('data-theme', themeName)
    // this.loadElementUIThemeLink(themeName)

    // 平台Logo配置
    this.$store.dispatch('User/getLogoConfig').then((data: any) => {
      document.title = data.productNameEn || '';
      document.getElementById('htmlHeadLinkFavicon')?.setAttribute('href', data.faviconLogo || '/img/favicon.ico');
      const serverLocale = data.locale;
      if (!hasStoredLocale() && (serverLocale === 'zh-CN' || serverLocale === 'en-US')) {
        setAppLocale(serverLocale as AppLocale);
        this.$store.commit('UPDATE_LOCALE', serverLocale);
        locale.use(serverLocale === 'zh-CN' ? elementZh : elementEn);
      }
    });

    await this.getProductVersion();
    this.setProjectVersion();

    if (getToken()) {
      startSessionIdleWatcher();
    }
  }

  private loadElementUIThemeLink (theme: 'dark' | 'light') {
    const $link = document.querySelector('link#elementUIThemeLink') as HTMLLinkElement
    $link && ($link.href = `/css/element-${theme}.css`);
  }

  /** Build/debug metadata for localStorage; product version from backend. */
  private setProjectVersion() {
    const formatTime = (time: any) => {
      if (time) {
        return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
      }
      return ''
    }
    try {
      const versionData = {
        commitBranch: import.meta.env.VITE_COMMIT_BRANCH,
        commitHash: import.meta.env.VITE_COMMIT_HASH,
        commitTime: formatTime(import.meta.env.VITE_COMMIT_TIMESTAMP),
        buildTime: formatTime(import.meta.env.VITE_BUILD_TIMESTAMP),
        productVersion: this.productVersion,
      }
      window.localStorage.setItem('DATABUFF_VERSION', JSON.stringify(versionData));
    } catch (error) {
      //
    }
  }

  private productVersion = ''
  private async getProductVersion () {
    const { result, error } = await toAsyncWait(UserApi.getProductVersion());
    if (!error && result?.data != null && result.data !== '') {
      this.productVersion = String(result.data);
    }
  }
}
</script>

<style lang='scss'>
#app,
.container {
  height: 100%;
}
#app {
  width: 100vw;
  height: 100vh;
  /* min-width: 1280px; */
  overflow-x: auto;
  background-color: var(--bg-color);
}

.theme-toggle-mask {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: var(--bg-color);
  position: fixed;
  top: 0;
  left: 0;
  opacity: 0;
  z-index: -100;

  &.active {
    z-index: 9999;
    animation: themeMaskAni 1s ease-in forwards;
  }

  .theme-icon {
    opacity: 0.5;
    color: var(--color-text-regular);
    font-size: 75px;
  }
}

@keyframes themeMaskAni {
  0% {
    opacity: 1;
  }
  100% {
    opacity: 0;
  }
}

.hidden-trigger-dom {
  width: 0;
  height: 0;
  opacity: 0;
  overflow: hidden;
}
</style>
