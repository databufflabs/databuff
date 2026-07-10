<template>
  <div class="monitor-template-wrapper">
    <div class="monitor-template-content" v-loading="isLoading">
      <div class="flex-h mb-16">
        <el-input
          v-model="query"
          @change="queryChangeHandle"
          clearable
          size="small"
          maxlength="100"
          prefix-icon="db-icon-search"
          :placeholder="$t('modules.views.configManage.alarm.s_f50696a9')"
          class="search-input"
        />

        <el-upload
          action="/webapi/monitor/updatePresetMonitor"
          :headers="uploadHeaders"
          :before-upload='beforeUploadHandle'
          :on-success="uploadSuccessHandle"
          :on-error="uploadErrorHandle"
          accept=".txt,.csv"
          class="ml-10"
          :show-file-list='false'
          :with-credentials='true'
          v-if="hasAlarmManageAuthV1"
        >
          <el-button
            type="primary" plain size="small">
            <i class="el-icon-upload2"></i> {{ $t('modules.views.configManage.alarm.s_ef572785') }}</el-button>
        </el-upload>
      </div>

      <db-table
        ref="listTable"
        :data="monitorList"
        :columnConfig="columnConfig"
        :showTotal="false"
        :load="loadSubMonitorHandle"
        row-key="id" lazy>
        <template slot="column-service" slot-scope="{ row }">{{row.service}}</template>
        <template slot="column-ruleName" slot-scope="{ row }">{{row.ruleName}}</template>

        <el-table-column slot="suffix" :label="$t('modules.views.aiPlatform.experts.s_2b6bc0f2')" :width="150">
          <template slot-scope="{ row }">
            <div v-show='!row.switchLoading && !row.hasChildren && row.enabled'>
              <a v-if="!getEnableStatus" @click="editHandle(row, 'e')" class="action-btn">{{ $t('modules.views.hide.advancedConfig.s_95b351c8') }}</a>
              <a v-if="!getEnableStatus && hasAlarmManageAuthV1" @click="editHandle(row, 'c')" class="action-btn">{{ $t('modules.views.aiPlatform.chat.s_79d3abe9') }}</a>
              <a v-if="hasAlarmManageAuthV1" @click="deleteHandle(row)" class="action-btn">{{ $t('modules.views.hide.advancedConfig.s_2f4aaddd') }}</a>
            </div>
            <div v-if='!row.switchLoading && (row.hasChildren || !row.enabled)'>
              <el-switch :value='row.enabled' :disabled='!hasAlarmManageAuthV1' @change="changeMonitorStatus(row)"></el-switch>
            </div>
            <div v-if='row.switchLoading'>
              <span class="el-icon-loading font-16"></span>
            </div>
          </template>
        </el-table-column>
      </db-table>
    </div>
  </div>
</template>

<script lang="ts">
import { Component, Vue } from 'vue-property-decorator';
import i18n from '@/i18n';
import { v4 as uuidv4 } from 'uuid';
import MonitorApi from '@/api/monitor'
import { toAsyncWait } from '@/utils/common';
import { getRequestHeaders } from '@/utils/jsCookie';
import { sortBy } from 'lodash'
import { ALARM_DETAIL_PATH, buildAlarmDetailLocation } from '../alarm-routes';

@Component
export default class TemplateList extends Vue {
  get getEnableStatus () { // 是否开启管理域
    return this.$store.getters['User/getGroupEnabled']
  }

  private isLoading = false;

  private monitorList: any = [];

  private uploadHeaders = getRequestHeaders()

  private query = '';

