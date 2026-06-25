<template>
  <div class="llm-config-page" v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="page-title">{{ $t('modules.views.configManage.llm.s_73cbcf30') }}</h2>
        <p class="page-desc">{{ $t('modules.views.configManage.llm.s_51342d6a') }}</p>
      </div>
      <el-button type="primary" size="small" @click="openCreateDialog">
        <i class="db-icon-add"></i> {{ $t('modules.views.configManage.llm.s_66ab5e9f') }}
      </el-button>
    </div>

    <div class="provider-grid">
      <div
        v-for="item in providers"
        :key="item.providerCode"
        class="provider-card"
        :class="{ 'is-configured': item.configured, 'is-enabled': item.enabled }"
        @click="openEditDialog(item)">
        <div class="provider-icon" :style="{ background: iconStyle(item).bg }">
          <span>{{ iconStyle(item).label }}</span>
        </div>
        <div class="provider-name">{{ item.displayName }}</div>
        <div class="provider-url" :title="item.baseUrl">{{ item.baseUrl }}</div>
        <div class="provider-meta">
          <el-tag v-if="item.defaultProvider" size="mini" type="primary" effect="plain">{{ $t('modules.views.configManage.llm.s_18c63459') }}</el-tag>
          <el-tag size="mini" type="info" effect="plain">{{ $t('modules.views.configManage.llm.s_0c2e6e90', { value0: item.modelCount }) }}</el-tag>
          <el-tag v-if="item.enabled" size="mini" type="success" effect="plain">{{ $t('modules.views.help.startGuide.s_53ace430') }}</el-tag>
          <el-tag v-else-if="item.configured" size="mini" type="info" effect="plain">{{ $t('modules.views.configInstall.plugin.s_da208e9c') }}</el-tag>
          <el-tag v-else size="mini" type="info" effect="plain">{{ $t('modules.views.configManage.llm.s_71dc8feb') }}</el-tag>
        </div>
      </div>
    </div>

    <el-dialog
      :title="editForm.providerName || $t('modules.views.configManage.llm.s_73cbcf30')"
      :visible.sync="editVisible"
      width="760px"
      top="6vh"
      :close-on-click-modal="false"
      custom-class="llm-provider-dialog"
      @closed="resetEditForm">
      <el-form
        ref="editFormRef"
        :model="editForm"
        :rules="editRules"
        label-position="top"
        size="small"
        class="form-box"
        @submit.native.prevent>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item :label="$t('modules.views.configManage.llm.s_5d551c2c')" prop="providerName">
              <el-input v-model="editForm.providerName" :placeholder="$t('modules.views.configManage.llm.s_790d7e1c')" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Provider Code">
              <el-input v-model="editForm.providerCode" disabled />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="Base URL" prop="baseUrl">
          <el-input v-model="editForm.baseUrl" placeholder="https://api.example.com/v1" />
        </el-form-item>
        <el-form-item label="API Key" prop="apiKey">
          <el-input
            v-model="editForm.apiKey"
            :placeholder="apiKeyPlaceholder" />
          <p v-if="editApiKeyMasked && editConfigured" class="field-tip">{{ $t('modules.views.configManage.llm.s_f3a1c902') }}</p>
        </el-form-item>
        <el-form-item :label="$t('modules.views.configManage.llm.s_4fd1fecc')" prop="apiType">
          <el-select v-model="editForm.apiType" class="w-full">
            <el-option
              v-for="item in apiTypeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value" />
          </el-select>
          <p class="field-tip">{{ apiTypeHint }}</p>
        </el-form-item>

        <div class="model-section">
          <div class="model-section-header">
            <span class="model-section-title">{{ $t('modules.views.configManage.llm.s_1cd74b17') }}</span>
            <div class="model-section-actions">
              <el-button size="mini" :loading="testing" @click="testConnectivity">{{ $t('modules.views.configManage.llm.s_8a598ad9') }}</el-button>
              <el-button type="primary" size="mini" @click="addModel">{{ $t('modules.views.configManage.llm.s_4a2cd098') }}</el-button>
            </div>
          </div>

          <div v-if="!editForm.models.length" class="model-empty">{{ $t('modules.views.configManage.llm.s_22e06020') }}</div>
          <div v-for="(model, index) in editForm.models" :key="`${model.modelId}_${index}`" class="model-card">
            <div class="model-card-header">
              <span class="model-card-title">{{ model.displayName || model.modelId }} - {{ model.modelId }}</span>
              <div class="model-card-actions">
                <el-checkbox
                  v-model="model.defaultModel"
                  @change="onDefaultModelChange(index)">
                  {{ $t('modules.views.configManage.llm.s_18c63459') }}
                </el-checkbox>
                <el-button type="text" class="danger-text" @click="removeModel(index)">{{ $t('modules.views.hide.advancedConfig.s_2f4aaddd') }}</el-button>
              </div>
            </div>
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item :label="$t('modules.views.configManage.llm.s_afec32b8')" :prop="`models.${index}.modelId`" :rules="modelIdRules">
                  <el-input v-model="model.modelId" :placeholder="$t('modules.views.configManage.llm.s_6c720cb4')" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item :label="$t('modules.views.dataReport.report.s_fdf6f7f6')">
                  <el-input v-model="model.displayName" :placeholder="$t('modules.views.aiPlatform.skills.s_879c241d')" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item :label="$t('modules.views.configManage.llm.s_0ebfd118')">
                  <el-input v-model="model.contextWindow" :placeholder="$t('modules.views.configManage.llm.s_15c7b6f7')" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item :label="$t('modules.views.configManage.llm.s_2fb595dc')">
                  <el-input v-model="model.maxOutputTokens" :placeholder="$t('modules.views.configManage.llm.s_b45f1a9f')" />
                </el-form-item>
              </el-col>
            </el-row>
            <div class="env-vars-block">
              <div class="env-vars-header">
                <span>{{ $t('modules.views.configManage.entity.s_3867e350') }}</span>
                <el-button type="text" @click="addEnvVar(index)">{{ $t('modules.views.configManage.llm.s_2c6d6fe6') }}</el-button>
              </div>
              <div
                v-for="(env, envIndex) in model.envVars"
                :key="`${index}_${envIndex}`"
                class="env-var-row">
                <el-input v-model="env.key" placeholder="KEY" />
                <el-input v-model="env.value" placeholder="VALUE" />
                <el-button type="text" class="danger-text" @click="removeEnvVar(index, envIndex)">{{ $t('modules.views.hide.advancedConfig.s_2f4aaddd') }}</el-button>
              </div>
            </div>
          </div>
        </div>

        <el-form-item :label="$t('modules.views.help.startGuide.s_7854b52a')">
          <el-switch v-model="editForm.enabled" />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button size="small" @click="editVisible = false">{{ $t('modules.components.dialog-template.s_625fb26b') }}</el-button>
        <el-button type="primary" size="small" :loading="saving" @click="saveProvider">{{ $t('modules.views.configInstall.plugin.s_be5fbbe3') }}</el-button>
      </span>
    </el-dialog>

    <el-dialog
      :title="$t('modules.views.configManage.llm.s_f8a70904')"
      :visible.sync="createVisible"
      width="520px"
      :close-on-click-modal="false"
      @closed="resetCreateForm">
      <div class="preset-row">
        <span class="preset-label">{{ $t('modules.views.configManage.llm.s_b78ecb24') }}</span>
        <el-tag
          v-for="preset in presets"
          :key="preset.providerCode"
          size="small"
          class="preset-tag cp"
          @click="applyPreset(preset)">
          {{ preset.displayName }}
        </el-tag>
      </div>
      <el-form
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        label-position="top"
        size="small"
        class="form-box mt-12"
        @submit.native.prevent>
        <el-form-item :label="$t('modules.views.sysManage.operationAudit.s_45235f6c')" prop="providerCode">
          <el-input v-model="createForm.providerCode" :placeholder="$t('modules.views.configManage.llm.s_7bd76870')" />
        </el-form-item>
        <el-form-item :label="$t('modules.views.dataReport.report.s_fdf6f7f6')" prop="displayName">
          <el-input v-model="createForm.displayName" :placeholder="$t('modules.views.configManage.llm.s_790d7e1c')" />
        </el-form-item>
        <el-form-item :label="$t('modules.views.configManage.llm.s_98c186f8')" prop="baseUrl">
          <el-input v-model="createForm.baseUrl" placeholder="https://api.example.com/v1" />
        </el-form-item>
        <el-form-item :label="$t('modules.views.configManage.llm.s_b11de232')" prop="defaultModel">
          <el-input v-model="createForm.defaultModel" :placeholder="$t('modules.views.configManage.ai.s_920fe38e')" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="createForm.apiKey" :placeholder="$t('modules.views.configInstall.apm.s_c20cba89')" />
        </el-form-item>
        <el-form-item :label="$t('modules.views.help.startGuide.s_7854b52a')">
          <el-switch v-model="createForm.enabled" />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button size="small" @click="createVisible = false">{{ $t('modules.components.dialog-template.s_625fb26b') }}</el-button>
        <el-button type="primary" size="small" :loading="creating" @click="submitCreate">{{ $t('modules.components.dialog-template.s_38cf16f2') }}</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script lang="ts">
