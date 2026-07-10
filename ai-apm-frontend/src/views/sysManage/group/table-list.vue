<template>
  <div class="manage-group-table-cont">
    <div class="flex-h flex-none">
      <el-input
        v-model="nameQuery"
        :disabled="isLoading"
        @change="searchChangeHandle"
        clearable size="small"
        maxlength="100"
        prefix-icon="db-icon-search"
        :placeholder="$t('modules.views.sysManage.group.s_4e97ca9e')"
      />

      <div class="flex-none ml-10">
        <el-button
          @click="batchDeleteHandle"
          :disabled="!selection.length || isLoading"
          :type="selection.length ? 'primary' : ''"
          plain size="small">{{ $t('modules.views.configInstall.dataAccess.s_7fb62b30') }}</el-button>
        <el-button
          @click="getTableList"
          :disabled="isLoading"
          type="primary" plain size="small">
          <i class="db-icon-refresh"></i> {{ $t('modules.views.aiPlatform.chat.s_694fc5ef') }}</el-button>
        <el-button
          @click="showManageModalHandle()"
          :disabled="isLoading"
          type="primary" size="small">
          <i class="db-icon-add"></i> {{ $t('modules.views.configManage.alarm.s_26bb8418') }}</el-button>
      </div>
    </div>

    <db-table
      ref="listTable"
      :data="tableList"
      :total="tableList.length"
      :columnConfig="columnConfig"
      :showSelection="true"
      :selectableFunc="selectableFunc"
      @selection-change="selectChangeHandle"
      v-loading="isLoading"
      class="manage-group-table">
      <template slot="dataSource" slot-scope="{ row }">
        <div size="small">{{ row.dataSource ? $t('modules.views.sysManage.group.s_ca8843d3') : $t('modules.views.sysManage.group.s_2dbb3e25')  }}</div>
      </template>
      
      <el-table-column slot="suffix" :label="$t('modules.views.aiPlatform.experts.s_2b6bc0f2')" width="200">
        <template slot-scope="{ row }">
          <span v-if='row.dataSource' @click="changeCustomHandle(row)" class="blue cp">{{ $t('modules.views.sysManage.group.s_4b1aebb8') }}</span>
          <template v-else>
            <span @click.stop="showManageModalHandle(row)" :class='["mr-15", row.dataSource ? "describe cn" : "blue cp"]'>{{ $t('modules.views.hide.advancedConfig.s_95b351c8') }}</span>
            <span @click.stop="showConfigHandle(row)" :class='["mr-15", row.dataSource ? "describe cn" : "blue cp"]'>{{ $t('modules.views.sysManage.group.s_15a45c61') }}</span>
            <span @click.stop="deleteHandle(row)" :class='[row.dataSource ? "describe cn" : "blue cp"]'>{{ $t('modules.views.hide.advancedConfig.s_2f4aaddd') }}</span>
          </template>
        </template>
      </el-table-column>
    </db-table>

    <manage-modal :showModel="showManageModal" :title='currManageRow ? $t('modules.views.sysManage.group.s_c0b64420') : $t('modules.views.sysManage.group.s_0629752a')'
      :curr='currManageRow'
      @on-close='closeManageModalHandle' />

    <config-dialog :showModel="showConfigDialog" :title="$t('modules.views.sysManage.group.s_15a45c61')"
      :curr='currConfigRow'
      @on-close='closeConfigDialogHandle' />
  </div>
</template>

<script lang='ts'>import i18n from '@/i18n';

import { Vue, Component } from 'vue-property-decorator'
import { getAgi, setAgi } from '@/utils/jsCookie'
import { toAsyncWait } from '@/utils/common'
import GroupApi from '@/api/group'
import ManageModal from './manage-modal.vue'
import ConfigDialog from './config-dialog.vue'

@Component({
  components: {
    ManageModal,
    ConfigDialog,
  }
})
export default class GroupTableList extends Vue {
  public $refs!: {
    listTable: any,
  }

  private nameQuery = ''
  private isLoading = false
  private tableList: any[] = []
  private selection: any[] = []

  private columnConfig = [
    { field: 'name', label: i18n.t('modules.views.sysManage.group.s_eab5b13a') as string, labelKey: 'modules.views.sysManage.group.s_eab5b13a', minWidth: 100 },
    { field: 'description', label: i18n.t('modules.views.configInstall.plugin.s_3bdd08ad') as string, labelKey: 'modules.views.configInstall.plugin.s_3bdd08ad', minWidth: 150 },
    { field: 'dataSource', label: i18n.t('modules.views.sysManage.group.s_76e1a31b') as string, labelKey: 'modules.views.sysManage.group.s_76e1a31b', slot: 'dataSource', minWidth: 150 },
  ]

  // 新增/编辑弹窗
  private showManageModal = false
  private currManageRow: any = null

  // 配置规则弹窗
  private showConfigDialog = false
  private currConfigRow: any = null

  private mounted () {
    const { name } = this.$route.query
    this.nameQuery = decodeURIComponent(name as string || '')
    this.getTableList()
  }