  private columnConfig = [
    { field: 'service', label: i18n.t('modules.views.alarmCenter.alarm.s_8f3747c0') as string, labelKey: 'modules.views.alarmCenter.alarm.s_8f3747c0', slot: 'column-service', minWidth: 150 },
    { field: 'ruleName', label: i18n.t('modules.views.appMonitor.serviceDetail.s_87080256') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_87080256', slot: 'column-ruleName', minWidth: 300 },
  ]

  private created () {
    const { query } = this.$route.query
    if (query) {
      this.query = decodeURIComponent(query as string || '')
    }
    this.getServiceList()
  }

  private queryChangeHandle () {
    this.monitorList = []
    this.$nextTick(() => {
      this.getServiceList()
    })
    const _query: any = { ...this.$route.query, query: encodeURIComponent(this.query) }
    if (!this.query) {
      delete _query.query
    }
    this.$router.replace({ query: { ..._query }})
  }

  private async getServiceList () {
    this.isLoading = true
    const { result, error } = await toAsyncWait(MonitorApi.getPresetMonitorService({ name: this.query }))
    this.isLoading = false
    if (!error) {
      const data = result?.data || {};
      const _list = Object.entries(data).map((item: any[], idx: number) => ({
        id: uuidv4(),
        service: item[0],
        enabled: !!item[1],
        hasChildren: true,
        switchLoading: false,
      }));
      this.monitorList = sortBy(_list, 'service')
    }
  }

  // 编辑|复制规则
  private editHandle(row: any, mode: 'e' | 'c') {
    this.$router.push(buildAlarmDetailLocation(
      ALARM_DETAIL_PATH.ruleSetting,
      this.$route.query,
      { id: row.monitorId, mode },
    ));
  }

  // 删除规则
  private deleteHandle(row: any) {
    this.$confirm(i18n.t('modules.views.sysManage.org.s_bafb9cb6') as string, i18n.t('common.hint') as string, { type: 'warning' })
      .then(async () => {
        row.switchLoading = true
        const params = row.monitorIds.map((id: number) => ({ id }))
        const { result, error } = await toAsyncWait(MonitorApi.batchDelPresetMonitor(params))
        row.switchLoading = false
        if (!error) {
          row.enabled = false;
          row.monitorIds = [];
          row.monitorId = '';
          this.$message.success(i18n.t('modules.views.aiPlatform.experts.s_0007d170') as string);
          // 更新服务的状态
          const idx = this.monitorList.findIndex((item: any) => item.service === row.monitorObject)
          if (idx !== -1 && this.monitorList[idx].enabled) {
            this.monitorList[idx].enabled = false
          }
        } else {
          if (error.message !== 'interrupt') {
            this.$message.error(error.message);
          }
        }
      })
      .catch(() => null)
  }

  // 根据服务名称获取规则列表
  private async loadSubMonitorHandle (row: any, treeNode: any, resolve: any) {
    this.isLoading = true;
    const { service = '' } = row;
    if (!service) {
      resolve([])
      return;
    }
    const { result, error } = await toAsyncWait(MonitorApi.getPresetMonitorByService({ monitorObject: service, name: this.query }))
    if (!error) {
      const { data = [] } = result;
      const _list = data.map((item: any) => ({
        ...item,
        ruleName: item.ruleName || item.name,
        switchLoading: false,
        enabled: !!item.monitorIds?.length,
        monitorIds: item.monitorIds || [],
        monitorId: item.monitorIds[0] || '',
      }))
      resolve(_list);
      row.children = _list
    } else {
      if (error.message !== 'interrupt') {
        this.$message.error(error.message);
      }
    }
    this.isLoading = false;
  }

  // 启用/停用
  private async changeMonitorStatus (row: any) {
    if (row.hasChildren) {
      this.toggleStatusByService(row)
    } else {
      this.toggleStatusById(row)
    }
  }

  // 根据服务名称启停
  private async toggleStatusByService (row: any) {
    const params: any = {
      name: this.query,
      monitorObject: row.service,
      enabled: !row.enabled,
    }
    row.switchLoading = true;
    (row.children || []).forEach((item: any) => {
      item.switchLoading = true;
    });
    const { result, error } = await toAsyncWait(MonitorApi.batchOpenPresetMonitor(params))
    row.switchLoading = false;
    (row.children || []).forEach((item: any) => {
      item.switchLoading = false;
    });
    if (!error) {
      row.enabled = params.enabled;
      // 服务下的所有规则都需switch
      const data = result?.data || [];
      const idx = this.monitorList.findIndex((item: any) => item.service === params.monitorObject)
      if (idx !== -1) {
        (row.children || []).forEach((item: any) => {
          const _item = data.find((t: any) => t.id === item.id);
          const enabled = params.enabled && !!_item?.monitorIds?.length;
          item.enabled = enabled;
          item.monitorIds = enabled ? _item.monitorIds || [] : [];
          item.monitorId = enabled ? _item.monitorIds[0] : '';
        });
      }
    } else {
      if (error.message !== 'interrupt') {
        this.$message.error(error.message);
      }
    }
  }

  // 根据id开启
  private async toggleStatusById (row: any) {
    row.switchLoading = true
    const { result, error } = await toAsyncWait(MonitorApi.openPresetMonitor({ id: row.id }));
    row.switchLoading = false
    if (!error) {
      const data = result?.data || {};
      const enabled = !!data.monitorIds?.length;
      row.enabled = enabled;
      row.monitorIds = enabled ? data.monitorIds || [] : [];
      row.monitorId = enabled ? data.monitorIds[0] : '';
      // 更新服务的状态
      const idx = this.monitorList.findIndex((item: any) => item.service === row.monitorObject)
      if (idx !== -1) {
        const closeItems = this.monitorList[idx].children.filter((item: any) => !item.enabled)
        this.monitorList[idx].enabled = !closeItems.length
      }
    } else {
      if (error.message !== 'interrupt') {
        this.$message.error(error.message);
      }
    }
  }

  private beforeUploadHandle () {
    this.isLoading = true
  }

  private uploadSuccessHandle(res: any) {
    if (res.status === 200 && res.message === 'SUCCESS') {
      this.$message.success(i18n.t('modules.views.appMonitor.service.s_55aa6366') as string);
      // 刷新列表
      this.getServiceList()
    } else {
      if (res.message !== 'interrupt') {
        this.$message.error(i18n.t('modules.views.configManage.alarm.s_1af790b6', { value0: res.message }) as string);
      }
    }
    this.isLoading = false
  }

  private uploadErrorHandle(error: any) {
    // console.log(error)
    this.$message.error(i18n.t('modules.views.configManage.alarm.s_3a845c4d') as string);
    this.isLoading = false
  }
}
</script>

<style lang="scss">
.monitor-template-wrapper {
  flex: 1;
  padding: 16px;
  height: 100%;
  display: flex;
  flex-direction: column;
  color: var(--color-text-regular);
  overflow: auto;

  .monitor-template-content {
    flex: 1;
    min-height: 300px;
    padding: 20px;
    border-radius: 4px;
    background-color: var(--bg-color);
    display: flex;
    flex-direction: column;

    .action-btn {
      display: inline-block;
      margin-right: 10px;
      color: var(--color-text-link);
      &:last-child {
        margin-right: 0;
      }
    }
  }
}
</style>
