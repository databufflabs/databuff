<template>
  <div class="config-wrapper"
    v-loading='isLoading'>
    <div class="config-content">
      <el-form :model="configForm" :rules="configRules" ref="configForm"
        label-position="top" size="small" class="form-box mb-30">
        <el-collapse class="db-setting-collapse" v-model="collapseData.value">
          <el-collapse-item :title="$t('modules.views.configManage.entity.s_cba0d619')" name="1">
            <el-form-item :label="$t('modules.views.appMonitor.traceDetail.s_4b1fb5dc')" prop="service_sample_rate">
              <div class="flex-h">
                <el-input-number
                  v-model="configForm.service_sample_rate"
                  :disabled="!hasEntityManageAuth"
                  :controls="false" :min="0" :max="100" :precision="0"
                  class="config-input input-number mr-5"
                ></el-input-number> %
                <el-slider
                  v-model="configForm.service_sample_rate"
                  :disabled="!hasEntityManageAuth"
                  :min="0" :max="100" :step="1" :show-tooltip="false"
                  class="config-slider ml-24"></el-slider>
              </div>
            </el-form-item>

            <div class="flex-h mb-18 lh-18">
              {{ $t('modules.views.configManage.entity.s_9a3380fe') }}
              <el-switch
                v-if="!configForm.low_power_mode"
                v-model="configForm.adaptive_sampling_config.enable"
                :disabled="!hasEntityManageAuth" class="ml-8" />
              <el-tooltip v-else :content="$t('modules.views.configManage.entity.s_ee2e741f')" placement="right" effect="light" class="ml-8">
                <el-switch v-model="lowPowerData.adaptive_sampling_config.enable" disabled class="ml-8" />
              </el-tooltip>
              <el-tooltip :content="$t('modules.views.configManage.entity.s_6ef3ab00')" placement="right" effect="light" class="ml-8">
                <i class="db-icon-info information"></i>
              </el-tooltip>
            </div>
            <template v-if="!configForm.low_power_mode && configForm.adaptive_sampling_config.enable">
              <div class="flex-h mb-18">{{ $t('modules.views.configManage.entity.s_44f02a2a') }}
                <el-form-item label="" prop="adaptive_sampling_config.successive_duration" class="form-inline-item">
                  <el-select
                    v-model="configForm.adaptive_sampling_config.successive_duration"
                    :disabled="!hasEntityManageAuth"
                    class="config-select ml-8 mr-8">
                    <el-option v-for="t in 24" :key="t" :value="t * 5" :label="t * 5" />
                  </el-select>
                </el-form-item>
                {{ $t('modules.views.configManage.entity.s_077bba07') }}<span class="red">{{ $t('modules.views.configManage.entity.s_d6c6e029') }}</span>{{ $t('modules.views.configManage.entity.s_9a3380fe') }}
              </div>
              <div class="flex-h mb-18 pl-20">{{ $t('modules.views.configManage.entity.s_44f02a2a') }}
                <el-form-item label="" prop="adaptive_sampling_config.cpuLowerBound" class="form-inline-item">
                  <el-input-number
                    v-model="configForm.adaptive_sampling_config.cpuLowerBound"
                    :disabled="!hasEntityManageAuth"
                    :max="(configForm.adaptive_sampling_config.cpuUpperBound || 100) - 1"
                    :controls="false" :min="0" :precision="0"
                    size="mini"
                    class="config-input min input-number ml-8 mr-8"
                  ></el-input-number>
                </el-form-item> {{ $t('modules.views.configManage.entity.s_a3030a13') }}
                <el-form-item label="" prop="adaptive_sampling_config.cpuUpperBound" class="form-inline-item">
                  <el-input-number
                    v-model="configForm.adaptive_sampling_config.cpuUpperBound"
                    :disabled="!hasEntityManageAuth"
                    :min="(configForm.adaptive_sampling_config.cpuLowerBound || 0) + 1"
                    :controls="false" :max="100" :precision="0"
                    size="mini"
                    class="config-input min input-number ml-8 mr-8"
                  ></el-input-number>
                </el-form-item> {{ $t('modules.views.configManage.entity.s_cff9914a') }}
                <el-form-item label="" prop="adaptive_sampling_config.cpuLowerBoundSample" class="form-inline-item">
                  <el-input-number
                    v-model="configForm.adaptive_sampling_config.cpuLowerBoundSample"
                    :disabled="!hasEntityManageAuth"
                    :min="(configForm.adaptive_sampling_config.cpuUpperBoundSample || 0) + 1"
                    :controls="false" :max="100" :precision="0"
                    size="mini"
                    class="config-input min input-number ml-8 mr-8"
                  ></el-input-number>
                </el-form-item> {{ $t('modules.views.configManage.entity.s_5c47ba7f') }}
                <el-form-item label="" prop="adaptive_sampling_config.cpuUpperBound" class="form-inline-item">
                  <el-input-number
                    v-model="configForm.adaptive_sampling_config.cpuUpperBound"
                    :disabled="!hasEntityManageAuth"
                    :min="(configForm.adaptive_sampling_config.cpuLowerBound || 0) + 1"
                    :controls="false" :max="100" :precision="0"
                    size="mini"
                    class="config-input min input-number ml-8 mr-8"
                  ></el-input-number>
                </el-form-item> {{ $t('modules.views.configManage.entity.s_cff9914a') }}
                <el-form-item label="" prop="adaptive_sampling_config.cpuUpperBoundSample" class="form-inline-item">
                  <el-input-number
                    v-model="configForm.adaptive_sampling_config.cpuUpperBoundSample"
                    :disabled="!hasEntityManageAuth"
                    :max="(configForm.adaptive_sampling_config.cpuLowerBoundSample || 100) - 1"
                    :controls="false" :min="0" :precision="0"
                    size="mini"
                    class="config-input min input-number ml-8 mr-8"
                  ></el-input-number>
                </el-form-item> %
              </div>
              <div class="flex-h mb-18 pl-20">{{ $t('modules.views.configManage.entity.s_74ac1708') }}
                <el-form-item label="" prop="adaptive_sampling_config.loadUpperBound" class="form-inline-item">
                  <el-input-number
                    v-model="configForm.adaptive_sampling_config.loadUpperBound"
                    :disabled="!hasEntityManageAuth"
                    :controls="false" :min="0" :max="3" :precision="0"
                    size="mini"
                    class="config-input min input-number ml-8 mr-8"
                  ></el-input-number>
                </el-form-item> {{ $t('modules.views.configManage.entity.s_3897382b') }}
                <el-form-item label="" prop="adaptive_sampling_config.loadUpperBoundSample" class="form-inline-item">
                  <el-input-number
                    v-model="configForm.adaptive_sampling_config.loadUpperBoundSample"
                    :disabled="!hasEntityManageAuth"
                    :controls="false" :min="0" :max="100" :precision="0"
                    size="mini"
                    class="config-input min input-number ml-8 mr-8"
                  ></el-input-number>
                </el-form-item> %
              </div>
              <div class="flex-h mb-18 pl-20">{{ $t('modules.views.configManage.entity.s_44f02a2a') }}
                <el-form-item label="" prop="adaptive_sampling_config.heapLowerBound" class="form-inline-item">
                  <el-input-number
                    v-model="configForm.adaptive_sampling_config.heapLowerBound"
                    :disabled="!hasEntityManageAuth"
                    :max="(configForm.adaptive_sampling_config.heapUpperBound || 100) - 1"
                    :controls="false" :min="0" :precision="0"
                    size="mini"
                    class="config-input min input-number ml-8 mr-8"
                  ></el-input-number>
                </el-form-item> {{ $t('modules.views.configManage.entity.s_bc44ac4b') }}
                <el-form-item label="" prop="adaptive_sampling_config.heapUpperBound" class="form-inline-item">
                  <el-input-number
                    v-model="configForm.adaptive_sampling_config.heapUpperBound"
                    :disabled="!hasEntityManageAuth"
                    :min="(configForm.adaptive_sampling_config.heapLowerBound || 0) + 1"
                    :controls="false" :max="100" :precision="0"
                    size="mini"
                    class="config-input min input-number ml-8 mr-8"
                  ></el-input-number>
                </el-form-item> {{ $t('modules.views.configManage.entity.s_cff9914a') }}
                <el-form-item label="" prop="adaptive_sampling_config.heapLowerBoundSample" class="form-inline-item">
                  <el-input-number
                    v-model="configForm.adaptive_sampling_config.heapLowerBoundSample"
                    :disabled="!hasEntityManageAuth"
                    :min="(configForm.adaptive_sampling_config.heapUpperBoundSample || 0) + 1"
                    :controls="false" :max="100" :precision="0"
                    size="mini"
                    class="config-input min input-number ml-8 mr-8"
                  ></el-input-number>
                </el-form-item> {{ $t('modules.views.configManage.entity.s_ff04faf0') }}
                <el-form-item label="" prop="adaptive_sampling_config.heapUpperBound" class="form-inline-item">
                  <el-input-number
                    v-model="configForm.adaptive_sampling_config.heapUpperBound"
                    :disabled="!hasEntityManageAuth"
                    :min="(configForm.adaptive_sampling_config.heapLowerBound || 0) + 1"
                    :controls="false" :max="100" :precision="0"
                    size="mini"
                    class="config-input min input-number ml-8 mr-8"
                  ></el-input-number>
                </el-form-item> {{ $t('modules.views.configManage.entity.s_cff9914a') }}
                <el-form-item label="" prop="adaptive_sampling_config.heapUpperBoundSample" class="form-inline-item">
                  <el-input-number
                    v-model="configForm.adaptive_sampling_config.heapUpperBoundSample"
                    :disabled="!hasEntityManageAuth"
                    :max="(configForm.adaptive_sampling_config.heapLowerBoundSample || 100) - 1"
                    :controls="false" :min="0" :precision="0"
                    size="mini"
                    class="config-input min input-number ml-8 mr-8"
                  ></el-input-number>
                </el-form-item> %
              </div>
              <div class="mb-18 pl-20">{{ $t('modules.views.configManage.entity.s_2f8f095e') }}<span class="green">{{ $t('modules.views.configManage.entity.s_c7db6d4f') }}</span>{{ $t('modules.views.configManage.entity.s_14216603') }}</div>
            </template>

            <el-form-item prop="url_path_normalized_type">
              <div slot="label">{{ $t('modules.views.configManage.entity.s_0792a870') }}
                <el-tooltip placement="right" effect="light">
                  <div slot='content'>
                    <div>{{ $t('modules.views.configManage.entity.s_df88b578') }}</div>
                    <div class="mt-5 ml-20">POST /org.databuff.dispatch/abc123abc/82737764223/OrderDispatchService</div>
                    <div class="mt-10">{{ $t('modules.views.configManage.entity.s_fc8830d4') }}</div>
                    <div class="mt-5 ml-20">POST /org.databuff.dispatch/abc123abc/82737764223/OrderDispatchService</div>
                    <div class="mt-10">{{ $t('modules.views.configManage.entity.s_b8418ca6') }}</div>
                    <div class="mt-5 ml-20">POST /org.databuff.dispatch/abc123abc/?/OrderDispatchService</div>
                    <div class="mt-10">{{ $t('modules.views.configManage.entity.s_7456000e') }}</div>
                    <div class="mt-5 ml-20">POST /org.databuff.dispatch/?/?/OrderDispatchService</div>
                  </div>
                  <i class="db-icon-info information"></i>
                </el-tooltip>
              </div>
              <el-select
                v-if="!configForm.low_power_mode"
                v-model="configForm.url_path_normalized_type" :disabled='!hasEntityManageAuth' class="config-select max">
                <el-option :value="-1" :label="$t('modules.views.configManage.entity.s_4f1287d6')" />
                <el-option :value="0" :label="$t('modules.views.configManage.entity.s_11d0f0dd')" />
                <el-option :value="1" :label="$t('modules.views.configManage.entity.s_046e1a9f')" />
              </el-select>
              <el-tooltip v-else :content="$t('modules.views.configManage.entity.s_ee2e741f')" placement="right" effect="light" class="ml-8">
                <el-select
                  v-model="lowPowerData.url_path_normalized_type" disabled class="config-select max">
                  <el-option :value="-1" :label="$t('modules.views.configManage.entity.s_4f1287d6')" />
                  <el-option :value="0" :label="$t('modules.views.configManage.entity.s_11d0f0dd')" />
                  <el-option :value="1" :label="$t('modules.views.configManage.entity.s_046e1a9f')" />
                </el-select>
              </el-tooltip>
            </el-form-item>

            <el-form-item prop="sql_normalized_type">
              <div slot="label">{{ $t('modules.views.configManage.entity.s_5fa14562') }}
                <el-tooltip placement="right" effect="light">
                  <div slot='content'>
                    <div>{{ $t('modules.views.configManage.entity.s_c7b53dca') }}</div>
                    <div class="mt-5 ml-20">select * from dc_db where apiKey = HW274HYFH2492H and startTriggerTime &lt;= 1710224793 and lastTriggerTime &gt;= 1710226306</div>
                    <div class="mt-10">{{ $t('modules.views.configManage.entity.s_fc8830d4') }}</div>
                    <div class="mt-5 ml-20">select * from dc_db where apiKey = HW274HYFH2492H and startTriggerTime &lt;= 1710224793 and lastTriggerTime &gt;= 1710226306</div>
                    <div class="mt-10">{{ $t('modules.views.configManage.entity.s_0ed58191') }}</div>
                    <div class="mt-5 ml-20">select * from dc_db where apiKey = HW274HYFH2492H and startTriggerTime &lt;= ? and lastTriggerTime &gt;= ?</div>
                    <div class="mt-10">{{ $t('modules.views.configManage.entity.s_7456000e') }}</div>
                    <div class="mt-5 ml-20">select * from dc_db where apiKey = ? and startTriggerTime &lt;= ? and lastTriggerTime &gt;= ?</div>
                  </div>
                  <i class="db-icon-info information"></i>
                </el-tooltip>
              </div>
              <el-select
                v-if="!configForm.low_power_mode"
                v-model="configForm.sql_normalized_type" :disabled='!hasEntityManageAuth' class="config-select max">
                <el-option :value="-1" :label="$t('modules.views.configManage.entity.s_4f1287d6')" />
                <el-option :value="0" :label="$t('modules.views.configManage.entity.s_f5c2bd8a')" />
                <el-option :value="1" :label="$t('modules.views.configManage.entity.s_046e1a9f')" />
              </el-select>
              <el-tooltip v-else :content="$t('modules.views.configManage.entity.s_ee2e741f')" placement="right" effect="light" class="ml-8">
                <el-select
                  v-model="lowPowerData.sql_normalized_type" disabled class="config-select max">
                  <el-option :value="-1" :label="$t('modules.views.configManage.entity.s_4f1287d6')" />
                  <el-option :value="0" :label="$t('modules.views.configManage.entity.s_f5c2bd8a')" />
                  <el-option :value="1" :label="$t('modules.views.configManage.entity.s_046e1a9f')" />
                </el-select>
              </el-tooltip>
            </el-form-item>
          </el-collapse-item>

          <el-collapse-item :title="$t('modules.api.config.ts.s_1f318234')" name="2">
            <div class="font-13 mb-10">{{ $t('modules.views.configManage.entity.s_63fa9335') }}</div>
            <el-form-item :label="$t('modules.views.configManage.entity.s_2f4d6430')" prop="slow_http">
              <el-input-number
                v-model="configForm.slow_http" :disabled='!hasEntityManageAuth'
                :controls="false" :min="100" :max="9999" :precision="0"
                class="config-input input-number"
              ></el-input-number>
              <span class="ml-8 mr-8">ms</span>
              <span class="font-12 describe">{{ $t('modules.views.configManage.entity.s_12258768') }}</span>
            </el-form-item>
            <el-form-item :label="$t('modules.views.configManage.entity.s_1877450f')" prop="slow_rpc">
              <el-input-number
                v-model="configForm.slow_rpc" :disabled='!hasEntityManageAuth'
                :controls="false" :min="100" :max="9999" :precision="0"
                class="config-input input-number"
              ></el-input-number>
              <span class="ml-8 mr-8">ms</span>
              <span class="font-12 describe">{{ $t('modules.views.configManage.entity.s_a1f87499') }}</span>
            </el-form-item>
            <el-form-item :label="$t('modules.views.configManage.entity.s_f5f01a37')" prop="slow_mq">
              <el-input-number
                v-model="configForm.slow_mq" :disabled='!hasEntityManageAuth'
                :controls="false" :min="100" :max="9999" :precision="0"
                class="config-input input-number"
              ></el-input-number>
              <span class="ml-8 mr-8">ms</span>
              <span class="font-12 describe">{{ $t('modules.views.configManage.entity.s_ffd27383') }}</span>
            </el-form-item>
            <el-form-item :label="$t('modules.views.configManage.entity.s_90059cbe')" prop="slow_sql">
              <el-input-number
                v-model="configForm.slow_sql" :disabled='!hasEntityManageAuth'
                :controls="false" :min="100" :max="9999" :precision="0"
                class="config-input input-number"
              ></el-input-number>
              <span class="ml-8 mr-8">ms</span>
              <span class="font-12 describe">{{ $t('modules.views.configManage.entity.s_03496f5a') }}</span>
            </el-form-item>
            <el-form-item :label="$t('modules.views.configManage.entity.s_e974f2cf')" prop="slow_redis">
              <el-input-number
                v-model="configForm.slow_redis" :disabled='!hasEntityManageAuth'
                :controls="false" :min="100" :max="9999" :precision="0"
                class="config-input input-number"
              ></el-input-number>
              <span class="ml-8 mr-8">ms</span>
              <span class="font-12 describe">{{ $t('modules.views.configManage.entity.s_41798142') }}</span>
            </el-form-item>
            <el-form-item :label="$t('modules.views.configManage.entity.s_39f82079')" prop="slow_elasticsearch">
              <el-input-number
                v-model="configForm.slow_elasticsearch" :disabled='!hasEntityManageAuth'
                :controls="false" :min="100" :max="9999" :precision="0"
                class="config-input input-number"
              ></el-input-number>
              <span class="ml-8 mr-8">ms</span>
              <span class="font-12 describe">{{ $t('modules.views.configManage.entity.s_4c6ba43e') }}</span>
            </el-form-item>
            <el-form-item :label="$t('modules.views.configManage.entity.s_5ef13d7f')" prop="slow_config">
              <el-input-number
                v-model="configForm.slow_config" :disabled='!hasEntityManageAuth'
                :controls="false" :min="100" :max="9999" :precision="0"
                class="config-input input-number"
              ></el-input-number>
              <span class="ml-8 mr-8">ms</span>
              <span class="font-12 describe">{{ $t('modules.views.configManage.entity.s_40a49003') }}</span>
            </el-form-item>
            <el-form-item :label="$t('modules.views.configManage.entity.s_33be2818')" prop="slow_other">
              <el-input-number
                v-model="configForm.slow_other" :disabled='!hasEntityManageAuth'
                :controls="false" :min="100" :max="9999" :precision="0"
                class="config-input input-number"
              ></el-input-number>
              <span class="ml-8 mr-8">ms</span>
              <span class="font-12 describe">{{ $t('modules.views.configManage.entity.s_85003369') }}</span>
            </el-form-item>

            <div class="flex-h mb-18 lh-18">
              {{ $t('modules.views.configManage.entity.s_c6b56eb0') }}
              <el-switch
                v-model="configForm.circuit_breaking_config.enable"
                :disabled="!hasEntityManageAuth" class="ml-8" />
            </div>
            <template v-if="configForm.circuit_breaking_config.enable">
              <div class="flex-h mb-18">{{ $t('modules.views.configManage.entity.s_44f02a2a') }}
                <el-form-item label="" prop="circuit_breaking_config.successive_duration" class="form-inline-item">
                  <el-select
                    v-model="configForm.circuit_breaking_config.successive_duration"
                    :disabled="!hasEntityManageAuth"
                    class="config-select ml-8 mr-8">
                    <el-option v-for="t in 24" :key="t" :value="t * 5" :label="t * 5" />
                  </el-select>
                </el-form-item>
                {{ $t('modules.views.configManage.entity.s_b2849d18') }}<span class="red">{{ $t('modules.views.configManage.entity.s_d6c6e029') }}</span>{{ $t('modules.views.configManage.entity.s_c5e3dab9') }}
              </div>
              <div class="flex-h mb-18 pl-20">
                <div class="w172">{{ $t('modules.views.configManage.entity.s_ef48f858') }}</div>
                <el-form-item label="" prop="circuit_breaking_config.cpu_trigger_threshold" class="form-inline-item w93">
                  <el-input-number
                    v-model="configForm.circuit_breaking_config.cpu_trigger_threshold"
                    :disabled="!hasEntityManageAuth"
                    :controls="false" :min="0" :max="100" :precision="0"
                    size="mini"
                    class="config-input min input-number mr-8"
                  ></el-input-number> %
                </el-form-item>
                <el-slider
                  v-model="configForm.circuit_breaking_config.cpu_trigger_threshold"
                  :disabled="!hasEntityManageAuth"
                  :min="0" :max="100" :step="1" :show-tooltip="false"
                  class="config-slider"></el-slider>
              </div>
              <div class="flex-h mb-18 pl-20">
                <div class="w172">{{ $t('modules.views.configManage.entity.s_108253a7') }}</div>
                <el-form-item label="" prop="circuit_breaking_config.load_trigger_threshold" class="form-inline-item w93">
                  <el-input-number
                    v-model="configForm.circuit_breaking_config.load_trigger_threshold"
                    :disabled="!hasEntityManageAuth"
                    :controls="false" :min="0" :max="3" :precision="2"
                    size="mini"
                    class="config-input min input-number mr-8"
                  ></el-input-number>
                </el-form-item>
                <el-slider
                  v-model="configForm.circuit_breaking_config.load_trigger_threshold"
                  :disabled="!hasEntityManageAuth"
                  :min="0" :max="3" :step="0.01" :show-tooltip="false"
                  class="config-slider"></el-slider>
              </div>
              <div class="flex-h mb-18 pl-20">
                <div class="w172">{{ $t('modules.views.configManage.entity.s_4d63f881') }}</div>
                <el-form-item label="" prop="circuit_breaking_config.heap_trigger_threshold" class="form-inline-item w93">
                  <el-input-number
                    v-model="configForm.circuit_breaking_config.heap_trigger_threshold"
                    :disabled="!hasEntityManageAuth"
                    :controls="false" :min="0" :max="100" :precision="0"
                    size="mini"
                    class="config-input min input-number mr-8"
                  ></el-input-number> %
                </el-form-item>
                <el-slider
                  v-model="configForm.circuit_breaking_config.heap_trigger_threshold"
                  :disabled="!hasEntityManageAuth"
                  :min="0" :max="100" :step="1" :show-tooltip="false"
                  class="config-slider"></el-slider>
              </div>

              <div class="flex-h mb-18">{{ $t('modules.views.configManage.entity.s_44f02a2a') }}
                <el-form-item label="" prop="circuit_breaking_config.successive_duration" class="form-inline-item">
                  <el-select
                    v-model="configForm.circuit_breaking_config.successive_duration"
                    :disabled="!hasEntityManageAuth"
                    class="config-select ml-8 mr-8">
                    <el-option v-for="t in 24" :key="t" :value="t * 5" :label="t * 5" />
                  </el-select>
                </el-form-item>
                {{ $t('modules.views.configManage.entity.s_289b314a') }}<span class="green">{{ $t('modules.views.configManage.entity.s_2d92531b') }}</span>{{ $t('modules.views.configManage.entity.s_c5e3dab9') }}
              </div>
              <div class="flex-h mb-18 pl-20">
                <div class="w172">{{ $t('modules.views.configManage.entity.s_93453b75') }}</div>
                <el-form-item label="" prop="circuit_breaking_config.cpu_recovery_threshold" class="form-inline-item w93">
                  <el-input-number
                    v-model="configForm.circuit_breaking_config.cpu_recovery_threshold"
                    :disabled="!hasEntityManageAuth"
                    :controls="false" :min="0" :max="100" :precision="0"
                    size="mini"
                    class="config-input min input-number mr-8"
                  ></el-input-number> %
                </el-form-item>
                <el-slider
                  v-model="configForm.circuit_breaking_config.cpu_recovery_threshold"
                  :disabled="!hasEntityManageAuth"
                  :min="0" :max="100" :step="1" :show-tooltip="false"
                  class="config-slider"></el-slider>
              </div>
              <div class="flex-h mb-18 pl-20">
                <div class="w172">{{ $t('modules.views.configManage.entity.s_fa9d4c7b') }}</div>
                <el-form-item label="" prop="circuit_breaking_config.load_recovery_threshold" class="form-inline-item w93">
                  <el-input-number
                    v-model="configForm.circuit_breaking_config.load_recovery_threshold"
                    :disabled="!hasEntityManageAuth"
                    :controls="false" :min="0" :max="3" :precision="2"
                    size="mini"
                    class="config-input min input-number mr-8"
                  ></el-input-number>
                </el-form-item>
                <el-slider
                  v-model="configForm.circuit_breaking_config.load_recovery_threshold"
                  :disabled="!hasEntityManageAuth"
                  :min="0" :max="3" :step="0.01" :show-tooltip="false"
                  class="config-slider"></el-slider>
              </div>
              <div class="flex-h mb-18 pl-20">
                <div class="w172">{{ $t('modules.views.configManage.entity.s_ea8ac283') }}</div>
                <el-form-item label="" prop="circuit_breaking_config.heap_recovery_threshold" class="form-inline-item w93">
                  <el-input-number
                    v-model="configForm.circuit_breaking_config.heap_recovery_threshold"
                    :disabled="!hasEntityManageAuth"
                    :controls="false" :min="0" :max="100" :precision="0"
                    size="mini"
                    class="config-input min input-number mr-8"
                  ></el-input-number> %
                </el-form-item>
                <el-slider
                  v-model="configForm.circuit_breaking_config.heap_recovery_threshold"
                  :disabled="!hasEntityManageAuth"
                  :min="0" :max="100" :step="1" :show-tooltip="false"
                  class="config-slider"></el-slider>
              </div>
            </template>

            <div class="flex-h mb-18 lh-18">
              {{ $t('modules.views.configManage.entity.s_7d16b0e6') }}
              <el-switch
                v-model="configForm.low_power_mode"
                :disabled="!hasEntityManageAuth" class="ml-8" />
            </div>

            <div class="flex-h mb-18 lh-18">
              {{ $t('modules.views.configManage.entity.s_8ae363ed') }}
              <el-switch
                v-if="!configForm.low_power_mode"
                v-model="configForm.request_header_dump"
                :disabled="!hasEntityManageAuth" class="ml-8" />
              <el-tooltip v-else :content="$t('modules.views.configManage.entity.s_ee2e741f')" placement="right" effect="light" class="ml-8">
                <el-switch v-model="lowPowerData.request_header_dump" disabled class="ml-8" />
              </el-tooltip>
            </div>

            <div class="flex-h mb-18 lh-18">
              {{ $t('modules.views.configManage.entity.s_cfb44ee8') }}
              <el-switch
                v-if="!configForm.low_power_mode"
                v-model="configForm.response_header_dump"
                :disabled="!hasEntityManageAuth" class="ml-8" />
              <el-tooltip v-else :content="$t('modules.views.configManage.entity.s_ee2e741f')" placement="right" effect="light" class="ml-8">
                <el-switch v-model="lowPowerData.response_header_dump" disabled class="ml-8" />
              </el-tooltip>
            </div>

          </el-collapse-item>
        </el-collapse>
      </el-form>
    </div>

    <div class="pt-10">
      <el-button
        :loading="resetLoading"
        :disabled="!hasEntityManageAuth || postLoading"
        @click="resetHandle"
        size="small">{{ $t('modules.views.configManage.entity.s_770fe9e7') }}</el-button>
      <el-button
        :loading="postLoading"
        :disabled="!hasEntityManageAuth || resetLoading"
        @click="saveHandle"
        type="primary" size="small">{{ $t('modules.views.configInstall.plugin.s_be5fbbe3') }}</el-button>
    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import i18n from '@/i18n';