import { Vue, Component } from 'vue-property-decorator';
import i18n from '@/i18n';
import { Form } from 'element-ui';
import { toAsyncWait } from '@/utils/common';
import {
  listLlmProviders,
  getLlmProviderDetail,
  saveLlmProviderDetail,
  createLlmProvider,
  testLlmProvider,
  LlmProviderView,
  LlmModelView,
  LlmEnvVarItem,
} from '@/api/llmConfig';

interface ProviderPreset {
  providerCode: string;
  displayName: string;
  baseUrl: string;
  defaultModel: string;
}

interface IconStyle {
  bg: string;
  label: string;
}

interface EditModelForm {
  modelId: string;
  displayName: string;
  contextWindow: string;
  maxOutputTokens: string;
  envVars: LlmEnvVarItem[];
  defaultModel: boolean;
}

interface EditFormState {
  providerCode: string;
  providerName: string;
  apiType: string;
  baseUrl: string;
  apiKey: string;
  enabled: boolean;
  models: EditModelForm[];
}

const ICON_MAP: Record<string, IconStyle> = {
  kimi: { bg: 'linear-gradient(135deg, #111827, #374151)', label: 'K' },
  volcengine: { bg: 'linear-gradient(135deg, #006EFF, #00E5E5)', label: i18n.t('modules.views.configManage.llm.s_df3bbd52') as string, labelKey: 'modules.views.configManage.llm.s_df3bbd52' },
  minimax: { bg: 'linear-gradient(135deg, #2563eb, #7c3aed)', label: 'M' },
  bailian: { bg: 'linear-gradient(135deg, #f97316, #ea580c)', label: i18n.t('modules.views.configManage.llm.s_81ec89a2') as string, labelKey: 'modules.views.configManage.llm.s_81ec89a2' },
  deepseek: { bg: 'linear-gradient(135deg, #0ea5e9, #0369a1)', label: 'D' },
  zhipu: { bg: 'linear-gradient(135deg, #7c3aed, #4f46e5)', label: i18n.t('modules.views.configManage.llm.s_c49daac4') as string, labelKey: 'modules.views.configManage.llm.s_c49daac4' },
  qianfan: { bg: 'linear-gradient(135deg, #3b82f6, #1d4ed8)', label: i18n.t('modules.views.configManage.llm.s_c821e522') as string, labelKey: 'modules.views.configManage.llm.s_c821e522' },
  openai: { bg: 'linear-gradient(135deg, #059669, #047857)', label: 'O' },
  ollama: { bg: 'linear-gradient(135deg, #6366f1, #4338ca)', label: 'Ol' },
};

