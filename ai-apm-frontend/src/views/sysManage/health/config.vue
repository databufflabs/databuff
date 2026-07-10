<template>
  <div class="cont ovy-auto">
    <div class="font-14 fw-500 mb-10">{{ $t('modules.views.sysManage.health.s_2ccdef32') }}</div>
    <div class="config-item mb-20">
      <div class="describe mb-10">{{ $t('modules.views.sysManage.health.s_4639d014', { value0: typeName }) }}</div>

      <div class="flex-h mb-10">
        <el-button @click="handleAddRule" size="small" type="primary" class="mr-10">{{ $t('modules.views.configManage.llm.s_66ab5e9f') }}</el-button>
        <el-input v-model='queryName' size="small" @change='handleSearch' :placeholder="$t('modules.views.sysManage.health.s_72b6f48e')" clearable></el-input>
      </div>

      <div v-loading='tableLoading' class="mh-300">
        <db-table :scrollMode='false' :columnConfig="columnConfig" :data="tableList" :showTotal="false" :tableSortable='true'
          @drop='handleSorted'  class="flex-1">

          <template slot="prefix" >
            <el-table-column :label="$t('modules.views.sysManage.health.s_ee8ecb9e')"  width="80" align="center">
              <div class="" :class="[ queryName ? 'is-disabled cn' : 'table-handler cm' ]">
                <i class="el-icon-more rotate-90"></i>
                <i class="el-icon-more rotate-90 offset-5"></i>
              </div>
            </el-table-column>
          </template>
          
          <template slot="enabled" slot-scope="{ row }">
            <el-switch :value="row.enabled" :active-value="1" :inactive-value="0" @change="toggleRowEnableHandle(row)"></el-switch>
          </template>

          <template slot="suffix">
            <el-table-column :label="$t('modules.views.aiPlatform.experts.s_2b6bc0f2')" width="180">
              <template slot-scope="{ row }">
                <span @click="editRule(row)" class="blue cp">{{ $t('modules.views.hide.advancedConfig.s_95b351c8') }}</span>
                <span @click="deleteRule(row)" class="blue cp ml-10">{{ $t('modules.views.hide.advancedConfig.s_2f4aaddd') }}</span>
              </template>
            </el-table-column>
          </template>
          
        </db-table>
      </div>
    </div>
    <div class="font-14 fw-500 mb-10">{{ $t('modules.views.sysManage.health.s_57caea06') }}</div>
    <div class="mb-20">
      <div class="p-16 border-1 br-4 pos-r">
        <div v-show='!defaultEditMode' class="pos-a edit-btn">
          <span @click="editDefaultRule" class="blue cp">{{ $t('modules.views.hide.advancedConfig.s_95b351c8') }}</span>
        </div>
        <MetricList v-if='defaultEditMode' :detail="ruleDetail" :cid='getCid' key='defaultRules' ref='defaultRulesComp' :typeName="typeName" :type="type" :showCondition="false" :showDescribe="false"
          @on-save='saveDefaultHandle' @on-cancel='cancelDefaultHandle' class="mt-10" />
      </div>
    </div>
    <div class="font-14 fw-500 mb-10">{{ $t('modules.views.sysManage.health.s_72bf8bc5') }}</div>
    <div class="mb-20"> 
      <div class="describe mb-10">
        <span>{{ $t('modules.views.sysManage.health.s_7de6bd02') }}</span>
      </div>
      <div>
        <i class="db-icon db-icon-face-smile green mr-6"></i>
        <span class="mr-10">{{ $t('modules.views.sysManage.health.s_6277a44d') }}</span>
        <span class="mr-10">>=</span>
        <el-input-number v-model="rate.healthy" v-if='colorEditMode' size="small" controls-position="right" :min="0" :max="100" :style="{ width: '90px' }" class="mr-15"></el-input-number>
        <span v-if='!colorEditMode' class="mr-15 fw-500">{{ rate.healthy }}</span>
        <span class="mr-10">></span>
        <i class="db-icon db-icon-face-normal yellow mr-6"></i>
        <span class="mr-0">{{ $t('modules.views.sysManage.health.s_dfbad486') }}</span>
        <span class="mr-10">>=</span>
        <el-input-number v-model="rate.slightAbnormal" v-if='colorEditMode' size="small" controls-position="right" :min="0" :max="100" :style="{ width: '90px' }" class="mr-15"></el-input-number>
        <span v-if='!colorEditMode' class="mr-15 fw-500">{{ rate.slightAbnormal }}</span>
        <span class="mr-10">></span>
        <i class="db-icon db-icon-face-sad red mr-6"></i>
        <span class="mr-6">{{ $t('modules.views.sysManage.health.s_02f27b7f') }}</span>
        <!-- <el-input-number v-model="rate.seriousAbnormal" v-if='colorEditMode' size="small" controls-position="right" :min="0" :max="100" :style="{ width: '90px' }" class="mr-15"></el-input-number>
        <span v-if='!colorEditMode' class="mr-15">{{ rate.seriousAbnormal }}</span> -->
        <span v-if='!colorEditMode' @click="colorEditMode = true" class="blue cp ml-20">{{ $t('modules.views.hide.advancedConfig.s_95b351c8') }}</span>
      </div>
      <div>
        <div v-if='colorEditMode' class="mt-10">
          <el-button size="small" type="primary" :disabled="tableLoading" :loading="tableLoading" class="mr-10" @click="colorSaveHandle">{{ $t('modules.views.configInstall.plugin.s_be5fbbe3') }}</el-button>
          <el-button size="small" :disabled="tableLoading" @click="colorCancelHandle">{{ $t('modules.views.aiPlatform.experts.s_625fb26b') }}</el-button>
        </div>
      </div>
    </div>

    <el-drawer :visible.sync="drawerVisible" :before-close='handleCloseDrawer' :title="$t('modules.views.sysManage.health.s_2ccdef32')" :size="850" custom-class="no-padding">
      <MetricList v-if='drawerVisible' :cid='getCid' key='configRules' :detail="ruleDetail" :typeName="typeName" :type="type"
        @on-save='handleSaveRule' @on-cancel='handleCancelRule' class="p-16" />
    </el-drawer>
    
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator'
import { StringIsEmpty, toAsyncWait, waitForSomeSecond } from '@/utils/common';
import i18n from '@/i18n';
import MetricList from './metric-list.vue';
import HealthApi from './health.api'
import { v4 as uuidv4 } from 'uuid'

