<template>
  <div>
    <db-table
      ref="listTable"
      :queryApi="queryApi"
      :queryParams="getQueryParams"
      :timeMode="false"
      :autoRefresh="false"
      showSetting
      tableKey="CONFIG_ALARM_RULE_LIST"
      :columnConfig="columnConfig"
      :formatFunc="formatFunc"
      :showSelection="true"
      :selectableFunc="selectableFunc"
      @selection-change="selectionChangeHandle"
      @sort-change="tableRefresh"
      @on-fetch-end="tableFetchEndHandle">
      <template slot="column-ruleName" slot-scope="{ row }">
        <el-popover v-if="(row.closeMetrics || []).length" trigger="hover" placement="top">
          <div style='font-size: 13px;'>{{ $t('modules.views.appMonitor.serviceDetail.s_9c7f996e', { value0: row.closeMetrics.join('、') }) }}</div>
          <span slot="reference" class="el-icon-warning mr-5 red" />
        </el-popover>
        <span>{{ row.ruleName || '-' }}</span>
      </template>
      <template slot="column-type" slot-scope="{ row }">
        <template v-if="row._type.length">
          <span v-for="(t, i) in row._type" :key="i">
            {{ i === 0 ? '' : ',' }}
            {{ t | MonitorMethodFilter }}
          </span>
        </template>
        <template v-else>-</template>
      </template>
      <template slot="column-enabled" slot-scope="{ row }">
        <el-switch
          v-model="row.enabled"
          :disabled="!row._hasAlarmManageAuth || row.loading"
          @change="toggleEnableHandle(row)">
        </el-switch>
      </template>

      <el-table-column slot="suffix" :label="$t('modules.views.aiPlatform.experts.s_2b6bc0f2')" :width="150">
        <template slot-scope="{ row }">
          <span
            @click.stop="viewSettingHandle(row, 'e')"
            :class="{ 'action-disabled': row.loading }"
            class="blue cp mr-15">{{ row._hasAlarmManageAuth ? $t('modules.views.configInstall.dataAccess.s_95b351c8') : $t('modules.views.configManage.alarm.s_607e7a4f')  }}</span>
          <span v-if="row._hasAlarmManageAuth"
            @click.stop="viewSettingHandle(row, 'c')"
            :class="{ 'action-disabled': row.loading }"
            class="blue cp mr-15">{{ $t('modules.views.aiPlatform.chat.s_79d3abe9') }}</span>
          <span v-if="row._hasAlarmManageAuth"
            @click.stop="deleteHandle(row)"
            :class="{ 'action-disabled': row.loading }"
            class="blue cp">{{ $t('modules.views.hide.advancedConfig.s_2f4aaddd') }}</span>
        </template>
      </el-table-column>
    </db-table>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop } from 'vue-property-decorator';
import i18n from '@/i18n';
import { namespace } from 'vuex-class';
import { toAsyncWait } from '@/utils/common';
import MonitorApi from '@/api/monitor';
import { ALARM_DETAIL_PATH, buildAlarmDetailLocation } from '../alarm-routes';

const UserModel = namespace('User');

@Component
export default class TableList extends Vue {
  @UserModel.Getter('getGroupMapping') private groupMapping!: any;
  @Prop({ default: false }) private isSystemRule!: boolean;
  @Prop({ default: () => ({}) }) private query!: any;

  public $refs!: {
    listTable: any
  }

  get getQueryParams () {
    const params = { ...this.query }
    Object.entries(params).forEach(([key, value]) => {
      if (value === '') {
        delete params[key]
      }
    })
    return params;
  }

  get getAccountRoleName () {
    return this.$store.getters['User/getUserInfo']?.currentRole?.roleName || ''
  }

  get getGroupEnabled () {
    return this.$store.getters['User/getGroupEnabled'];
  }

  get getCurrGroup () {
    return this.$store.getters['User/getCurrGroup'];
  }