import { Form } from 'element-ui'
import { toAsyncWait } from '@/utils/common';
import ConfigApi from '@/api/config';
import deepClone from 'lodash/cloneDeep';

@Component({
  components: {},
})
export default class ApmGlobal extends Vue {
  public $refs!: {
    configForm: Form
  }

  private slowFields = ['slow_http', 'slow_rpc', 'slow_mq', 'slow_sql', 'slow_redis', 'slow_elasticsearch', 'slow_config', 'slow_other'];

  private configForm: any = {
    key: 'global',
    service_sample_rate: 100,
    url_path_normalized_type: 1,
    sql_normalized_type: 1,
    low_power_mode: false,
    request_header_dump: false,
    response_header_dump: false,
    adaptive_sampling_config: {
      enable: false,
      successive_duration: 5,
      cpuLowerBound: 70,
      cpuLowerBoundSample: 50,
      cpuUpperBound: 80,
      cpuUpperBoundSample: 10,
      loadUpperBound: 1,
      loadUpperBoundSample: 10,
      heapLowerBound: 70,
      heapLowerBoundSample: 50,
      heapUpperBound: 80,
      heapUpperBoundSample: 10,
    },
    circuit_breaking_config: {
      enable: false,
      broken_sample_rate: 0,
      successive_duration: 30,
      cpu_trigger_threshold: 70,
      cpu_recovery_threshold: 30,
      load_trigger_threshold: 0.8,
      load_recovery_threshold: 0.6,
      heap_trigger_threshold: 70,
      heap_recovery_threshold: 40,
    },
    slow_http: 300,
    slow_rpc: 150,
    slow_mq: 2000,
    slow_sql: 500,
    slow_redis: 200,
    slow_elasticsearch: 500,
    slow_config: 500,
    slow_other: 1000,
  }