const API_TYPE_OPTIONS = [
  { value: 'openai-completions', label: 'OpenAI Chat Completions (openai-completions)' },
  { value: 'anthropic-messages', label: 'Anthropic Messages (anthropic-messages)' },
];

const API_KEY_MASK_DISPLAY = '**********';

@Component
export default class LlmConfigPage extends Vue {
  public $refs!: {
    editFormRef: Form;
    createFormRef: Form;
  };

  private loading = false;
  private saving = false;
  private testing = false;
  private creating = false;
  private providers: LlmProviderView[] = [];
  private editVisible = false;
  private createVisible = false;
  private editConfigured = false;
  private editApiKeyMasked = false;
  private apiTypeOptions = API_TYPE_OPTIONS;

  private editForm: EditFormState = this.emptyEditForm();

  private createForm = {
    providerCode: '',
    displayName: '',
    baseUrl: '',
    defaultModel: '',
    apiKey: '',
    enabled: true,
  };

  private presets: ProviderPreset[] = [
    {
      providerCode: 'custom-openai',
      displayName: i18n.t('modules.views.configManage.llm.s_a2fb695e') as string,
      baseUrl: 'https://api.openai.com/v1',
      defaultModel: 'gpt-4o-mini',
    },
  ];

  private modelIdRules = [{ required: true, message: i18n.t('modules.views.configManage.llm.s_180338d6') as string, messageKey: 'modules.views.configManage.llm.s_180338d6', trigger: 'blur' }];

