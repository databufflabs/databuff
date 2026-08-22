<template>
  <div
    v-if='hasNoticeEnable'
    v-loading='noticeLoading'
    class="notice-wrap">
    <!-- 邮件 -->
    <div v-if="noticeEnable.email !== 'hide'" class="notice-item">
      <el-checkbox v-model='noticeForm.emailModel' :disabled="noticeEnable.email !== 'normal'">{{ $t('modules.utils.filters.s_e9e8054f') }}</el-checkbox>
      <div v-if="noticeEnable.email === 'normal' && noticeForm.emailModel" class="pl-20 pt-10 pb-5">
        <scroll-select
          v-model="noticeForm.emailList"
          :options="receiverData.email"
          multiple
          :collapse-tags="false"
          :showTitle="true"
          :placeholder="$t('modules.views.configManage.alarm.s_fe429aa4')"
          class="w530"
        ></scroll-select>
      </div>
      <p v-if="noticeEnable.email === 'saasSetting'" class="describe">
        {{ $t('modules.views.configManage.alarm.s_1d26119b') }}
        <span>{{ $t('modules.views.configManage.alarm.s_9e14599b') }}</span> ->
        <span>{{ $t('modules.views.configManage.alarm.s_6e20a191') }}</span> {{ $t('modules.views.configManage.alarm.s_cc42dd31') }}
      </p>
      <p v-if="noticeEnable.email === 'singleSetting'" class="describe">
        {{ $t('modules.views.configManage.alarm.s_1d26119b') }}
        <router-link to="/sysManage" class="route-link-btn">{{ $t('modules.views.configManage.alarm.s_ef4efcda') }}</router-link> ->
        <router-link to="/sysManage/notice?type=email" class="route-link-btn">{{ $t('modules.views.configManage.alarm.s_6e20a191') }}</router-link> {{ $t('modules.views.configManage.alarm.s_cc42dd31') }}
      </p>
      <p v-if="noticeEnable.email === 'contactAdmin'" class="describe">{{ $t('modules.views.configManage.alarm.s_3c8582c5') }}</p>
    </div>

    <!-- 短信 -->
    <div v-if="noticeEnable.sms !== 'hide'" class="notice-item">
      <el-checkbox v-model='noticeForm.smsModel' :disabled="noticeEnable.sms !== 'normal'">{{ $t('modules.utils.filters.s_485c3abb') }}</el-checkbox>
      <div v-if="noticeEnable.sms === 'normal' && noticeForm.smsModel" class="pl-20 pt-10 pb-5">
        <scroll-select
          v-model="noticeForm.smsList"
          :options="receiverData.sms"
          multiple
          :collapse-tags="false"
          :showTitle="true"
          :placeholder="$t('modules.views.configManage.alarm.s_7864dddf')"
          class="w530"
        ></scroll-select>
      </div>
      <p v-if="noticeEnable.sms === 'saasSetting'" class="describe">
        {{ $t('modules.views.configManage.alarm.s_1d26119b') }}
        <span>{{ $t('modules.views.configManage.alarm.s_9e14599b') }}</span> ->
        <span>{{ $t('modules.views.configManage.alarm.s_6e20a191') }}</span> {{ $t('modules.views.configManage.alarm.s_cc42dd31') }}
      </p>
      <p v-if="noticeEnable.sms === 'singleSetting'" class="describe">
        {{ $t('modules.views.configManage.alarm.s_1d26119b') }}
        <router-link to="/sysManage" class="route-link-btn">{{ $t('modules.views.configManage.alarm.s_ef4efcda') }}</router-link> ->
        <router-link to="/sysManage/notice?type=sms" class="route-link-btn">{{ $t('modules.views.configManage.alarm.s_6e20a191') }}</router-link> {{ $t('modules.views.configManage.alarm.s_cc42dd31') }}
      </p>
      <p v-if="noticeEnable.sms === 'contactAdmin'" class="describe">{{ $t('modules.views.configManage.alarm.s_3c8582c5') }}</p>
    </div>

    <!-- 钉钉 -->
    <div v-if="noticeEnable.dingtalk !== 'hide'" class="notice-item">
      <el-checkbox v-model='noticeForm.dingtalkModel' :disabled="noticeEnable.dingtalk !== 'normal'">{{ $t('modules.utils.filters.s_4a0e9142') }}</el-checkbox>
      <div v-if="noticeEnable.dingtalk === 'normal' && noticeForm.dingtalkModel" class="pl-20 pt-10 pb-5">
        <scroll-select
          v-model="noticeForm.dingtalkList"
          :options="receiverData.dingtalk"
          multiple
          :collapse-tags="false"
          :showTitle="true"
          :placeholder="$t('modules.views.configManage.alarm.s_dd209929')"
          class="w530"
        ></scroll-select>
      </div>
      <p v-if="noticeEnable.dingtalk === 'saasSetting'" class="describe">
        {{ $t('modules.views.configManage.alarm.s_1d26119b') }}
        <span>{{ $t('modules.views.configManage.alarm.s_9e14599b') }}</span> ->
        <span>{{ $t('modules.views.configManage.alarm.s_6e20a191') }}</span> {{ $t('modules.views.configManage.alarm.s_cc42dd31') }}
      </p>
      <p v-if="noticeEnable.dingtalk === 'singleSetting'" class="describe">
        {{ $t('modules.views.configManage.alarm.s_1d26119b') }}
        <router-link to="/sysManage" class="route-link-btn">{{ $t('modules.views.configManage.alarm.s_ef4efcda') }}</router-link> ->
        <router-link to="/sysManage/notice?type=dingtalk" class="route-link-btn">{{ $t('modules.views.configManage.alarm.s_6e20a191') }}</router-link> {{ $t('modules.views.configManage.alarm.s_cc42dd31') }}
      </p>
      <p v-if="noticeEnable.dingtalk === 'contactAdmin'" class="describe">{{ $t('modules.views.configManage.alarm.s_3c8582c5') }}</p>
    </div>

    <!-- 企业微信 -->
    <div v-if="noticeEnable.wechat !== 'hide'" class="notice-item">
      <el-checkbox v-model='noticeForm.wechatModel' :disabled="noticeEnable.wechat !== 'normal'">{{ $t('modules.utils.filters.s_ff17b9f9') }}</el-checkbox>
      <div v-if="noticeEnable.wechat === 'normal' && noticeForm.wechatModel" class="pl-20 pt-10 pb-5">
        <scroll-select
          v-model="noticeForm.wechatList"
          :options="receiverData.wechat"
          multiple
          :collapse-tags="false"
          :showTitle="true"
          :placeholder="$t('modules.views.configManage.alarm.s_f2170f0c')"
          class="w530"
        ></scroll-select>
      </div>
      <p v-if="noticeEnable.wechat === 'saasSetting'" class="describe">
        {{ $t('modules.views.configManage.alarm.s_1d26119b') }}
        <span>{{ $t('modules.views.configManage.alarm.s_9e14599b') }}</span> ->
        <span>{{ $t('modules.views.configManage.alarm.s_6e20a191') }}</span> {{ $t('modules.views.configManage.alarm.s_cc42dd31') }}
      </p>
      <p v-if="noticeEnable.wechat === 'singleSetting'" class="describe">
        {{ $t('modules.views.configManage.alarm.s_1d26119b') }}
        <router-link to="/sysManage" class="route-link-btn">{{ $t('modules.views.configManage.alarm.s_ef4efcda') }}</router-link> ->
        <router-link to="/sysManage/notice?type=wechat" class="route-link-btn">{{ $t('modules.views.configManage.alarm.s_6e20a191') }}</router-link> {{ $t('modules.views.configManage.alarm.s_cc42dd31') }}
      </p>
      <p v-if="noticeEnable.wechat === 'contactAdmin'" class="describe">{{ $t('modules.views.configManage.alarm.s_3c8582c5') }}</p>
    </div>

    <!-- Webhook推送 -->
    <div v-if="noticeEnable.webhook !== 'hide'" class="notice-item">
      <div class="flex-h">
        <el-checkbox v-model='noticeForm.webhookModel' :disabled="noticeEnable.webhook !== 'normal'">{{ $t('modules.views.configManage.alarm.s_a848f454') }}</el-checkbox>
        <el-tooltip class="ml-10" content="仅HTTP/HTTPS推送，请求方式为POST请求，content-type为application/json;charset=UTF-8" placement="top-start" effect="light">
          <i class="db-icon-info describe"></i>
        </el-tooltip>
      </div>
      <p v-if="noticeEnable.webhook === 'saasSetting'" class="describe">
        {{ $t('modules.views.configManage.alarm.s_1d26119b') }}
        <span>{{ $t('modules.views.configManage.alarm.s_9e14599b') }}</span> ->
        <span>{{ $t('modules.views.configManage.alarm.s_6e20a191') }}</span> {{ $t('modules.views.configManage.alarm.s_cc42dd31') }}
      </p>
      <p v-if="noticeEnable.webhook === 'singleSetting'" class="describe">
        {{ $t('modules.views.configManage.alarm.s_1d26119b') }}
        <router-link to="/sysManage" class="route-link-btn">{{ $t('modules.views.configManage.alarm.s_ef4efcda') }}</router-link> ->
        <router-link to="/sysManage/notice?type=webhook" class="route-link-btn">{{ $t('modules.views.configManage.alarm.s_6e20a191') }}</router-link> {{ $t('modules.views.configManage.alarm.s_cc42dd31') }}
      </p>
      <p v-if="noticeEnable.webhook === 'contactAdmin'" class="describe">{{ $t('modules.views.configManage.alarm.s_3c8582c5') }}</p>
      <div v-if='noticeForm.webhookModel' class="pl-20">
        <el-form-item
          label="URL"
          prop="webhookUrl"
          label-width="70px"
          class="w530 pt-10">
          <el-input
            v-model="settingForm.webhookUrl"
            maxlength="300"
            :placeholder="$t('modules.views.configManage.alarm.s_d259e0c9')"
            class="webhook-url-ipt">
            <el-select v-model="settingForm.webhookMethod" slot="prepend" :placeholder="$t('modules.views.metrics.list.s_708c9d6d')" class="w80">
              <el-option label="GET" value="GET"></el-option>
              <el-option label="POST" value="POST"></el-option>
              <el-option label="PUT" value="PUT"></el-option>
            </el-select>
          </el-input>
        </el-form-item>

        <el-form-item
          label="Headers"
          prop="webhookHeader"
          label-width="70px"
          class="w530">
          <div
            v-for="(item, index) in settingForm.webhookHeader"
            :key="index"
            class="field-item">
            <el-input
              v-model="item.key"
              :placeholder="$t('modules.views.configManage.alarm.s_d3145589')" :maxlength='100' size="small"
              class="field-input"></el-input>
            <el-input
              v-model="item.value"
              :placeholder="$t('modules.components.matching-criteria.s_a3390352')" size="small"
              class="field-input"></el-input>
            <span
              @click="addFieldHandle(settingForm.webhookHeader, 10)"
              class="field-icon add el-icon-circle-plus-outline"></span>
            <span
              @click="deleteFieldHandle(settingForm.webhookHeader, index)"
              class="field-icon delete el-icon-delete"></span>
          </div>
          <div class="btns">
            <el-button :disabled="testLoading" @click="testNotice()" size="small">{{ $t('modules.views.configManage.alarm.s_edb13707') }}</el-button>
          </div>
        </el-form-item>
      </div>
    </div>

    <!-- Socket -->
    <div v-if="noticeEnable.socket !== 'hide'" class="notice-item">
      <el-checkbox v-model='noticeForm.socketModel' :disabled="noticeEnable.socket !== 'normal'">Socket</el-checkbox>
      <div v-if="noticeEnable.socket === 'normal' && noticeMapping.socket.selectable && noticeForm.socketModel" class="pl-20 pt-10 pb-5">
        <scroll-select
          v-model="noticeForm.socketList"
          :options="receiverData.sms"
          multiple
          :collapse-tags="false"
          :showTitle="true"
          :placeholder="$t('modules.views.configManage.alarm.s_949a3420')"
          class="w530"
        ></scroll-select>
      </div>
      <p v-if="noticeEnable.socket === 'saasSetting'" class="describe">
        {{ $t('modules.views.configManage.alarm.s_1d26119b') }}
        <span>{{ $t('modules.views.configManage.alarm.s_9e14599b') }}</span> ->
        <span>{{ $t('modules.views.configManage.alarm.s_6e20a191') }}</span> {{ $t('modules.views.configManage.alarm.s_cc42dd31') }}
      </p>
      <p v-if="noticeEnable.socket === 'singleSetting'" class="describe">
        {{ $t('modules.views.configManage.alarm.s_1d26119b') }}
        <router-link to="/sysManage" class="route-link-btn">{{ $t('modules.views.configManage.alarm.s_ef4efcda') }}</router-link> ->
        <router-link to="/sysManage/notice?type=socket" class="route-link-btn">{{ $t('modules.views.configManage.alarm.s_6e20a191') }}</router-link> {{ $t('modules.views.configManage.alarm.s_cc42dd31') }}
      </p>
      <p v-if="noticeEnable.socket === 'contactAdmin'" class="describe">{{ $t('modules.views.configManage.alarm.s_3c8582c5') }}</p>
    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Watch, Prop } from 'vue-property-decorator'