  private configRules = {
    'service_sample_rate': { required: true, trigger: 'blur', message: i18n.t('modules.views.configManage.entity.s_e6a4612b') as string, messageKey: 'modules.views.configManage.entity.s_e6a4612b' },
    'url_path_normalized_type': { required: true, trigger: 'change', message: i18n.t('modules.views.configManage.entity.s_91a2b481') as string, messageKey: 'modules.views.configManage.entity.s_91a2b481' },
    'sql_normalized_type': { required: true, trigger: 'change', message: i18n.t('modules.views.configManage.entity.s_982168d7') as string, messageKey: 'modules.views.configManage.entity.s_982168d7' },
    'slow_http': { required: true, trigger: 'blur', message: i18n.t('modules.views.configManage.entity.s_d2d1378f') as string, messageKey: 'modules.views.configManage.entity.s_d2d1378f' },
    'slow_rpc': { required: true, trigger: 'blur', message: i18n.t('modules.views.configManage.entity.s_83af415c') as string, messageKey: 'modules.views.configManage.entity.s_83af415c' },
    'slow_mq': { required: true, trigger: 'blur', message: i18n.t('modules.views.configManage.entity.s_bfb51ec9') as string, messageKey: 'modules.views.configManage.entity.s_bfb51ec9' },
    'slow_sql': { required: true, trigger: 'blur', message: i18n.t('modules.views.configManage.entity.s_586eddf9') as string, messageKey: 'modules.views.configManage.entity.s_586eddf9' },
    'slow_redis': { required: true, trigger: 'blur', message: i18n.t('modules.views.configManage.entity.s_18e41950') as string, messageKey: 'modules.views.configManage.entity.s_18e41950' },
    'slow_elasticsearch': { required: true, trigger: 'blur', message: i18n.t('modules.views.configManage.entity.s_7b3187b5') as string, messageKey: 'modules.views.configManage.entity.s_7b3187b5' },
    'slow_config': { required: true, trigger: 'blur', message: i18n.t('modules.views.configManage.entity.s_97f33c3f') as string, messageKey: 'modules.views.configManage.entity.s_97f33c3f' },
    'slow_other': { required: true, trigger: 'blur', message: i18n.t('modules.views.configManage.entity.s_4664b528') as string, messageKey: 'modules.views.configManage.entity.s_4664b528' },
    'adaptive_sampling_config.successive_duration': { required: true, trigger: 'change', message: i18n.t('modules.views.metrics.list.s_708c9d6d') as string },
    'adaptive_sampling_config.cpuLowerBound': { required: true, trigger: 'blur', message: i18n.t('modules.views.configInstall.dataAccess.s_02cc4f8f') as string, messageKey: 'modules.components.matching-criteria.s_02cc4f8f' },
    'adaptive_sampling_config.cpuLowerBoundSample': { required: true, trigger: 'blur', message: i18n.t('modules.views.configInstall.dataAccess.s_02cc4f8f') as string, messageKey: 'modules.components.matching-criteria.s_02cc4f8f' },
    'adaptive_sampling_config.cpuUpperBound': { required: true, trigger: 'blur', message: i18n.t('modules.views.configInstall.dataAccess.s_02cc4f8f') as string, messageKey: 'modules.components.matching-criteria.s_02cc4f8f' },
    'adaptive_sampling_config.cpuUpperBoundSample': { required: true, trigger: 'blur', message: i18n.t('modules.views.configInstall.dataAccess.s_02cc4f8f') as string, messageKey: 'modules.components.matching-criteria.s_02cc4f8f' },
    'adaptive_sampling_config.loadUpperBound': { required: true, trigger: 'blur', message: i18n.t('modules.views.configInstall.dataAccess.s_02cc4f8f') as string, messageKey: 'modules.components.matching-criteria.s_02cc4f8f' },
    'adaptive_sampling_config.loadUpperBoundSample': { required: true, trigger: 'blur', message: i18n.t('modules.views.configInstall.dataAccess.s_02cc4f8f') as string, messageKey: 'modules.components.matching-criteria.s_02cc4f8f' },
    'adaptive_sampling_config.heapLowerBound': { required: true, trigger: 'blur', message: i18n.t('modules.views.configInstall.dataAccess.s_02cc4f8f') as string, messageKey: 'modules.components.matching-criteria.s_02cc4f8f' },
    'adaptive_sampling_config.heapLowerBoundSample': { required: true, trigger: 'blur', message: i18n.t('modules.views.configInstall.dataAccess.s_02cc4f8f') as string, messageKey: 'modules.components.matching-criteria.s_02cc4f8f' },
    'adaptive_sampling_config.heapUpperBound': { required: true, trigger: 'blur', message: i18n.t('modules.views.configInstall.dataAccess.s_02cc4f8f') as string, messageKey: 'modules.components.matching-criteria.s_02cc4f8f' },
    'adaptive_sampling_config.heapUpperBoundSample': { required: true, trigger: 'blur', message: i18n.t('modules.views.configInstall.dataAccess.s_02cc4f8f') as string, messageKey: 'modules.components.matching-criteria.s_02cc4f8f' },
    'circuit_breaking_config.successive_duration': { required: true, trigger: 'change', message: i18n.t('modules.views.metrics.list.s_708c9d6d') as string },
    'circuit_breaking_config.cpu_trigger_threshold': { required: true, trigger: 'blur', message: i18n.t('modules.views.configInstall.dataAccess.s_02cc4f8f') as string, messageKey: 'modules.components.matching-criteria.s_02cc4f8f' },
    'circuit_breaking_config.cpu_recovery_threshold': { required: true, trigger: 'blur', message: i18n.t('modules.views.configInstall.dataAccess.s_02cc4f8f') as string, messageKey: 'modules.components.matching-criteria.s_02cc4f8f' },
    'circuit_breaking_config.load_trigger_threshold': { required: true, trigger: 'blur', message: i18n.t('modules.views.configInstall.dataAccess.s_02cc4f8f') as string, messageKey: 'modules.components.matching-criteria.s_02cc4f8f' },
    'circuit_breaking_config.load_recovery_threshold': { required: true, trigger: 'blur', message: i18n.t('modules.views.configInstall.dataAccess.s_02cc4f8f') as string, messageKey: 'modules.components.matching-criteria.s_02cc4f8f' },
    'circuit_breaking_config.heap_trigger_threshold': { required: true, trigger: 'blur', message: i18n.t('modules.views.configInstall.dataAccess.s_02cc4f8f') as string, messageKey: 'modules.components.matching-criteria.s_02cc4f8f' },
    'circuit_breaking_config.heap_recovery_threshold': { required: true, trigger: 'blur', message: i18n.t('modules.views.configInstall.dataAccess.s_02cc4f8f') as string, messageKey: 'modules.components.matching-criteria.s_02cc4f8f' },
  }