  private async getTableList () {
    const params = { name: this.nameQuery }
    this.isLoading = true;
    const { result, error } = await toAsyncWait(GroupApi.getGroupList(params))
    this.isLoading = false;
    if (!error) {
      const { data = [] } = result || {};
      this.tableList = data;
      // 如果已选择的还存在，则恢复选中
      if (this.selection.length) {
        this.$nextTick(() => {
          this.tableList.forEach((item) => {
            const selected = !!this.selection.find((t) => t.id === item.id)
            if (selected) {
              this.$refs.listTable.toggleRowSelection(item, true)
            }
          })
        })
      }
    } else if (error.message !== 'interrupt') {
      this.$message.error(i18n.t('modules.views.appMonitor.serviceFlow.s_e05c1ca3') as string)
    }
  }

  public resize () {
    this.$nextTick(() => {
      this.$refs.listTable.getHeightHandle()
    })
  }

  private searchChangeHandle () {
    this.getTableList();
    const _query: any = { ...this.$route.query, name: encodeURIComponent(this.nameQuery || '') }
    if (!_query.name) {
      delete _query.name
    }
    this.$router.replace({ query: { ..._query }})
  }

  private selectChangeHandle (selection: any) {
    this.selection = [...selection]
  }

  private async batchDeleteHandle () {
    this.$confirm(i18n.t('modules.views.sysManage.org.s_bafb9cb6') as string, i18n.t('common.hint') as string, { type: 'warning' })
      .then(async () => {
        const ids = this.selection.map((i) => i.id)
        if (ids.length) {
          this.isLoading = true
          const { result, error } = await toAsyncWait(GroupApi.deleteGroup({ ids }))
          if (!error) {
            this.$message.success(i18n.t('modules.views.aiPlatform.experts.s_0007d170') as string)
            this.getTableList()
            this.selection = []
            this.$emit('on-refresh')
          } else if (error.message !== 'interrupt') {
            this.$message.error(error.message || i18n.t('modules.views.sysManage.group.s_acf0664a') as string);
          }
          this.isLoading = false
        }
      })
      .catch(() => null)
  }

  private showManageModalHandle (curr?: any) {
    if (curr?.dataSource) {
      return;
    }
    if (!curr) {
      this.currManageRow = null
    } else {
      this.currManageRow = curr
    }
    this.showManageModal = true
  }
  // 关闭管理弹窗
  private closeManageModalHandle ({ refresh }: { refresh?: boolean }) {
    this.showManageModal = false
    this.currManageRow = null
    if (refresh && typeof refresh === 'boolean') {
      this.getTableList()
      // 更新当前用户的管理域列表
      this.$store.dispatch('User/findRoleGroupByUser');
    }
  }

  private showConfigHandle (curr?: any) {
    if (curr?.dataSource) {
      return;
    }
    if (!curr) {
      this.currConfigRow = null
    } else {
      this.currConfigRow = curr
    }
    this.showConfigDialog = true
  }
  private closeConfigDialogHandle () {
    this.showConfigDialog = false
    this.currConfigRow = null
  }

  // 删除管理域
  private async deleteHandle (curr?: any) {
    if (curr && !curr.dataSource) {
      this.$confirm(i18n.t('modules.views.sysManage.group.s_6fef542e') as string, i18n.t('common.hint') as string, { type: 'warning', dangerouslyUseHTMLString: true })
        .then(async () => {
          this.isLoading = true
          const { result, error } = await toAsyncWait(GroupApi.deleteGroup({ ids: [curr.id] }))
          if (!error) {
            this.$message.success(i18n.t('modules.views.aiPlatform.experts.s_0007d170') as string);
            if (getAgi() === String(curr.id)) {
              // 删除了当前的管理域，提示重新选择
              this.$alert(i18n.t('modules.views.sysManage.group.s_8eb72260') as string, i18n.t('modules.views.sysManage.group.s_02d9819d') as string);
              setAgi('')
            }
            this.getTableList();
            // 更新当前用户的管理域列表
            await this.$store.dispatch('User/findRoleGroupByUser');
          } else {
            this.$message.error(i18n.t('modules.views.sysManage.group.s_acf0664a') as string)
          }
          this.isLoading = false
        })
        .catch(() => null)
    }
  }

  // 是否可以选中
  private selectableFunc (row: any) {
    return row.dataSource !== 1
  }

  private changeCustomHandle (row: any) {
    this.$confirm(i18n.t('modules.views.sysManage.group.s_b504ae59') as string, i18n.t('common.hint') as string, { type: 'warning', dangerouslyUseHTMLString: true })
      .then(async () => {
        this.isLoading = true
        const { result, error } = await toAsyncWait(GroupApi.setCustomGroup({ id: row.id, dataSource: 0 }))
        if (!error) {
          this.$message.success(i18n.t('modules.views.configManage.entity.s_33130f5c') as string);
          row.dataSource = 0
        } else {
          this.$message.error(i18n.t('modules.views.configInstall.dataAccess.s_5fa802be') as string)
        }
        this.isLoading = false
      })
      .catch(() => null)
  }
}
</script>

<style scoped lang='scss'>
.manage-group-table-cont {
  display: flex;
  flex-direction: column;
  overflow: hidden;

  .manage-group-table {
    flex: 1;
  }

  :deep(.el-table__header th.el-table__cell .el-checkbox) {
    display: none;
  }
}
</style>