  private editRules = {
    providerName: [{ required: true, message: i18n.t('modules.views.configManage.llm.s_97b6dccd') as string, messageKey: 'modules.views.configManage.llm.s_97b6dccd', trigger: 'blur' }],
    baseUrl: [{ required: true, message: i18n.t('modules.views.configManage.llm.s_37d1bf0f') as string, messageKey: 'modules.views.configManage.llm.s_37d1bf0f', trigger: 'blur' }],
    apiType: [{ required: true, message: i18n.t('modules.views.configManage.llm.s_411e43dd') as string, messageKey: 'modules.views.configManage.llm.s_411e43dd', trigger: 'change' }],
    apiKey: [{
      validator: (_rule: unknown, value: string, callback: (err?: Error) => void) => {
        if (this.editConfigured || (value && value.trim())) {
          callback();
          return;
        }
        callback(new Error(i18n.t('modules.views.configManage.llm.s_b807ddf3') as string));
      },
      trigger: 'blur',
    }],
  };

  private createRules = {
    providerCode: [
      { required: true, message: i18n.t('modules.views.configManage.llm.s_befa4827') as string, messageKey: 'modules.views.configManage.llm.s_befa4827', trigger: 'blur' },
      { pattern: /^[a-z][a-z0-9_-]{1,31}$/, message: i18n.t('modules.views.configManage.llm.s_6394cb31') as string, messageKey: 'modules.views.configManage.llm.s_6394cb31', trigger: 'blur' },
    ],
    displayName: [{ required: true, message: i18n.t('modules.views.configManage.llm.s_050e24f5') as string, messageKey: 'modules.views.configManage.llm.s_050e24f5', trigger: 'blur' }],
    baseUrl: [{ required: true, message: i18n.t('modules.views.configManage.llm.s_2ceffaf2') as string, messageKey: 'modules.views.configManage.llm.s_2ceffaf2', trigger: 'blur' }],
    defaultModel: [{ required: true, message: i18n.t('modules.views.configManage.llm.s_09b43a8d') as string, messageKey: 'modules.views.configManage.llm.s_09b43a8d', trigger: 'blur' }],
  };

