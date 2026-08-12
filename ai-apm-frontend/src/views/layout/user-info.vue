<template>
  <div class="user-info-cont">
    <div class="user-info-wrapper">
      <el-popover
        trigger="click"
        placement='right-end'
        :close-delay="0"
        popper-class="user-info-popover"
      >
        <div
          slot="reference"
          :class="['user-avatar flex-h cp', collapse ? 'user-avatar-collapse' : '']"
          :title="collapse && versionLabel ? versionLabel : undefined"
        >
          <span class="user-avatar-icon">{{ (userInfo.account || '').slice(0, 1) | FirstLetterCapital }}</span>
          <div class="user-avatar-meta">
            <span class="user-avatar-text">{{ userInfo.account }}</span>
            <span v-if="version && !collapse" class="user-avatar-version">{{ versionLabel }}</span>
          </div>
        </div>

        <div class="sub-nav-box user-info">
          <div class="sub-nav-link cp" @click="showUserInfoHandle">{{ $t('user.profile') }}</div>
          <div class="sub-nav-link cp" @click="logoutHandle">{{ $t('user.logout') }}</div>
        </div>
      </el-popover>
    </div>
  </div>
</template>

<script lang="ts">
  import { Vue, Component, Prop } from 'vue-property-decorator';
  import { namespace } from 'vuex-class';
  import UserApi from '@/api/user'
  import { removeTokenAndCid } from '@/utils/jsCookie'
  import { toAsyncWait } from '@/utils/common'

  const UserModel = namespace('User');

  @Component({})
  export default class UserInfo extends Vue {
    @Prop() private collapse!: boolean;

    @UserModel.State private userInfo!: any;

    private version = ''

    get versionLabel () {
      return this.version ? this.$t('user.version', { version: this.version }) as string : '';
    }

    private async created() {
      const { result, error } = await toAsyncWait(UserApi.getProductVersion());
      if (!error && result?.data != null && result.data !== '') {
        this.version = String(result.data);
      }
    }

    private showUserInfoHandle () {
      this.$router.push({
        path: '/personal',
        query: { __ps: 'm' }
      })
    }

    private logoutHandle () {
      UserApi.logoutHandle()
        .then(() => {
          this.$router.replace({
            query: {
              ...this.$route.query,
              __redirect: 'false',
            }
          });
          removeTokenAndCid()
          sessionStorage.setItem('DB_TIMEINFO_SHOW', '')
          window.location.reload()
        })
    }
  }
</script>

<style lang="scss" scoped>
.user-info-cont {
  margin-bottom: 4px;
  color: #CCCED4;
  .user-avatar {
    padding: 4px 6px;
    min-height: 32px;
    border-radius: 4px;
    user-select: none;
    position: relative;
    align-items: center;

    &:hover {
      background: rgba(255, 255, 255, 0.08);
    }

    .user-avatar-icon {
      flex: none;
      width: 24px;
      height: 24px;
      background: #2962FF;
      border-radius: 20px;
      line-height: 24px;
      text-align: center;
      font-family: Source Han Sans;
      font-size: 16px;
      color: #FFFFFF;
    }

    .user-avatar-meta {
      margin-left: 10px;
      width: 92px;
      min-width: 0;
      display: flex;
      flex-direction: column;
      justify-content: center;
      overflow: hidden;
      transition: width 0.1s;
    }

    .user-avatar-text {
      font-size: 13px;
      line-height: 18px;
      font-weight: 500;
      font-family: PingFang SC;
      text-overflow: ellipsis;
      white-space: nowrap;
      overflow: hidden;
    }

    .user-avatar-version {
      margin-top: 1px;
      font-size: 11px;
      line-height: 14px;
      font-weight: 400;
      color: rgba(204, 206, 212, 0.65);
      text-overflow: ellipsis;
      white-space: nowrap;
      overflow: hidden;
    }

    &.user-avatar-collapse {
      .user-avatar-meta {
        width: 0;
        margin-left: 0;
      }
    }
  }
}
.sub-nav-box {
  position: relative;
  z-index: 1;
  .sub-nav-link {
    width: 100%;
    height: 32px;
    line-height: 32px;
    text-align: center;
    font-size: 13px;
    color: var(--color-text-regular);
    transition: background-color 0.3s;

    &:focus,
    &:hover {
      background-color: var(--bg-color03);
    }
  }
}
</style>

<style lang="scss">
.user-info-popover.el-popover {
  padding: 6px 0;
  min-width: 100px;
  background-color: var(--bg-color);
  border-color: var(--border-color-base);
  box-shadow: 0 0 16px 0 var(--bg-color-base);
  &[x-placement^=right] .popper__arrow {
    z-index: 0;
    border-right-color: var(--border-color-base);
  }
}

:root[data-theme=light] {
  .user-info-popover.el-popover {
    box-shadow: 0 2px 24px 0 var(--shadow-color02);
  }
}
</style>