import { namespace } from 'vuex-class'
import NoticeApi from '@/api/notice';
import i18n from '@/i18n';
import SystemApi from '@/api/system';
import { toAsyncWait } from '@/utils/common';

const UserState = namespace('User');

type NoticeType = 'email' | 'sms' | 'dingtalk' | 'wechat' | 'webhook' | 'socket';
interface NoticeEnable {
  [propName: string]: 'normal' | 'saasSetting' | 'singleSetting' | 'contactAdmin' |  'hide';
}
interface ConfigItem {
  way: NoticeType | 'mail',
  isSingle?: number,
  uid?: number[],
}

@Component
export default class NoticeReceiver extends Vue {
  @UserState.Getter('hasSystemNoticeMenu') private hasSystemNoticeMenu!: any;
  @Prop({ default: () => [] }) private initConfig!: ConfigItem[];
  @Prop({ default: () => [] }) private noticeTypes!: NoticeType[]; // 可显示的通知类型，空数组表示全部

  get showNoticeMapping () {
    const mapping: any = {
      email: true,
      sms: true,
      dingtalk: true,
      wechat: true,
      webhook: false,
      socket: true,
    };
    if (this.noticeTypes && this.noticeTypes.length) {
      Object.keys(mapping).forEach((key: any) => {
        mapping[key] = this.noticeTypes.includes(key)
      })
    }
    // Alarm webhooks are configured only in application.yml, never per policy.
    mapping.webhook = false
    return mapping
  }