  private queryApi = MonitorApi.getRuleList
  private columnConfig = [
    { field: 'ruleName', label: i18n.t('modules.views.appMonitor.serviceDetail.s_87080256') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_87080256', slot: 'column-ruleName', minWidth: 200 },
    { field: 'id', label: i18n.t('modules.views.appMonitor.serviceDetail.s_779ee11c') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_779ee11c', minWidth: 80, defaultShow: false },
    { field: 'domainName', label: i18n.t('modules.views.alarmCenter.alarm.s_f9d4e244') as string, labelKey: 'modules.views.alarmCenter.alarm.s_f9d4e244', minWidth: 120 },
    { field: 'classification', label: i18n.t('modules.views.appMonitor.serviceDetail.s_986329a3') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_986329a3', unit: 'monitorType', minWidth: 90 },
    { field: 'type', label: i18n.t('modules.views.appMonitor.serviceDetail.s_2b87411b') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_2b87411b', slot: 'column-type', minWidth: 80 },
    { field: 'updateTime', label: i18n.t('modules.views.appMonitor.serviceDetail.s_921453aa') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_921453aa', unit: 'time', sortable: true, defaultSort: 'desc', minWidth: 145 },
    { field: 'editor', label: i18n.t('modules.views.appMonitor.serviceDetail.s_1e1bbd89') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_1e1bbd89', minWidth: 100 },
    { field: 'createTime', label: i18n.t('modules.views.appMonitor.serviceDetail.s_eca37cb0') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_eca37cb0', unit: 'time', sortable: true, minWidth: 145, defaultShow: false },
    { field: 'creator', label: i18n.t('modules.views.appMonitor.serviceDetail.s_f4cc0634') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_f4cc0634', minWidth: 100, defaultShow: false },
    { field: 'enabled', label: i18n.t('modules.views.appMonitor.serviceDetail.s_2b82bf9a') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_2b82bf9a', slot: 'column-enabled', minWidth: 80 },
  ]

  private created () {
    if (!this.$store.getters['User/getGroupEnabled']) {
      this.columnConfig = this.columnConfig.filter(t => t.field !== 'domainName')
    }
    if (this.isSystemRule) {
      this.queryApi = MonitorApi.getSystemRuleList
    }
  }

  private formatFunc (data: any) {
    return data.map((t: any) => ({
      ...t,
      domainName: this.groupMapping[t.gid] || '',
      _type: t.type ? t.type.split(',') : [],
      enabled: t.enabled === true,
      _hasAlarmManageAuth: this.hasAlarmManageAuth(t),
    }));
  }
  private tableRefresh () {
    (this.$refs.listTable as any)?.refresh()
  }
  private tableFetchEndHandle (data: any[], total: number, params: any) {
    this.sortQuery.sortOrder = params.sortOrder
    this.sortQuery.sortField = params.sortField
  }

  public async getData () {
    this.tableRefresh()
  }

  // 列表选中
  private selection: any[] = []
  private selectionChangeHandle (selection: any[]) {
    this.selection = [...selection]
    this.$emit('selection-change', [...selection])
  }
  private selectableFunc (row: any) {
    return row._hasAlarmManageAuth
  }

  // 编辑|复制
  public viewSettingHandle(row?: any, mode?: 'e' | 'c') {
    const path = this.isSystemRule
      ? ALARM_DETAIL_PATH.systemRuleSetting
      : ALARM_DETAIL_PATH.ruleSetting
    this.$router.push(buildAlarmDetailLocation(
      path,
      this.$route.query,
      row ? { id: row.id, mode: mode || 'e' } : undefined,
    ));
  }

  // 启停
  private async toggleEnableHandle (row: any) {
    if (row.loading) {
      return;
    }
    const params = {
      ids: [row.id],
      enabled: !!row.enabled,
    }
    this.$set(row, 'loading', true);
    const fetchUrl = this.isSystemRule ? 'toggleSystemRuleEnable' : 'toggleRuleEnable'
    const { result, error } = await toAsyncWait(MonitorApi[fetchUrl](params));
    this.$set(row, 'loading', false);
    if (!error) {
      this.$message.success(params.enabled ? i18n.t('modules.views.appMonitor.serviceDetail.s_c0cd850f') as string : i18n.t('modules.views.appMonitor.serviceDetail.s_8b4e6853') as string);
      this.$set(row, 'enabled', params.enabled);
    } else if (error.message !== 'interrupt') {
      this.$message.error(error.message);
    }
  }
  // 批量启停
  public async batchToggleEnableHandle (enabled: boolean) {
    const params = {
      ids: this.selection.map(t => t.id),
      enabled: !!enabled,
    }
    const loadingMask = this.getFullScreenMask(i18n.t('modules.views.configManage.alarm.s_1b275572', { value0: !!enabled ? i18n.t('modules.views.configManage.alarm.s_7854b52a') as string : i18n.t('modules.views.configManage.alarm.s_5c56a889') as string }) as string);
    const fetchUrl = this.isSystemRule ? 'toggleSystemRuleEnable' : 'toggleRuleEnable'
    const { result, error } = await toAsyncWait(MonitorApi[fetchUrl](params));
    loadingMask.close();
    if (!error) {
      this.$message.success(!!enabled ? i18n.t('modules.views.configInstall.dataAccess.s_20ea1267') as string : i18n.t('modules.views.configInstall.dataAccess.s_10fbec45') as string);
      params.ids.forEach(id => {
        const item = this.selection.find(t => t.id === id)
        if (item) {
          this.$set(item, 'enabled', params.enabled)
        }
      })
    } else if (error.message !== 'interrupt') {
      this.$message.error(error.message);
    }
  }

  // 删除
  private deleteHandle(row: any) {
    if (row.loading) {
      return;
    }
    this.$confirm(i18n.t('modules.views.sysManage.org.s_bafb9cb6') as string, i18n.t('common.hint') as string, { type: 'warning' })
      .then(async () => {
        this.$set(row, 'loading', true);
        const fetchUrl = this.isSystemRule ? 'batchDelSystemMonitor' : 'batchDelMonitor'
        const { result, error } = await toAsyncWait(MonitorApi[fetchUrl]([{ id: row.id }]));
        this.$set(row, 'loading', false);
        if (!error) {
          this.$message.success(i18n.t('modules.views.aiPlatform.experts.s_0007d170') as string);
          this.tableRefresh()
        } else if (error.message !== 'interrupt') {
          this.$message.error(error.message);
        }
      })
      .catch(() => null)
  }
  // 批量删除
  public batchDeleteHandle() {
    this.$confirm(i18n.t('modules.views.configManage.alarm.s_e9db9d58') as string, i18n.t('modules.views.configInstall.dataAccess.s_7fb62b30') as string, { type: 'warning', dangerouslyUseHTMLString: true })
      .then(async () => {
        const loadingMask = this.getFullScreenMask(i18n.t('modules.views.configManage.alarm.s_08144a21') as string)
        const ids = this.selection.map(t => ({ id: t.id }))
        const fetchUrl = this.isSystemRule ? 'batchDelSystemMonitor' : 'batchDelMonitor'
        const { result, error } = await toAsyncWait(MonitorApi[fetchUrl](ids));
        loadingMask.close()
        if (!error) {
          this.$message.success(i18n.t('modules.views.configInstall.dataAccess.s_eedd70c6') as string);
          this.tableRefresh()
        } else if (error.message !== 'interrupt') {
          this.$message.error(error.message);
        }
      })
      .catch(() => null)
  }

  private sortQuery: any = {
    sortField: '',
    sortOrder: '',
  }

  // 全屏遮罩层
  private getFullScreenMask (text?: string) {
    return this.$loading({
      lock: true,
      text,
    });
  }
}
</script>

<style lang="scss" scoped>
.action-disabled {
  color: var(--color-text-secondary);
  pointer-events: none;
}
</style>