  get apiTypeHint () {
    if (this.editForm.apiType === 'anthropic-messages') {
      return i18n.t('modules.views.configManage.llm.s_627e3b8f') as string;
    }
    return i18n.t('modules.views.configManage.llm.s_19aa7412') as string;
  }

  get apiKeyPlaceholder () {
    if (this.editConfigured) {
      return i18n.t('modules.views.configManage.llm.s_08f020e8') as string;
    }
    return i18n.t('modules.views.configManage.llm.s_58aacc17') as string;
  }

  private created () {
    this.loadProviders();
  }

  private emptyEditForm (): EditFormState {
    return {
      providerCode: '',
      providerName: '',
      apiType: 'openai-completions',
      baseUrl: '',
      apiKey: '',
      enabled: true,
      models: [],
    };
  }

  private iconStyle (item: LlmProviderView): IconStyle {
    const mapped = ICON_MAP[item.providerCode];
    if (mapped) {
      return mapped;
    }
    const label = (item.displayName || item.providerCode || '?').slice(0, 2);
    return { bg: 'linear-gradient(135deg, #64748b, #475569)', label };
  }

  private async loadProviders () {
    this.loading = true;
    const { result, error } = await toAsyncWait(listLlmProviders(), false);
    this.loading = false;
    if (error) {
      this.$message.error(i18n.t('modules.views.configManage.llm.s_2d412e4c') as string);
      return;
    }
    this.providers = result || [];
  }

  private async openEditDialog (item: LlmProviderView) {
    this.loading = true;
    const { result, error } = await toAsyncWait(getLlmProviderDetail(item.providerCode), false);
    this.loading = false;
    if (error || !result) {
      this.$message.error(i18n.t('modules.views.configManage.llm.s_810d83f8') as string);
      return;
    }
    this.editConfigured = result.configured;
    this.editApiKeyMasked = !!result.apiKeyMasked;
    this.editForm = {
      providerCode: result.providerCode,
      providerName: result.providerName,
      apiType: result.apiType || 'openai-completions',
      baseUrl: result.baseUrl,
      apiKey: result.apiKeyMasked ? API_KEY_MASK_DISPLAY : (result.apiKey || ''),
      enabled: result.configured ? result.enabled : true,
      models: result.models.map(model => this.toEditModel(model)),
    };
    if (!this.editForm.models.length) {
      this.addModel();
    }
    this.editVisible = true;
    this.$nextTick(() => {
      this.$refs.editFormRef?.clearValidate();
    });
  }

  private toEditModel (model: LlmModelView): EditModelForm {
    return {
      modelId: model.modelId,
      displayName: model.displayName,
      contextWindow: model.contextWindow != null ? String(model.contextWindow) : '',
      maxOutputTokens: model.maxOutputTokens != null ? String(model.maxOutputTokens) : '',
      envVars: (model.envVars || []).map(item => ({ key: item.key, value: item.value })),
      defaultModel: model.defaultModel,
    };
  }

  private openCreateDialog () {
    this.resetCreateForm();
    this.createVisible = true;
  }

  private resetEditForm () {
    this.editForm = this.emptyEditForm();
    this.editConfigured = false;
    this.editApiKeyMasked = false;
  }

  private resetCreateForm () {
    this.createForm = {
      providerCode: '',
      displayName: '',
      baseUrl: '',
      defaultModel: '',
      apiKey: '',
      enabled: true,
    };
    this.$nextTick(() => {
      this.$refs.createFormRef?.clearValidate();
    });
  }

  private applyPreset (preset: ProviderPreset) {
    this.createForm = {
      providerCode: preset.providerCode,
      displayName: preset.displayName,
      baseUrl: preset.baseUrl,
      defaultModel: preset.defaultModel,
      apiKey: '',
      enabled: true,
    };
  }