  private noticeForm: any = {
    emailModel: false, // 通知是否开启
    emailList: [],
    smsModel: false,
    smsList: [],
    dingtalkModel: false,
    dingtalkList: [],
    wechatModel: false,
    wechatList: [],
    webhookModel: false,
    socketModel: false,
    socketList: [],
  }

  private noticeLoading = true; // 通知权限loading
  private noticeMapping: any = { // 通知权限开启状态数据
    email: {},
    sms: {},
    dingtalk: {},
    wechat: {},
    webhook: {
      enable: true,
      tenantEnable: true,
      configStatus: true,
    },
    socket: {},
  }

  private settingForm: any = {
    webhookUrl: '',
    webhookMethod: 'GET',
    webhookHeader: [
      { key: '', value: '' },
    ],
  }

  private testLoading = false

  get noticeEnable (): NoticeEnable { // 通知权限
    /*
      enable        平台侧开关
      tenantEnable  项目内开关
      configStatus   是否已配置

      单机版通知权限判断逻辑：（相当于平台侧已开启）
      开关未开启 -> 有菜单: 去设置  /  无菜单: 联系管理员
      开关已开启 -> 无配置 -> 有菜单: 去设置  /  无菜单: 联系管理员
      开关已开启 -> 有配置 -> 正常
    */
    const getEnable = (type: string) => {
      const { enable, tenantEnable, configStatus } = this.noticeMapping[type] || {}
      const hide = !this.showNoticeMapping[type]
      const hasMenu = this.hasSystemNoticeMenu
      return hide ? 'hide' : tenantEnable && configStatus ? 'normal' : hasMenu ? 'singleSetting' : 'contactAdmin'
    }
    return {
      email: getEnable('email'),
      sms: getEnable('sms'),
      dingtalk: getEnable('dingtalk'),
      wechat: getEnable('wechat'),
      webhook: getEnable('webhook'),
      socket: getEnable('socket'),
    }
  }
  get hasNoticeEnable () { // 是否有通知权限
    return Object.values(this.noticeEnable).some(t => t !== 'hide')
  }
  @Watch('hasNoticeEnable', { immediate: true })
  private onHasNoticeEnableChange () {
    this.$emit('notice-enable', this.hasNoticeEnable)
  }
  @Watch('initConfig', { immediate: true })
  private onInitConfigChange () {
    const configs = [...this.initConfig || []]
    const email = configs.find(t => t.way === 'mail')
    const sms = configs.find(t => t.way === 'sms')
    const webhook: any = configs.find(t => t.way === 'webhook')
    const socket: any = configs.find(t => t.way === 'socket')
    const dingtalkList = [...new Set(configs.filter(t => t.way === 'dingtalk').map(t => t.uid).flat())]
    const wechatList = [...new Set(configs.filter(t => t.way === 'wechat').map(t => t.uid).flat())]
    this.noticeForm = {
      emailModel: !!email,
      emailList: (email || {}).uid || [],
      smsModel: !!sms,
      smsList: (sms || {}).uid || [],
      dingtalkModel: !!dingtalkList.length,
      dingtalkList,
      wechatModel: !!wechatList.length,
      wechatList,
      webhookModel: !!webhook,
      socketModel: !!socket,
      socketList: (socket || {}).uid || [],
    }
    if (this.receiverData.loaded) {
      this.filterReceiver()
    }
    if (webhook) {
      const { config = {} } = webhook || {};
      const { webhookHeader = '', webhookHeaderWebUse } = config || {};
      if (!webhookHeader && !webhookHeaderWebUse) {
        this.settingForm.webhookHeader = [{ key: '', value: '' }]
      } else {
        this.settingForm.webhookHeader = webhookHeaderWebUse ? webhookHeaderWebUse : (JSON.parse(webhookHeader || '[]')).map((t: string) => {
          const [key, value] = t.split(':')
          return { key, value }
        })
      }

      this.settingForm.webhookMethod = (config || {}).webhookMethod || 'GET'
      this.settingForm.webhookUrl = (config || {}).webhookUrl || ''
    }
  }