  private lowPowerData: any = {
    url_path_normalized_type: -1,
    sql_normalized_type: -1,
    request_header_dump: false,
    response_header_dump: false,
    adaptive_sampling_config: {
      enable: false,
    },
  }

  private collapseData: any = { // 折叠面板展开控制
    value: ['1'],
    1: [
      'service_sample_rate', 'url_path_normalized_type', 'sql_normalized_type',
      'adaptive_sampling_config.successive_duration',
      'adaptive_sampling_config.cpuLowerBound',
      'adaptive_sampling_config.cpuLowerBoundSample',
      'adaptive_sampling_config.cpuUpperBound',
      'adaptive_sampling_config.cpuUpperBoundSample',
      'adaptive_sampling_config.loadUpperBound',
      'adaptive_sampling_config.loadUpperBoundSample',
      'adaptive_sampling_config.heapLowerBound',
      'adaptive_sampling_config.heapLowerBoundSample',
      'adaptive_sampling_config.heapUpperBound',
      'adaptive_sampling_config.heapUpperBoundSample',
    ],
    2: [
      'slow_http', 'slow_rpc', 'slow_mq', 'slow_sql',
      'slow_redis', 'slow_elasticsearch', 'slow_config', 'slow_other',
      'circuit_breaking_config.successive_duration',
      'circuit_breaking_config.cpu_trigger_threshold',
      'circuit_breaking_config.cpu_recovery_threshold',
      'circuit_breaking_config.load_trigger_threshold',
      'circuit_breaking_config.load_recovery_threshold',
      'circuit_breaking_config.heap_trigger_threshold',
      'circuit_breaking_config.heap_recovery_threshold',
    ],
  }