  private addModel () {
    this.editForm.models.push({
      modelId: '',
      displayName: '',
      contextWindow: '',
      maxOutputTokens: '',
      envVars: [],
      defaultModel: false,
    });
  }

  private removeModel (index: number) {
    this.editForm.models.splice(index, 1);
  }

  private onDefaultModelChange (index: number) {
    if (!this.editForm.models[index]?.defaultModel) {
      return;
    }
    this.editForm.models.forEach((model, idx) => {
      if (idx !== index) {
        model.defaultModel = false;
      }
    });
  }

  private addEnvVar (modelIndex: number) {
    this.editForm.models[modelIndex].envVars.push({ key: '', value: '' });
  }

  private removeEnvVar (modelIndex: number, envIndex: number) {
    this.editForm.models[modelIndex].envVars.splice(envIndex, 1);
  }

  private resolveEditApiKey (): string | undefined {
    const value = (this.editForm.apiKey || '').trim();
    if (!value || value === API_KEY_MASK_DISPLAY) {
      return undefined;
    }
    return value;
  }

  private parseOptionalInt (value: string): number | null {
    const trimmed = (value || '').trim();
    if (!trimmed) {
      return null;
    }
    const parsed = Number.parseInt(trimmed, 10);
    return Number.isFinite(parsed) ? parsed : null;
  }

  private buildSaveModels (): LlmModelView[] {
    return this.editForm.models
      .filter(model => model.modelId && model.modelId.trim())
      .map(model => ({
        modelId: model.modelId.trim(),
        displayName: (model.displayName || model.modelId).trim(),
        contextWindow: this.parseOptionalInt(model.contextWindow),
        maxOutputTokens: this.parseOptionalInt(model.maxOutputTokens),
        envVars: model.envVars.filter(item => item.key && item.key.trim()),
        defaultModel: model.defaultModel,
      }));
  }

  private resolveTestModelId (): string {
    const defaultModel = this.editForm.models
      .find(model => model.defaultModel && model.modelId?.trim());
    if (defaultModel?.modelId?.trim()) {
      return defaultModel.modelId.trim();
    }
    const fromForm = this.editForm.models
      .map(model => model.modelId?.trim())
      .find(modelId => !!modelId);
    return fromForm || '';
  }

  private async testConnectivity () {
    const valid = await this.$refs.editFormRef.validate().catch(() => false);
    if (!valid) {
      return;
    }
    const modelId = this.resolveTestModelId();
    if (!modelId) {
      this.$message.warning(i18n.t('modules.views.configManage.llm.s_5cd4cd39') as string);
      return;
    }
    this.testing = true;
    const { result, error } = await toAsyncWait(testLlmProvider({
      baseUrl: this.editForm.baseUrl,
      apiKey: this.resolveEditApiKey(),
      apiType: this.editForm.apiType,
      modelId,
      providerCode: this.editForm.providerCode,
    }), false);
    this.testing = false;
    if (error) {
      this.$message.error(i18n.t('modules.views.configManage.llm.s_b9ba20b2') as string);
      return;
    }
    if (result?.ok) {
      this.$message.success(result.message || i18n.t('modules.views.configManage.llm.s_b33199d3') as string);
    } else {
      this.$message.warning(result?.message || i18n.t('modules.views.configManage.llm.s_0745fc09') as string);
    }
  }