  private created() {
    this.fetchAllConfig()
    this.getAllReceiverList()
  }

  public getData () {
    const noticeForm = { ...this.noticeForm }
    let error: any = null
    const data: any[] = []
    const filterUidList = (list: number[], allList: any[], field: string) => {
      return list.filter(id => allList.find((t: any) => t.value === id && t[field]));
    }
    if (this.showNoticeMapping.email && noticeForm.emailModel) {
      data.push({ way: 'mail', uid: [...noticeForm.emailList] })
      if (!error && !noticeForm.emailList.length) {
        error = { errorField: 'mail', errorMsg: i18n.t('modules.views.configManage.alarm.s_fe429aa4') as string }
      }
    }
    if (this.showNoticeMapping.sms && noticeForm.smsModel) {
      data.push({ way: 'sms', uid: [...noticeForm.smsList] })
      if (!error && !noticeForm.smsList.length) {
        error = { errorField: 'sms', errorMsg: i18n.t('modules.views.configManage.alarm.s_7864dddf') as string }
      }
    }
    if (this.showNoticeMapping.dingtalk && noticeForm.dingtalkModel) {
      const singles = filterUidList(noticeForm.dingtalkList, this.receiverData.dingtalk, 'single');
      const groups = filterUidList(noticeForm.dingtalkList, this.receiverData.dingtalk, 'group');
      if (singles.length) {
        data.push({ way: 'dingtalk', isSingle: 1, uid: singles, })
      }
      if (groups.length) {
        data.push({ way: 'dingtalk', isSingle: 0, uid: groups, })
      }
      if (!error && !noticeForm.dingtalkList.length) {
        error = { errorField: 'dingtalk', errorMsg: i18n.t('modules.views.configManage.alarm.s_dd209929') as string }
      }
    }
    if (this.showNoticeMapping.wechat && noticeForm.wechatModel) {
      const singles = filterUidList(noticeForm.wechatList, this.receiverData.wechat, 'single');
      const groups = filterUidList(noticeForm.wechatList, this.receiverData.wechat, 'group');
      if (singles.length) {
        data.push({ way: 'wechat', isSingle: 1, uid: singles, })
      }
      if (groups.length) {
        data.push({ way: 'wechat', isSingle: 0, uid: groups, })
      }
      if (!error && !noticeForm.wechatList.length) {
        error = { errorField: 'wechat', errorMsg: i18n.t('modules.views.configManage.alarm.s_f2170f0c') as string }
      }
    }
    if (this.showNoticeMapping.webhook && noticeForm.webhookModel) {
      // 过滤掉字段名和字段值都为空的项
      const filterValue = this.settingForm.webhookHeader.filter((t: any) => t.key.trim() && t.value.trim())
      const params = {
        way: 'webhook',
        isSingle: 1,
        config: {
          ...this.settingForm,
          webhookHeaderWebUse: [...this.settingForm.webhookHeader],
          webhookHeader: JSON.stringify(filterValue.map((t: any) => `${t.key}:${t.value}`))
        }
      }
      data.push({ ...params })
    }
    if (this.showNoticeMapping.socket && noticeForm.socketModel) {
      if (this.noticeMapping.socket.selectable) {
        data.push({ way: 'socket', uid: [...noticeForm.socketList] })
        if (!error && !noticeForm.socketList.length) {
          error = { errorField: 'socket', errorMsg: i18n.t('modules.views.configManage.alarm.s_949a3420') as string }
        }
      } else {
        data.push({ way: 'socket', uid: [] })
      }
    }
    return { ...error, data }
  }