  private isLoading = false // 配置获取中
  private postLoading = false // 配置获取中
  private resetLoading = false // 配置重置中

  private configMapping: any = {} // 修改前的配置
  get config () {
    return this.configMapping[this.configForm.key]
  }

  private async created () {
    this.isLoading = true
    await this.getConfig()
    await this.getDtsConfig()
    this.isLoading = false
  }

  private async getConfig () {
    const { result, error } = await toAsyncWait(ConfigApi.getConfigList('/javaagent/global'))
    if (!error) {
      const data: any[] = (result || {}).data || []
      const key = this.configForm.key
      const item = data.find(t => t.key === key)
      if (item) {
        this.configMapping = {
          ...this.configMapping,
          [key]: { ...item, value: this.formatValue(item.value) }
        }
      }
      this.initForm()
    } else if (error.message !== 'interrupt') {
      this.$message.error(error.message);
    }
  }
  private async getDtsConfig () {
    const { result, error } = await toAsyncWait(ConfigApi.getConfigList('/dts/global'))
    if (!error) {
      const data: any[] = (result || {}).data || [];
      if (Array.isArray(data)) {
        data.forEach((item: any) => {
          const _key = String(item.key).replace('global.', '')
          if (this.slowFields.includes(_key) && Object.prototype.hasOwnProperty.call(this.configForm, _key)) {
            this.configForm[_key] = item.value
          }
        })
      }
    } else if (error.message !== 'interrupt') {
      // this.$message.error(error.message);
    }
  }