@Component({
  components: {
    MetricList,
  }
})
export default class HealthComp extends Vue {
  @Prop({}) private typeName!: string;
  @Prop({}) private type!: string;

  @Watch('type', { immediate: true })
  private async onTypeChange (val: string) {
    this.tableList = [];
    this.defaultEditMode = false;
    this.colorEditMode = false;
    this.tableLoading = true
    const { error, result } = await toAsyncWait(HealthApi.gethealthConfig({ type: val }))
    if (!error) {
      this.formatDetail(result?.data || {});
    } else if (error.message !== 'interrupt') {
      this.$message.error(error?.message || i18n.t('modules.views.sysManage.health.s_a8a23722') as string);
    }
    this.tableLoading = false
  }

  get getCid () {
    return this.configDetail?.id || '';
  }

  private queryName = '';
  private tableLoading = false;
  private drawerVisible = false;
  private configDetail: any = null;
  private ruleDetail: any = null;

  private defaultEditMode = false;
  private colorEditMode = false;

  private columnConfig = [
    { field: 'describe', label: i18n.t('modules.views.configInstall.plugin.s_3bdd08ad') as string, labelKey: 'modules.views.configInstall.plugin.s_3bdd08ad', minWidth: 100 },
    { field: 'enabled', label: i18n.t('modules.views.sysManage.health.s_7854b52a') as string, labelKey: 'modules.views.aiPlatform.experts.s_7854b52a', slot:'enabled', minWidth: 100 },
  ]

  private tableList: any[] = []

  private rate = {
    healthy: 90,
    slightAbnormal: 70,
    seriousAbnormal: 40,
  }

  private async created () {
    //
  }

  private handleSearch (val: string) {
    this.tableList = (this.configDetail?.healthRuleEntityList ?? []).filter((i: any) => (i?.describe || '').includes(this.queryName) && i.ruleType === 1).map((i: any) => {
      return {
        id: uuidv4(),
        ...i,
      }
    });
  }

  private formatDetail (detail: any = {}) {
    this.configDetail = detail;
    // 初始化权重和权值
    const _list = Array.isArray(detail?.healthRuleEntityList) ? detail.healthRuleEntityList : [];
    _list.forEach((rule: any) => {
      try {
        const _config = JSON.parse(rule?.config || '{}');
        let totalWeight = 0;
        const minWeight = (_config?.metrics || []).reduce((min: number, item: any) => Math.min(min, item.weight || 50), 100);

        (_config?.metrics || []).forEach((item: any) => {
          item.weight = Number(item.weight) || minWeight;
          totalWeight += item.weight;
        });
        (_config?.metrics || []).forEach((item: any) => {
          item.rate = totalWeight > 0 ? ((Number(item.weight) || 0) / totalWeight).toFixed(6) : 0;
        });
        rule.config = JSON.stringify(_config);
      } catch {
        //
      }
    });
    // ruleType = 0 默认规则， ruleType = 1 自定义规则
    this.tableList = _list.filter((i: any) => i.ruleType === 1).map((i: any) => {
      return {
        id: uuidv4(),
        ...i,
      }
    });
    const { colorSign = '', healthRuleEntityList = [] } = detail;
    if (!StringIsEmpty(colorSign)) {
      const [health = '', normal = ''] = String(colorSign).split(',');
      if (!isNaN(Number(health)) && isFinite(Number(health))) {
        this.rate.healthy = Number(health)
      }
      if (!isNaN(Number(normal)) && isFinite(Number(normal))) {
        this.rate.slightAbnormal = Number(normal)
      }
    }
  }