  // 获取通知权限
  private async fetchAllConfig () {
    this.noticeLoading = true;
    await Promise.allSettled([
      this.getEmailContacts(),
      this.getSmsContacts(),
      this.getDingTalkConfig(),
      this.getWeChatConfig(),
      this.getSocketConfig(),
    ]).finally(() => {
      this.noticeLoading = false;
    })
  }
  private async getEmailContacts () {
    if (!this.showNoticeMapping.email) {
      return
    }
    const { result, error } = await toAsyncWait(NoticeApi.getEmailConfig())
    if (!error) {
      const data = result.data || {};
      const fields = ['mailHost', 'mailSender', 'mailSenderPwd', 'mailPort'];
      this.noticeMapping.email = {
        enable: !!data.enable,
        tenantEnable: !!data.tenantEnable,
        configStatus: fields.every((key: any) => data[key]),
      }
    }
  }
  private async getSmsContacts () {
    if (!this.showNoticeMapping.sms) {
      return
    }
    const { result, error } = await toAsyncWait(NoticeApi.getSmsConfig())
    if (!error) {
      const data = result.data || {};
      const template = (data.templates || [])[0] || {};
      data.smsNotifyType = template.smsNotifyType;
      data.smsTemplateId = template.smsTemplateId;
      data.smsTemplateContent = template.smsTemplateContent;
      data.smsSignName = template.smsSignName;
      const fields = ['smsKeyId', 'smsKeySecret', 'smsNotifyType', 'smsTemplateId', 'smsTemplateContent', 'smsSignName'];
      this.noticeMapping.sms = {
        enable: !!data.enable,
        tenantEnable: !!data.tenantEnable,
        configStatus: fields.every((key: any) => data[key]),
      }
    }
  }
  private async getDingTalkConfig () {
    if (!this.showNoticeMapping.dingtalk) {
      return
    }
    const { result, error } = await toAsyncWait(NoticeApi.getDingTalkConfig())
    if (!error) {
      const data = result.data || {};
      const fields = ['appkey', 'appsecret', 'dingAgentId'];
      this.noticeMapping.dingtalk = {
        enable: !!data.enable,
        tenantEnable: !!data.robotEnable || !!data.tenantEnable,
        configStatus: !!data.robotEnable || fields.every((key: any) => data[key]),
      }
    }
  }
  private async getWeChatConfig () {
    if (!this.showNoticeMapping.wechat) {
      return
    }
    const { result, error } = await toAsyncWait(NoticeApi.getWeChatConfig())
    if (!error) {
      const data = result.data || {};
      const fields = ['corpid', 'corpsecret', 'wechatAgentId'];
      this.noticeMapping.wechat = {
        enable: !!data.enable,
        tenantEnable: !!data.robotEnable || !!data.tenantEnable,
        configStatus: !!data.robotEnable || fields.every((key: any) => data[key]),
      }
    }
  }
  private async getSocketConfig () {
    if (!this.showNoticeMapping.socket) {
      return
    }
    const { result, error } = await toAsyncWait(NoticeApi.getSocketConfig())
    if (!error) {
      const data = result.data || {};
      const config = data.config || {};
      const fields = ['host', 'port'];
      this.noticeMapping.socket = {
        enable: !!data.enable,
        tenantEnable: !!data.tenantEnable,
        configStatus: fields.every((key: any) => config[key]),
        selectable: config.identify === 'sdcsh', // 是否可以下拉选择
      }
    }
  }