  private initForm () {
    const configValue = (this.config || {}).value || {}
    const isNum = (num: any) => typeof num === 'number' && !isNaN(num)
    const getNumValue = (num: any, scale = 1) => isNum(num) ? num * scale : ''
    Object.keys(this.configForm).filter(key => key !== 'key' && !this.slowFields.includes(key)).forEach(key => {
      if (key === 'adaptive_sampling_config') {
        const adaptiveValue = configValue[key]
        const configs: any[] = adaptiveValue?.configs || []
        const cpuLower = configs.find(t => t.resource === 'cpu' && isNum(t.lowerBound) && isNum(t.upperBound))
        const cpuUpper = configs.find(t => t.resource === 'cpu' && isNum(t.lowerBound) && !isNum(t.upperBound))
        const loadUpper = configs.find(t => t.resource === 'load' && isNum(t.lowerBound))
        const heapLower = configs.find(t => t.resource === 'heap' && isNum(t.lowerBound) && isNum(t.upperBound))
        const heapUpper = configs.find(t => t.resource === 'heap' && isNum(t.lowerBound) && !isNum(t.upperBound))
        this.configForm[key] = {
          enable: !!adaptiveValue?.enable,
          successive_duration: adaptiveValue ? adaptiveValue.successive_duration : 5,
          cpuLowerBound: adaptiveValue ? getNumValue(cpuLower?.lowerBound, 100) : 70,
          cpuLowerBoundSample: adaptiveValue ? getNumValue(cpuLower?.sample, 100) : 50,
          cpuUpperBound: adaptiveValue ? getNumValue(cpuLower?.upperBound, 100) || getNumValue(cpuUpper?.lowerBound, 100) : 80,
          cpuUpperBoundSample: adaptiveValue ? getNumValue(cpuUpper?.sample, 100) : 10,
          loadUpperBound: adaptiveValue ? getNumValue(loadUpper?.lowerBound) : 1,
          loadUpperBoundSample: adaptiveValue ? getNumValue(loadUpper?.sample, 100) : 10,
          heapLowerBound: adaptiveValue ? getNumValue(heapLower?.lowerBound, 100) : 70,
          heapLowerBoundSample: adaptiveValue ? getNumValue(heapLower?.sample, 100) : 50,
          heapUpperBound: adaptiveValue ? getNumValue(heapLower?.upperBound, 100) || getNumValue(heapUpper?.lowerBound, 100) : 80,
          heapUpperBoundSample: adaptiveValue ? getNumValue(heapUpper?.sample, 100) : 10,
        }
      } else if (key === 'circuit_breaking_config') {
        const circuitValue = configValue[key]
        this.configForm[key] = {
          enable: !!circuitValue?.enable,
          broken_sample_rate: circuitValue?.broken_sample_rate || 0,
          successive_duration: circuitValue ? circuitValue.successive_duration : 30,
          cpu_trigger_threshold: circuitValue ? getNumValue(circuitValue.cpu_trigger_threshold, 100) : 70,
          cpu_recovery_threshold: circuitValue ? getNumValue(circuitValue.cpu_recovery_threshold, 100) : 30,
          load_trigger_threshold: circuitValue ? getNumValue(circuitValue.load_trigger_threshold) : 0.8,
          load_recovery_threshold: circuitValue ? getNumValue(circuitValue.load_recovery_threshold) : 0.6,
          heap_trigger_threshold: circuitValue ? getNumValue(circuitValue.heap_trigger_threshold, 100) : 70,
          heap_recovery_threshold: circuitValue ? getNumValue(circuitValue.heap_recovery_threshold, 100) : 40,
        }
      } else if (['request_header_dump', 'response_header_dump', 'low_power_mode'].includes(key)) {
        this.configForm[key] = !!configValue[key]
      } else if (key === 'service_sample_rate') {
        this.configForm[key] = getNumValue(configValue[key], 100)
      } else {
        this.configForm[key] = isNum(configValue[key]) ? configValue[key] : ''
      }
    })
  }

