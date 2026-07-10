<template>
  <div ref="tableWrap" class="table-wrap" :style="height ? `height:${height}` : ''">
    <el-table
      :data="tableList"
      :height="tableHeight"
      :empty-text="!isLoading ? $t('modules.views.appMonitor.errorDetail.s_21efd88b') : ' '"
      highlight-current-row size="small"
      tooltip-effect="light"
      class="table"
    >
      <el-table-column :label="$t('modules.views.configManage.entity.s_c9cd3922')" :min-width="!isRecognition ? 250 : 500" show-overflow-tooltip>
        <template slot-scope="{ row }">{{ row.describe || '-' }}</template>
      </el-table-column>

      <el-table-column v-if="!isRecognition" :label="$t('modules.views.configManage.entity.s_96110929')" :min-width="250" show-overflow-tooltip>
        <template slot-scope="{ row }">{{ row.hostDesc || '-' }}</template>
      </el-table-column>

      <el-table-column :label="$t('modules.views.configManage.entity.s_9418d550')" :min-width="80" show-overflow-tooltip>
        <template slot-scope="{ row }">{{ row.isPreset ? $t('modules.components.s_0a60ac8f') : $t('modules.components.s_c9744f45')  }}</template>
      </el-table-column>

      <el-table-column :label="$t('modules.views.configManage.alarm.s_2b82bf9a')" :min-width="80" show-overflow-tooltip>
        <template slot-scope="{ row }">
          <el-switch
            v-model="row.status"
            :disabled="row.loading"
            @change="toggleEnableHandle(row)">
          </el-switch>
        </template>
      </el-table-column>

      <el-table-column key="actions" :label="$t('modules.views.aiPlatform.experts.s_2b6bc0f2')" :min-width="100">
        <template slot-scope="{ row }">
          <span
            @click.stop="editHandle(row)"
            :class="{ 'action-disabled': row.isPreset || row.loading }"
            class="blue cp mr-15">{{ $t('modules.views.hide.advancedConfig.s_95b351c8') }}</span>
          <span v-if='hasEntityManageAuth'
            @click.stop="deleteHandle(row)"
            :class="{ 'action-disabled': row.isPreset || row.loading }"
            class="blue cp">{{ $t('modules.views.hide.advancedConfig.s_2f4aaddd') }}</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import i18n from '@/i18n';
import { toAsyncWait } from '@/utils/common';
import ProcessApi from '@/api/process';

@Component
export default class TableList extends Vue {
  @Prop({ default: false }) private isLoading!: boolean
  @Prop({ default: () => [] }) private tableList!: any[]
  @Prop({ default: false }) private isRecognition!: boolean
  @Prop() private height!: number

  public $refs!: {
    tableWrap: HTMLDivElement,
  }

  private tableHeight: number = 101;

  @Watch('height', { immediate: true })
  private onTableListChange () {
    this.getTableHeight()
  }

  private mounted () {
    this.getTableHeight()
    window.addEventListener('resize', this.getTableHeight);
  }

  private beforeDestroy () {
    window.removeEventListener('resize', this.getTableHeight);
  }

  // 启停
  private async toggleEnableHandle (row: any) {
    if (row.loading) {
      return;
    }
    const params = {
      id: row.id,
      status: +row.status,
    }
    const fetchUrl = this.isRecognition ? 'toggleIdentifyRuleEnable' : 'toggleCollectRuleEnable'
    this.$set(row, 'loading', true);
    const { result, error } = await toAsyncWait(ProcessApi[fetchUrl](params))
    this.$set(row, 'loading', false);
    if (!error) {
      this.$message.success(params.status ? i18n.t('modules.views.appMonitor.serviceDetail.s_c0cd850f') as string : i18n.t('modules.views.appMonitor.serviceDetail.s_8b4e6853') as string);
      this.$set(row, 'status', !!params.status);
    } else if (error.message !== 'interrupt') {
      this.$message.error(error.message);
    }
  }

  // 编辑
  private editHandle (row: any) {
    this.$emit('on-edit', row);
  }

  // 删除
  private deleteHandle(row: any) {
    if (row.loading) {
      return;
    }
    this.$confirm(i18n.t('modules.views.sysManage.org.s_bafb9cb6') as string, i18n.t('common.hint') as string, { type: 'warning' })
      .then(async () => {
        const fetchUrl = this.isRecognition ? 'deleteIdentifyRule' : 'deleteCollectRule'
        this.$set(row, 'loading', true);
        const { result, error } = await toAsyncWait(ProcessApi[fetchUrl]({ id: row.id }));
        this.$set(row, 'loading', false);
        if (!error) {
          this.$message.success(i18n.t('modules.views.aiPlatform.experts.s_0007d170') as string);
          this.$emit('on-delete', row);
        } else if (error.message !== 'interrupt') {
          this.$message.error(error.message);
        }
      })
      .catch(() => null)
  }

  private getTableHeight () {
    this.$nextTick(() => {
      const { clientHeight } = this.$refs.tableWrap
      this.tableHeight = clientHeight
    })
  }
}
</script>

<style lang="scss" scoped>
.table-wrap {
  min-height: 101px;

  .action-disabled {
    color: var(--color-text-secondary);
    pointer-events: none;
  }
}
</style>