  private editDefaultRule() {
    this.ruleDetail = Array.isArray(this.configDetail?.healthRuleEntityList) ? this.configDetail.healthRuleEntityList.find((i: any) => i?.ruleType === 0) : { ruleType: 0, priority: 1 };
    this.defaultEditMode = true;
  }

  private handleAddRule () {
    this.drawerVisible = true;
    this.ruleDetail = null;
  }
  private editRule(row: any) {
    this.drawerVisible = true;
    this.ruleDetail = row;
  }
  private deleteRule(row: any) {
    this.$confirm(i18n.t('modules.views.sysManage.org.s_bafb9cb6') as string, i18n.t('common.hint') as string, { type: 'warning' })
      .then(async () => {
        this.tableLoading = true
        const { result, error } = await toAsyncWait(HealthApi.deleteRule({ id: row.id }));
        this.tableLoading = false
        if (!error) {
          this.$message.success(i18n.t('modules.views.aiPlatform.experts.s_0007d170') as string);
          // this.tableRefresh()
          this.tableList = this.tableList.filter(item => item.id !== row.id);
        } else if (error.message !== 'interrupt') {
          this.$message.error(error.message);
        }
      })
      .catch(() => null)
  }
  private handleSaveRule(payload: any) {
    this.onTypeChange(this.type);
    this.drawerVisible = false;
  }

  private handleCancelRule () {
    this.drawerVisible = false;
  }
  private async toggleRowEnableHandle(row: any) {
    // row.enabled = !row.enabled;
    this.tableLoading = true;
    const { error } = await toAsyncWait(HealthApi.editRule({...row, enabled: Number(!row.enabled)}));
    this.tableLoading = false;
    if (error && error.message !== 'interrupt') {
      this.$message.error(error?.message || i18n.t('modules.views.configInstall.dataAccess.s_5fa802be') as string);
    } else {
      this.$message.success(i18n.t('modules.views.configManage.entity.s_33130f5c') as string);
      row.enabled = Number(!row.enabled);
    }
  }

  private handleCloseDrawer(done: any) {
    this.drawerVisible = false;
    done();
  }

  private cancelDefaultHandle() {
    this.defaultEditMode = false;
  }
  private saveDefaultHandle(payload: any) {
    this.onTypeChange(this.type);
    this.defaultEditMode = false;
  }
  private colorCancelHandle() {
    this.colorEditMode = false;
  }
  private async colorSaveHandle () {
    this.tableLoading = true
    const colorSign = `${this.rate.healthy},${this.rate.slightAbnormal}`;
    const { error } = await toAsyncWait(HealthApi.setLevel({ id: this.getCid, colorSign }));
    this.tableLoading = false
    if (!error) {
      this.colorEditMode = false;
      this.$message.success(i18n.t('modules.views.aiPlatform.experts.s_3b108349') as string);
    } else if (error.message !== 'interrupt') {
      this.$message.error(error?.message || i18n.t('modules.views.cockpit.tab.s_6de920b4') as string);
    }
  }

  private async handleSorted () {
    this.tableLoading = true
    const sortedList = this.tableList.map((item: any, index: number) => ({
      id: item.id,
      priority: index + 1,
    }));
    const { error } = await toAsyncWait(HealthApi.sortRules(sortedList));
    this.tableLoading = false
    if (!error) {
      // this.$message.success(i18n.t('modules.views.sysManage.health.s_567c8518') as string);
      // this.tableList = sortedList;
    } else if (error.message !== 'interrupt') {
      this.$message.error(error?.message || i18n.t('modules.views.sysManage.health.s_bb58cf7f') as string);
    }
  }

}
</script>

<style lang="scss" scoped>
.cont{
  height: 100%;
}
.rotate-90 {
  transform: rotate(90deg);
}
.rotate-90.offset-5 {
  transform: rotate(90deg) translateY(8px);
}
.edit-btn {
  right: 16px;
  top: 16px;
}
.table-handler.is-disabled {
  cursor: not-allowed;
  pointer-events: none;
  opacity: 0.5;
}
</style>