  private saveHandle () {
    const getSaveParams = () => {
      const config = this.config || {}
      const circuitConfig = this.configForm.circuit_breaking_config
      const adaptiveConfig = this.configForm.adaptive_sampling_config
      const configForm = {
        service_sample_rate: this.configForm.service_sample_rate / 100,
        url_path_normalized_type: this.configForm.url_path_normalized_type,
        sql_normalized_type: this.configForm.sql_normalized_type,
        low_power_mode: this.configForm.low_power_mode,
        request_header_dump: this.configForm.request_header_dump,
        response_header_dump: this.configForm.response_header_dump,
        circuit_breaking_config: {
          ...config?.value?.circuit_breaking_config,
          ...circuitConfig,
          cpu_trigger_threshold: circuitConfig.cpu_trigger_threshold / 100,
          cpu_recovery_threshold: circuitConfig.cpu_recovery_threshold / 100,
          load_trigger_threshold: circuitConfig.load_trigger_threshold,
          load_recovery_threshold: circuitConfig.load_recovery_threshold,
          heap_trigger_threshold: circuitConfig.heap_trigger_threshold / 100,
          heap_recovery_threshold: circuitConfig.heap_recovery_threshold / 100,
        },
        adaptive_sampling_config: {
          ...config?.value?.adaptive_sampling_config,
          enable: adaptiveConfig.enable,
          successive_duration: adaptiveConfig.successive_duration,
          configs: [
            { resource: 'cpu', lowerBound: adaptiveConfig.cpuLowerBound / 100, upperBound: adaptiveConfig.cpuUpperBound / 100, sample: adaptiveConfig.cpuLowerBoundSample / 100 },
            { resource: 'cpu', lowerBound: adaptiveConfig.cpuUpperBound / 100, sample: adaptiveConfig.cpuUpperBoundSample / 100 },
            { resource: 'load', lowerBound: adaptiveConfig.loadUpperBound, sample: adaptiveConfig.loadUpperBoundSample / 100 },
            { resource: 'heap', lowerBound: adaptiveConfig.heapLowerBound / 100, upperBound: adaptiveConfig.heapUpperBound / 100, sample: adaptiveConfig.heapLowerBoundSample / 100 },
            { resource: 'heap', lowerBound: adaptiveConfig.heapUpperBound / 100, sample: adaptiveConfig.heapUpperBoundSample / 100 },
          ],
        },
      }
      const params = {
        ...config,
        key: this.configForm.key,
        path: `/javaagent/${this.configForm.key}`,
        value: { ...(config.value || {}), ...configForm },
      }
      if (!this.config) {
        params.add = true
        params.builtIn = true
        params.desc = ''
      }
      Object.entries(configForm).forEach(([key, value]) => {
        if (!value && value !== 0 && value !== false) {
          delete params.value[key]
        }
      })
      return params
    }
    const getDtsSaveParams = () => {
      return [
        {desc: i18n.t('modules.views.configManage.entity.s_63dab682') as string, descKey: 'modules.views.configManage.entity.s_63dab682', key: 'global/slow_http', value: this.configForm.slow_http, path: '/dts/global/slow_http', builtIn: true},
        {desc: i18n.t('modules.views.configManage.entity.s_e2b03f38') as string, descKey: 'modules.views.configManage.entity.s_e2b03f38', key: 'global/slow_rpc', value: this.configForm.slow_rpc, path: '/dts/global/slow_rpc', builtIn: true},
        {desc: i18n.t('modules.views.configManage.entity.s_9aac0267') as string, descKey: 'modules.views.configManage.entity.s_9aac0267', key: 'global/slow_mq', value: this.configForm.slow_mq, path: '/dts/global/slow_mq', builtIn: true},
        {desc: i18n.t('modules.views.configManage.entity.s_7090b9eb') as string, descKey: 'modules.views.configManage.entity.s_7090b9eb', key: 'global/slow_sql', value: this.configForm.slow_sql, path: '/dts/global/slow_sql', builtIn: true},
        {desc: i18n.t('modules.views.configManage.entity.s_f89729cd') as string, descKey: 'modules.views.configManage.entity.s_f89729cd', key: 'global/slow_redis', value: this.configForm.slow_redis, path: '/dts/global/slow_redis', builtIn: true},
        {desc: i18n.t('modules.views.configManage.entity.s_6d565f84') as string, descKey: 'modules.views.configManage.entity.s_6d565f84', key: 'global/slow_elasticsearch', value: this.configForm.slow_elasticsearch, path: '/dts/global/slow_elasticsearch', builtIn: true},
        {desc: i18n.t('modules.views.configManage.entity.s_a79330b2') as string, descKey: 'modules.views.configManage.entity.s_a79330b2', key: 'global/slow_config', value: this.configForm.slow_config, path: '/dts/global/slow_config', builtIn: true},
        {desc: i18n.t('modules.views.configManage.entity.s_fa56762e') as string, descKey: 'modules.views.configManage.entity.s_fa56762e', key: 'global/slow_other', value: this.configForm.slow_other, path: '/dts/global/slow_other', builtIn: true}
      ]
    }
    this.$refs.configForm.validate(async (valid: boolean, fields: any) => {
      if (valid) {
        const params = getSaveParams()
        const dtsParams = getDtsSaveParams()
        this.postLoading = true;
        const { result, error } = await toAsyncWait(ConfigApi.saveGlobalConfig([params, ...dtsParams]));
        this.postLoading = false;
        if (!error) {
          this.$message.success(i18n.t('modules.views.appMonitor.service.s_55aa6366') as string);
          delete params.add
          this.configMapping[params.key] = {
            ...(this.configMapping[params.key] || params),
            value: deepClone(params.value),
          }
        } else if (error.message !== 'interrupt') {
          this.$message.error(error.message || i18n.t('modules.views.cockpit.tab.s_6de920b4') as string);
        }
      } else {
        const fieldList = Object.keys(fields);
        const collapseValue = [...this.collapseData.value];
        for (const key in this.collapseData) {
          const collapseItem = this.collapseData[key];
          if (key !== 'value' && fieldList.some((t) => collapseItem.includes(t))) {
            collapseValue.push(key);
          }
        }
        this.collapseData.value = [...new Set(collapseValue)];
      }
    })
  }