  // 获取通知接收者
  private receiverData: any = {
    loaded: false,
    email: [],
    sms: [],
    dingtalk: [],
    wechat: [],
  }
  private async getAllReceiverList () {
    const { result, error } = await toAsyncWait(SystemApi.getAllReceiverList({ enabled: true }));
    if (!error) {
      const list: any[] = result?.data || [];
      this.receiverData.email = list.filter(t => t.email).map(t => ({ label: t.rcvName, value: t.id }));
      this.receiverData.sms = list.filter(t => t.phone).map(t => ({ label: t.rcvName, value: t.id }));
      this.receiverData.dingtalk = list.filter(t => t.dingtalkUid || t.dingWebhook).map(t => ({
        label: t.rcvName, value: t.id,
        single: !!t.dingtalkUid, // 个人
        group: !!t.dingWebhook && !!t.dingSecret, // 群组
      }));
      this.receiverData.wechat = list.filter(t => t.wechatUid || t.wechatWebhook).map(t => ({
        label: t.rcvName, value: t.id,
        single: !!t.wechatUid, // 个人
        group: !!t.wechatWebhook, // 群组
      }));
      this.receiverData.loaded = true;
      this.filterReceiver()
    }
  }
  // 过滤不在列表中的接收者
  private filterReceiver () {
    const { email, sms, dingtalk, wechat } = this.receiverData
    const { emailList, smsList, dingtalkList, wechatList, socketList } = this.noticeForm
    this.noticeForm.emailList = emailList.filter((id: number) => email.some((t: any) => t.value === id))
    this.noticeForm.smsList = smsList.filter((id: number) => sms.some((t: any) => t.value === id))
    this.noticeForm.dingtalkList = dingtalkList.filter((id: number) => dingtalk.some((t: any) => t.value === id))
    this.noticeForm.wechatList = wechatList.filter((id: number) => wechat.some((t: any) => t.value === id))
    this.noticeForm.socketList = socketList.filter((id: number) => sms.some((t: any) => t.value === id))
  }