  private async saveProvider () {
    const valid = await this.$refs.editFormRef.validate().catch(() => false);
    if (!valid) {
      return;
    }
    const models = this.buildSaveModels();
    if (!models.length) {
      this.$message.warning(i18n.t('modules.views.configManage.llm.s_71727198') as string);
      return;
    }
    const defaultModel = models.find(model => model.defaultModel);
    this.saving = true;
    const { error } = await toAsyncWait(saveLlmProviderDetail(this.editForm.providerCode, {
      providerName: this.editForm.providerName,
      apiType: this.editForm.apiType,
      baseUrl: this.editForm.baseUrl,
      apiKey: this.resolveEditApiKey(),
      enabled: this.editForm.enabled,
      defaultModelId: defaultModel?.modelId || models[0]?.modelId,
      models,
    }), false);
    this.saving = false;
    if (error) {
      this.$message.error((error as Error).message || i18n.t('modules.views.cockpit.tab.s_6de920b4') as string);
      return;
    }
    this.$message.success(i18n.t('modules.views.aiPlatform.experts.s_3b108349') as string);
    this.editVisible = false;
    await this.loadProviders();
  }

  private async submitCreate () {
    const valid = await this.$refs.createFormRef.validate().catch(() => false);
    if (!valid) {
      return;
    }
    this.creating = true;
    const { error } = await toAsyncWait(createLlmProvider({
      providerCode: this.createForm.providerCode.trim(),
      displayName: this.createForm.displayName.trim(),
      baseUrl: this.createForm.baseUrl.trim(),
      defaultModel: this.createForm.defaultModel.trim(),
      apiKey: this.createForm.apiKey || undefined,
      enabled: this.createForm.enabled,
    }), false);
    this.creating = false;
    if (error) {
      this.$message.error(i18n.t('modules.views.configManage.llm.s_5b2cc4b8') as string);
      return;
    }
    this.$message.success(i18n.t('modules.views.configManage.llm.s_a5bfd70d') as string);
    this.createVisible = false;
    await this.loadProviders();
  }
}
</script>

<style lang="scss" scoped>
.llm-config-page {
  flex: 1;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 20px;
  overflow: auto;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 4px;
}

.page-title {
  margin: 0 0 6px;
  font-size: 18px;
  font-weight: 600;
  color: var(--color-text-primary);
}

.page-desc {
  margin: 0;
  font-size: 13px;
  color: var(--color-text-secondary, #909399);
}

.provider-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 14px;
}

.provider-card {
  padding: 16px;
  background: var(--bg-color, #fff);
  border: 1px solid var(--border-color-lighter, #ebeef5);
  border-radius: 8px;
  cursor: pointer;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;

  &:hover {
    border-color: #93c5fd;
    box-shadow: 0 4px 16px rgba(59, 130, 246, 0.08);
  }

  &.is-enabled {
    border-color: #bfdbfe;
  }
}

.provider-icon {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 12px;
}

.provider-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-primary);
  margin-bottom: 6px;
}

.provider-url {
  font-size: 12px;
  color: var(--color-text-secondary, #909399);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 12px;
}

.provider-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.preset-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.preset-label {
  font-size: 13px;
  color: var(--color-text-secondary);
}

.preset-tag {
  cursor: pointer;
}

.field-tip {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--color-text-secondary, #909399);
}

.model-section {
  margin: 8px 0 16px;
  padding-top: 8px;
  border-top: 1px solid var(--border-color-lighter, #ebeef5);
}

.model-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.model-section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text-primary);
}

.model-section-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.model-empty {
  padding: 20px;
  text-align: center;
  color: var(--color-text-secondary, #909399);
  background: #f8fafc;
  border-radius: 8px;
}

.model-card {
  padding: 14px;
  margin-bottom: 12px;
  border: 1px solid var(--border-color-lighter, #ebeef5);
  border-radius: 8px;
  background: #fafafa;
}

.model-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.model-card-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-primary);
}

.model-card-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.env-vars-block {
  margin-top: 4px;
}

.env-vars-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--color-text-primary);
}

.env-var-row {
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: 8px;
  margin-bottom: 8px;
}

.danger-text {
  color: #f56c6c;
}

.w-full {
  width: 100%;
}

:deep(.llm-provider-dialog) {
  .el-dialog__body {
    max-height: 68vh;
    overflow: auto;
    padding-top: 10px;
  }
}
</style>