  private async resetHandle () {
    this.resetLoading = true
    const { result, error } = await toAsyncWait(ConfigApi.resetGlobalConfig());
    this.resetLoading = false
    if (!error) {
      const data = (result || {}).data || {}
      this.configMapping[this.configForm.key] = { ...data, value: this.formatValue(data.value) }
      this.$refs.configForm.resetFields();
      this.initForm()
      this.configForm = {
        ...this.configForm,
        key: 'global',
        slow_http: 300,
        slow_rpc: 150,
        slow_mq: 2000,
        slow_sql: 500,
        slow_redis: 200,
        slow_elasticsearch: 500,
        slow_config: 500,
        slow_other: 1000,
      }
      this.$message.success(i18n.t('modules.views.configManage.entity.s_faa357fc') as string);
    } else if (error.message !== 'interrupt') {
      this.$message.error(error.message || i18n.t('modules.views.configManage.entity.s_4d713822') as string);
    }
  }

  private formatValue (value: any) {
    let _value = value
    if (typeof value === 'string') {
      try {
        _value = JSON.parse(value)
      } catch (error) {
        //
      }
    }
    if (Object.prototype.toString.call(value) !== '[object Object]') {
      _value = {}
    }
    return _value
  }
}
</script>

<style lang="scss" scoped>
.config-wrapper {
  display: flex;
  flex-direction: column;

  .config-content {
    flex: 1;
    overflow: auto;
  }

  .config-input {
    width: 64px;
    &.max {
      width: 480px;
    }
    &.min {
      width: 48px;
      line-height: 24px;
      :deep(.el-input__inner) {
        height: 24px;
        line-height: 24px;
        padding-left: 9px;
        padding-right: 9px;
      }
    }
  }
  .config-select {
    width: 80px;
    &.max {
      width: 480px;
    }
  }
  .config-slider {
    width: 160px;
  }

  .input-number :deep(.el-input__inner) {
    text-align: left;
  }

  :deep(.el-form-item.is-required>.el-form-item__label:before) {
    display: none;
  }
  :deep(.el-input.is-disabled .el-input__inner) {
    color: inherit;
  }

  .form-inline-item {
    margin-bottom: 0;
    line-height: 1;
    :deep(.el-form-item__content) {
      line-height: 1;
      display: flex;
      align-items: center;
    }
    :deep(.el-form-item__error) {
      display: none;
    }
  }

  .m-0 {
    margin: 0 !important;
  }

  .ml-24 {
    margin-left: 24px;
  }

  .w172 {
    width: 172px;
  }
  .w93 {
    width: 93px;
  }
}
</style>