  // 添加字段
  private addFieldHandle (list: any, max?: number) {
    if (typeof max !== 'number' || list.length < max) {
      list.push({ key: '', value: '' })
    } else {
      this.$message.warning(i18n.t('modules.views.configManage.alarm.s_d6176fa1', { value0: max }) as string)
    }
  }
  // 删除字段
  private deleteFieldHandle (list: any, index: number) {
    if (list.length !== 1) {
      list.splice(index, 1)
    } else {
      list[0].key = ''
      list[0].value = ''
    }
  }

  // 测试通知
  private testNotice () {
    // 测试通知需要先保存
    if (!this.settingForm.webhookUrl) {
      this.$message.info(i18n.t('modules.views.configManage.alarm.s_9cd92041') as string)
      return
    }
    this.testLoading = true
    const filterValue = this.settingForm.webhookHeader.filter((t: any) => t.key.trim() && t.value.trim())
    const params = {
      ...this.settingForm,
        webhookHeader: JSON.stringify(filterValue.map((t: any) => `${t.key}:${t.value}`))
    }
    NoticeApi.testCustomWebhook(params)
      .then((rst: any) => {
        if (rst && rst.status === 200 && rst.message.toLocaleLowerCase() === 'success') {
          this.testLoading = false
          this.$message.success(i18n.t('modules.views.alarmCenter.notice.s_9db9a7e3') as string);
        } else {
          throw new Error(i18n.t('modules.views.configManage.alarm.s_9ca6a344') as string);
        }
      })
      .catch((err) => {
        this.testLoading = false
        if (err.message !== 'interrupt') {
          this.$message.error(err.message || i18n.t('modules.views.configManage.alarm.s_9ca6a344') as string);
        }
      })
  }
}
</script>

<style lang="scss" scoped>
.notice-wrap {
  min-height: 60px;
  margin-right: 16px;
  margin-bottom: 16px;
  line-height: 1.5;
  flex: 1;
}

.notice-item {
  line-height: 1;
  & + .notice-item {
    margin-top: 10px;
  }
  p {
    margin: 0;
    padding: 10px 0 5px 20px;
    line-height: 22px;
  }
  :deep(.el-checkbox) {
    line-height: 22px;
  }
  :deep(.el-input-group__prepend) {
    background-color: transparent;
    color: var(--color-text-primary);
  }
}

.w530 {
  width: 530px;
}

.w80 {
  width: 80px;
}

.route-link-btn{
  transition: color .3s ease;
  text-decoration: underline;

  &:hover{
    color: var(--color-text-link);
  }
}

.field-item {
  margin-bottom: 10px;
  position: relative;
  display: flex;
  .field-input {
    margin-right: 10px;
    flex: 1;
  }
  .field-icon {
    vertical-align: top;
    font-size: 18px;
    line-height: 32px;
    cursor: pointer;
    &.add {
      color: var(--color-primary);
    }
    &.delete {
      margin-left: 6px;
      color: var(--color-danger);
    }
  }
}
</style>
