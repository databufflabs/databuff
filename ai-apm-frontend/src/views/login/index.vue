<template>
  <div class="login-page">
    <div class="login-content">
      <div v-if="loginLogo || productNameCn" class="login-logo-slogan">
        <div v-if="loginLogo" class="login-logo-wrap">
          <img :src="loginLogo" alt="" class="login-logo-icon" />
          <img src="/img/logo_wordmark.svg" alt="Databuff" class="login-logo-wordmark" />
        </div>
        <div v-if="productNameCn" class="login-slogan">{{ productNameCn }}</div>
      </div>

      <login-form class="login-form" />
    </div>

    <div class="login-footer">
      <div class="browser-suggest">
        <span v-if="version" class="mr-10">{{ version }}</span>
        <span class="db-icon-earth icon-earth" />
        {{ $t('login.browserSuggest') }}
      </div>
      <div v-if="copyright" class="copyright-info">{{ $t('login.copyright', { company: copyright }) }}</div>
    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component } from 'vue-property-decorator';
import LoginForm from './loginForm.vue';
import { LensVersionReg } from '@/utils/regexp';
@Component({
  components: {
    LoginForm,
  },
})
export default class Login extends Vue {
  get loginLogo () {
    return this.$store.getters['User/getLogoConfig']?.loginLogo || '';
  }

  get productNameCn () {
    return this.$store.getters['User/getLogoConfig']?.productNameCn || '';
  }

  get copyright () {
    return this.$store.getters['User/getLogoConfig']?.copyright || '';
  }

  private version = ''

  private async created() {
    if (window.history) {
      window.history.pushState(null, '', document.URL);
    }
    if (window.localStorage) {
      // 清除高级配置页面的解锁状态
      window.localStorage.removeItem('DATABUFF_ADVANCED_UNLOCKING')
    }

    try {
      const originVersion = JSON.parse(window.localStorage.getItem('DATABUFF_VERSION') || '{}')?.productVersion || '';
      this.version = LensVersionReg.test(originVersion) ? `${originVersion.substring(0, 1).toUpperCase()}${originVersion.substring(1)}` : originVersion
    } catch (error) {
      //
    }
  }
}
</script>

<style lang="scss" scoped>
.login-page {
  min-width: 500px;
  height: 100%;
  min-height: 600px;
  padding-bottom: 62px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: PingFang-SC, sans-serif, Arial;
  background-color: #F0F4FA;
  background: linear-gradient(112deg, #F4F5F6 0%, #F0F4FA 100%);
  position: relative;

  .login-content {
    width: 432px;
    padding: 56px;
    background: #FFFFFF;
    box-shadow: 2px 6px 15px 0px rgba(180, 184, 193, 0.12);
    border-radius: 12px;
    position: relative;

    .login-logo-slogan {
      margin-bottom: 56px;
    }

    .login-logo-wrap {
      width: 100%;
      height: 40px;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 12px;
    }

    .login-logo-icon {
      display: block;
      height: 100%;
      width: auto;
    }

    .login-logo-wordmark {
      display: block;
      height: 24px;
      width: auto;
    }

    .login-slogan {
      color: #121317;
      font-size: 16px;
      line-height: 22px;
      text-align: center;
    }

    .login-logo-wrap + .login-slogan {
      margin-top: 16px;
    }
  }

  .login-form {
    width: 100%;
    position: relative;
  }

  .login-footer {
    width: 100%;
    text-align: center;
    font-size: 14px;
    color: #777A7E;
    line-height: 16px;
    user-select: none;
    position: absolute;
    bottom: 20px;

    .icon-earth {
      margin-top: -2px;
      display: inline-block;
      vertical-align: middle;
    }

    .copyright-info {
      margin-top: 10px;
    }
  }
}
</style>
