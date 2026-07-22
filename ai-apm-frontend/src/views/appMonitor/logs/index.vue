<template>
  <div class="logs-container">
    <div class="logs-wrap">
      <div class="logs-toolbar flex-h-jc mb-12">
        <div class="flex-h flex-1 ovh">
          <el-input
            v-model="queryText"
            @change="onQueryTextChange"
            @clear="onQueryTextChange('')"
            @keyup.enter.native="onQueryTextChange(queryText)"
            clearable
            size="small"
            maxlength="200"
            prefix-icon="db-icon-search"
            :placeholder="$t('modules.views.alarmCenter.alarmDetail.s_d1f0b009')"
            class="mr-10 search-input" />
          <el-input
            v-model="traceIdText"
            @change="onTraceIdChange"
            @clear="onTraceIdChange('')"
            @keyup.enter.native="onTraceIdChange(traceIdText)"
            clearable
            size="small"
            maxlength="64"
            placeholder="Trace ID"
            class="mr-10 trace-input" />
          <el-input
            v-if="spanIdText || showSpanInput"
            v-model="spanIdText"
            @change="onSpanIdChange"
            @clear="onSpanIdChange('')"
            @keyup.enter.native="onSpanIdChange(spanIdText)"
            clearable
            size="small"
            maxlength="32"
            placeholder="Span ID"
            class="mr-10 trace-input" />
        </div>
      </div>

      <chart-group
        ref="chartGroup"
        :query="chartQueryParams"
        :timeParams="timeParams"
        :searchInitLoading="chartInitLoading"
        :queryLoading="chartQueryLoading"
        @chart-click="chartClickHandle"
        class="chart-group" />

      <div v-if="showList" :class="['logs-cont', collapsed ? 'is-collapsed' : '']">
        <span
          v-show="collapsed"
          @click="toggleFilterHandle(false)"
          class="db-icon db-icon-unfold cp font-12 fixed-btn" />

        <div :class="['logs-filter choose-collapse flex-v', collapsed ? 'is-collapsed' : '']">
          <div class="choose-collapse-title flex-h-jc">
            <span class="font-13">{{ $t('modules.views.alarmCenter.alarm.s_c0fd0276') }}</span>
            <span
              v-show="!collapsed"
              @click="toggleFilterHandle(true)"
              class="db-icon db-icon-fold cp font-12" />
          </div>
          <div class="choose-collapse-body">
            <simplebar style="height: 100%; padding-right: 12px;">
              <el-collapse v-model="activeFilterNames" class="filter-collapse">
                <el-collapse-item name="service" :title="$t('modules.views.alarmCenter.alarm.s_47d68cd0')">
                  <template slot="title">
                    <div class="flex-h-jc">
                      <span>{{ $t('modules.views.alarmCenter.alarm.s_47d68cd0') }}</span>
                      <span
                        v-show="selectedServiceIds.length"
                        @click.stop="clearFilter('service')"
                        class="filter-clear-btn db-icon-close cp" />
                    </div>
                  </template>
                  <simplebar v-if="serviceOptions.length" style="max-height: 200px;">
                    <el-checkbox-group v-model="selectedServiceIds" @change="onFilterChange">
                      <el-checkbox
                        v-for="item in serviceOptions"
                        :key="item.id"
                        :label="item.id"
                        class="filter-checkbox">{{ item.nameKey ? $t(item.nameKey) : item.name }}</el-checkbox>
                    </el-checkbox-group>
                  </simplebar>
                  <div v-else class="describe filter-empty">{{ $t('modules.components.charts.s_21efd88b') }}</div>
                </el-collapse-item>

                <el-collapse-item name="serviceInstance" :title="$t('modules.views.alarmCenter.alarm.s_71673bab')">
                  <template slot="title">
                    <div class="flex-h-jc">
                      <span>{{ $t('modules.views.alarmCenter.alarm.s_71673bab') }}</span>
                      <span
                        v-show="selectedServiceInstances.length"
                        @click.stop="clearFilter('serviceInstance')"
                        class="filter-clear-btn db-icon-close cp" />
                    </div>
                  </template>
                  <simplebar v-if="serviceInstanceOptions.length" style="max-height: 200px;">
                    <el-checkbox-group v-model="selectedServiceInstances" @change="onFilterChange">
                      <el-checkbox
                        v-for="instance in serviceInstanceOptions"
                        :key="instance"
                        :label="instance"
                        class="filter-checkbox">{{ instance }}</el-checkbox>
                    </el-checkbox-group>
                  </simplebar>
                  <div v-else class="describe filter-empty">{{ $t('modules.components.charts.s_21efd88b') }}</div>
                </el-collapse-item>

                <el-collapse-item name="host" :title="$t('modules.views.alarmCenter.alarm.s_65227369')">
                  <template slot="title">
                    <div class="flex-h-jc">
                      <span>{{ $t('modules.views.alarmCenter.alarm.s_65227369') }}</span>
                      <span
                        v-show="selectedHosts.length"
                        @click.stop="clearFilter('host')"
                        class="filter-clear-btn db-icon-close cp" />
                    </div>
                  </template>
                  <simplebar v-if="hostOptions.length" style="max-height: 200px;">
                    <el-checkbox-group v-model="selectedHosts" @change="onFilterChange">
                      <el-checkbox
                        v-for="host in hostOptions"
                        :key="host"
                        :label="host"
                        class="filter-checkbox">{{ host }}</el-checkbox>
                    </el-checkbox-group>
                  </simplebar>
                  <div v-else class="describe filter-empty">{{ $t('modules.components.charts.s_21efd88b') }}</div>
                </el-collapse-item>

                <el-collapse-item name="severity" :title="$t('modules.views.alarmCenter.eventDetail.s_3fea7ca7')">
                  <template slot="title">
                    <div class="flex-h-jc">
                      <span>{{ $t('modules.views.alarmCenter.eventDetail.s_3fea7ca7') }}</span>
                      <span
                        v-show="selectedSeverities.length"
                        @click.stop="clearFilter('severity')"
                        class="filter-clear-btn db-icon-close cp" />
                    </div>
                  </template>
                  <simplebar v-if="severityOptions.length" style="max-height: 200px;">
                    <el-checkbox-group v-model="selectedSeverities" @change="onFilterChange">
                      <el-checkbox
                        v-for="level in severityOptions"
                        :key="level"
                        :label="level"
                        :class="['filter-checkbox', severityClass(level)]">{{ level }}</el-checkbox>
                    </el-checkbox-group>
                  </simplebar>
                  <div v-else class="describe filter-empty">{{ $t('modules.components.charts.s_21efd88b') }}</div>
                </el-collapse-item>
              </el-collapse>
            </simplebar>
          </div>
        </div>

        <db-table
          ref="listTable"
          :queryApi="queryApi"
          :queryParams="tableQueryParams"
          :offsetMode="true"
          :timeMode="false"
          :autoRefresh="false"
          :columnConfig="columnConfig"
          :formatFunc="formatFunc"
          :row-style="{ cursor: 'pointer' }"
          tableKey="APM_LOGS_ANALYSIS"
          @on-table-inited="tableInitedHandle"
          @sort-change="refreshTable"
          @row-click="rowClickHandle"
          class="logs-table list">
          <template slot="total" slot-scope="{ total }">
            <div class="describe">
              <span class="mr-15">{{ $t('modules.views.appMonitor.trace.s_1d0e7889', { value0: new Intl.NumberFormat().format(total) }) }}</span>
              <span class="time-range">{{ $t('modules.views.appMonitor.serviceCallDetail.s_c4023f57', listTimeDisplay) }}</span>
            </div>
          </template>
          <template slot="status" slot-scope="{ row }">
            <span :class="['log-level-tag', severityClass(row.status)]">{{ row.status || '-' }}</span>
          </template>
          <el-table-column slot="suffix" label="Trace" width="88" align="center">
            <template slot-scope="{ row }">
              <span
                v-if="row.traceId"
                @click.stop="viewTraceHandle(row)"
                class="db-blue cp font-12">{{ $t('modules.views.metrics.list.s_607e7a4f') }}</span>
              <span v-else class="describe">-</span>
            </template>
          </el-table-column>
        </db-table>
      </div>
    </div>

    <log-detail-drawer
      :visible.sync="detailVisible"
      :row="detailRow"
      @view-trace="viewTraceHandle" />
  </div>
</template>

<script lang="ts">
import { Vue, Component, Watch } from 'vue-property-decorator';
import { Getter } from 'vuex-class';
import simplebar from 'simplebar-vue';
import 'simplebar/dist/simplebar.min.css';
import dayjs from 'dayjs';
import i18n from '@/i18n';
import LogApi from '@/api/log';
import { toAsyncWait } from '@/utils/common';
import { v4 as uuidv4 } from 'uuid';
import ChartGroup from './overviewChart.vue';
import LogDetailDrawer from './logDetailDrawer.vue';
import { DEFAULT_CHART_LIST_LIMIT, resolveRecentRangeFromCounts } from '@/utils/chartListRange';
import { formatCompactTimeRange } from '@/utils/timeFormat';
import {
  ERROR_SEVERITIES,
  getSeverityClass,
  sortSeverities,
} from '@/utils/logSeverity';

@Component({
  components: { simplebar, ChartGroup, LogDetailDrawer },
})
export default class LogsAnalysis extends Vue {
  @Getter('globalTime') private globalTimeFunc!: any;
  @Getter('globalTimeInited') private globalTimeInited!: boolean;

  public $refs!: { listTable: any; chartGroup: ChartGroup };

  private queryApi = LogApi.getLogList;
  private queryText = '';
  private traceIdText = '';
  private spanIdText = '';
  private showSpanInput = false;
  private selectedServiceIds: string[] = [];
  private selectedServiceInstances: string[] = [];
  private selectedHosts: string[] = [];
  private selectedSeverities: string[] = [];
  private serviceOptions: Array<{ id: string; name: string }> = [];
  private serviceInstanceOptions: string[] = [];
  private hostOptions: string[] = [];
  private severityOptions: string[] = [];
  private timeParams = { fromTime: '', toTime: '', interval: 3600 };
  private listTimeRange = { fromTimeNs: '', toTimeNs: '' };
  private showList = false;
  private listScope: 'default' | 'minute' = 'default';
  private chartInitLoading = false;
  private chartQueryLoading = false;
  private tableReady = false;
  private collapsed = false;
  private activeFilterNames = ['service', 'serviceInstance', 'host', 'severity'];
  private refreshToken = 0;
  private refreshRetryTimer: number | null = null;
  private syncReloadSeq = 0;
  private detailVisible = false;
  private detailRow: any = null;

  private static readonly ROUTE_PRESERVE_KEYS = ['__ps', 'durationRange', 'sf', 'st', 'fromTime', 'toTime'];

  private columnConfig = [
    { field: '_timestamp', prop: '_timestamp', label: i18n.t('modules.views.appMonitor.errorDetail.s_13f7745f') as string, labelKey: 'modules.views.appMonitor.errorDetail.s_13f7745f', unit: 'time', minWidth: 150 },
    { field: 'status', prop: 'status', label: i18n.t('modules.views.alarmCenter.eventDetail.s_3fea7ca7') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_3fea7ca7', slot: 'status', minWidth: 88 },
    { field: 'service', prop: 'service', label: i18n.t('modules.views.alarmCenter.alarm.s_47d68cd0') as string, labelKey: 'modules.views.alarmCenter.alarm.s_47d68cd0', minWidth: 120 },
    { field: 'serviceInstance', prop: 'serviceInstance', label: i18n.t('modules.views.alarmCenter.alarm.s_71673bab') as string, labelKey: 'modules.utils.filters.s_71673bab', minWidth: 140, showOverflowTooltip: true },
    { field: 'hostname', prop: 'hostname', label: i18n.t('modules.views.alarmCenter.alarm.s_65227369') as string, labelKey: 'modules.views.alarmCenter.alarm.s_65227369', minWidth: 100 },
    { field: 'message', prop: 'message', label: i18n.t('modules.views.alarmCenter.eventDetail.s_2d711b09') as string, labelKey: 'modules.views.alarmCenter.eventDetail.s_2d711b09', minWidth: 320, showOverflowTooltip: true },
  ];

  get globalTime () {
    return this.globalTimeFunc();
  }

  get chartQueryParams () {
    return this.buildConditionParams(this.getChartTimeRange());
  }

  get listTimeDisplay () {
    const { fromTime, toTime } = this.getListTimeRange();
    return formatCompactTimeRange(fromTime, toTime);
  }

  get tableQueryParams () {
    const { fromTime, toTime } = this.getListTimeRange();
    const params: Record<string, any> = {
      fromTimeNs: `${this.resolveTimeMillis(fromTime) * 1_000_000}`,
      toTimeNs: `${this.resolveTimeMillis(toTime) * 1_000_000}`,
      size: DEFAULT_CHART_LIST_LIMIT,
      query: this.normalizeInputValue(this.queryText),
      traceId: this.normalizeInputValue(this.traceIdText),
      spanId: this.normalizeInputValue(this.spanIdText),
      serviceIds: [...this.selectedServiceIds],
      serviceInstances: [...this.selectedServiceInstances],
      hosts: [...this.selectedHosts],
      severities: [...this.selectedSeverities],
    };
    Object.keys(params).forEach((key) => {
      const val = params[key];
      if (val === '' || val === null || val === undefined) {
        delete params[key];
      } else if (Array.isArray(val) && !val.length) {
        delete params[key];
      }
    });
    return params;
  }

  @Watch('globalTime', { deep: true })
  private watchGlobalTime () {
    if (!this.globalTimeInited) {
      return;
    }
    this.syncTimeAndReload();
  }

  @Watch('globalTimeInited', { immediate: true })
  private watchGlobalTimeInited (val: boolean) {
    if (val) {
      this.syncTimeAndReload();
    }
  }

  private async restoreChartSelectionFromRoute () {
    const { sf, st, ft, tt, traceId } = this.$route.query;
    if (sf && st && !isNaN(Number(sf)) && !isNaN(Number(st)) && String(sf).length === 13 && String(st).length === 13) {
      const severities = String(this.$route.query.severities || '');
      const isErrorChart = severities.split(',').some((level) => ERROR_SEVERITIES.includes(level));
      this.chartClickHandle(dayjs(Number(sf)).format('YYYY-MM-DD HH:mm'), isErrorChart ? 'error' : '');
      return;
    }
    if (
      traceId
      && ft
      && tt
      && !isNaN(Number(ft))
      && !isNaN(Number(tt))
      && String(ft).length === 13
      && String(tt).length === 13
    ) {
      const fromMs = Number(ft);
      const toMs = Number(tt);
      this.listScope = 'minute';
      this.listTimeRange = {
        fromTimeNs: `${fromMs * 1_000_000}`,
        toTimeNs: `${toMs * 1_000_000}`,
      };
      this.showList = true;
      await this.loadConditions();
      this.$nextTick(() => {
        this.$refs.listTable?.refresh?.();
      });
      return;
    }
    await this.applyDefaultListFromChart();
  }

  private async applyDefaultListFromChart () {
    const range = resolveRecentRangeFromCounts(this.$refs.chartGroup?.getVolumeCounts?.(), {
      interval: this.timeParams.interval,
      globalToTime: this.timeParams.toTime,
      limit: DEFAULT_CHART_LIST_LIMIT,
    });
    if (!range) {
      this.showList = false;
      this.listTimeRange = { fromTimeNs: '', toTimeNs: '' };
      return;
    }
    this.listScope = 'default';
    this.listTimeRange = {
      fromTimeNs: `${range.fromMs * 1_000_000}`,
      toTimeNs: `${range.toMs * 1_000_000}`,
    };
    this.showList = true;
    this.clearMinuteRoute();
    this.chartQueryLoading = true;
    try {
      await this.loadConditions();
      await this.$nextTick();
      this.$refs.listTable?.refresh?.();
    } finally {
      this.chartQueryLoading = false;
    }
  }

  private clearMinuteRoute () {
    const nextQuery: Record<string, string> = {};
    LogsAnalysis.ROUTE_PRESERVE_KEYS.forEach((key) => {
      if (key === 'sf' || key === 'st') {
        return;
      }
      const value = this.$route.query[key];
      if (value !== null && value !== undefined && value !== '') {
        nextQuery[key] = String(value);
      }
    });
    this.$router.replace({ path: this.$route.path, query: nextQuery });
  }

  private getChartTimeRange () {
    const { fromTime, toTime } = this.getGlobalTime();
    return { fromTime, toTime };
  }

  private getListTimeRange () {
    if (this.listTimeRange.fromTimeNs && this.listTimeRange.toTimeNs) {
      return {
        fromTime: dayjs(Number(this.listTimeRange.fromTimeNs) / 1_000_000).toDate(),
        toTime: dayjs(Number(this.listTimeRange.toTimeNs) / 1_000_000).toDate(),
      };
    }
    return this.getChartTimeRange();
  }

  private resolveTimeMillis (value: Date | string | number) {
    if (value instanceof Date) {
      return value.valueOf();
    }
    if (typeof value === 'number') {
      return value;
    }
    const parsed = dayjs(value);
    return parsed.isValid() ? parsed.valueOf() : Date.now();
  }

  private regetGlobalTime () {
    const { fromTime, toTime, interval } = this.getGlobalTimeV2();
    this.timeParams = { fromTime, toTime, interval };
  }

  private created () {
    this.restoreFromRoute();
  }

  private mounted () {
    this.$eventBus.$on('GlobalRefresh', this, this.syncTimeAndReload);
  }

  private beforeDestroy () {
    this.$eventBus.$off('GlobalRefresh');
    if (this.refreshRetryTimer !== null) {
      window.clearTimeout(this.refreshRetryTimer);
      this.refreshRetryTimer = null;
    }
  }

  private restoreFromRoute () {
    const routeQuery = this.$route.query;
    const keyword = this.readRouteFilterValue(routeQuery.query);
    if (keyword) {
      this.queryText = keyword;
    }
    const traceId = this.readRouteFilterValue(routeQuery.traceId);
    if (traceId) {
      this.traceIdText = traceId;
    }
    const spanId = this.readRouteFilterValue(routeQuery.spanId);
    if (spanId) {
      this.spanIdText = spanId;
      this.showSpanInput = true;
    }
    if (routeQuery.serviceIds) {
      this.selectedServiceIds = String(routeQuery.serviceIds).split(',').map((s) => decodeURIComponent(s)).filter(Boolean);
    }
    if (routeQuery.serviceInstances) {
      this.selectedServiceInstances = String(routeQuery.serviceInstances).split(',').map((s) => decodeURIComponent(s)).filter(Boolean);
    }
    if (routeQuery.hosts) {
      this.selectedHosts = String(routeQuery.hosts).split(',').map((s) => decodeURIComponent(s)).filter(Boolean);
    }
    if (routeQuery.severities) {
      this.selectedSeverities = String(routeQuery.severities).split(',').filter(Boolean);
    }
  }

  private readRouteFilterValue (value: unknown) {
    if (value === null || value === undefined || value === '') {
      return '';
    }
    const text = decodeURIComponent(String(value)).trim();
    return text === 'undefined' ? '' : text;
  }

  private normalizeInputValue (value: unknown) {
    if (value === null || value === undefined) {
      return '';
    }
    return String(value).trim();
  }

  private buildConditionParams (timeRange?: { fromTime: Date | string; toTime: Date | string }) {
    const { fromTime, toTime } = timeRange || this.getChartTimeRange();
    const params: Record<string, any> = {
      fromTimeNs: `${this.resolveTimeMillis(fromTime) * 1_000_000}`,
      toTimeNs: `${this.resolveTimeMillis(toTime) * 1_000_000}`,
      query: this.normalizeInputValue(this.queryText),
      traceId: this.normalizeInputValue(this.traceIdText),
      spanId: this.normalizeInputValue(this.spanIdText),
      serviceIds: [...this.selectedServiceIds],
      serviceInstances: [...this.selectedServiceInstances],
      hosts: [...this.selectedHosts],
      severities: [...this.selectedSeverities],
    };
    Object.keys(params).forEach((key) => {
      const val = params[key];
      if (val === '' || val === null || val === undefined) {
        delete params[key];
      } else if (Array.isArray(val) && !val.length) {
        delete params[key];
      }
    });
    return params;
  }

  private writeRoute (extraQuery: Record<string, string> = {}) {
    const nextQuery: Record<string, string> = { ...extraQuery };
    LogsAnalysis.ROUTE_PRESERVE_KEYS.forEach((key) => {
      if (!this.showList && (key === 'sf' || key === 'st')) {
        return;
      }
      const value = this.$route.query[key];
      if (value !== null && value !== undefined && value !== '') {
        nextQuery[key] = String(value);
      }
    });
    const keyword = this.normalizeInputValue(this.queryText);
    const traceId = this.normalizeInputValue(this.traceIdText);
    const spanId = this.normalizeInputValue(this.spanIdText);
    if (keyword) {
      nextQuery.query = encodeURIComponent(keyword);
    }
    if (traceId) {
      nextQuery.traceId = encodeURIComponent(traceId);
    }
    if (spanId) {
      nextQuery.spanId = encodeURIComponent(spanId);
    }
    if (this.selectedServiceIds.length) {
      nextQuery.serviceIds = this.selectedServiceIds.map(encodeURIComponent).join(',');
    }
    if (this.selectedServiceInstances.length) {
      nextQuery.serviceInstances = this.selectedServiceInstances.map(encodeURIComponent).join(',');
    }
    if (this.selectedHosts.length) {
      nextQuery.hosts = this.selectedHosts.map(encodeURIComponent).join(',');
    }
    if (this.selectedSeverities.length) {
      nextQuery.severities = this.selectedSeverities.join(',');
    }
    this.$router.replace({ path: this.$route.path, query: nextQuery });
  }

  private resetTimeParams () {
    this.regetGlobalTime();
    this.listTimeRange = { fromTimeNs: '', toTimeNs: '' };
  }

  private async syncTimeAndReload () {
    if (!this.globalTimeInited) {
      return;
    }
    const seq = ++this.syncReloadSeq;
    this.listScope = 'default';
    this.showList = false;
    this.resetTimeParams();
    this.chartInitLoading = true;
    try {
      await this.loadConditions();
      if (seq !== this.syncReloadSeq) {
        return;
      }
      await this.$nextTick();
      if (seq !== this.syncReloadSeq) {
        return;
      }
      await this.$refs.chartGroup?.getData();
      if (seq !== this.syncReloadSeq) {
        return;
      }
      await this.restoreChartSelectionFromRoute();
    } finally {
      if (seq === this.syncReloadSeq) {
        this.chartInitLoading = false;
      }
    }
  }

  private async reloadChartAndList () {
    if (!this.globalTimeInited) {
      return;
    }
    this.writeRoute();
    await this.$nextTick();
    await this.$refs.chartGroup?.getData();
    if (this.listScope === 'minute') {
      if (this.showList) {
        await this.loadConditions();
        this.requestTableRefresh();
      }
      return;
    }
    await this.applyDefaultListFromChart();
  }

  private async loadConditions () {
    const { result, error } = await toAsyncWait(
      LogApi.getLogsCondition(this.buildConditionParams(this.showList ? this.getListTimeRange() : undefined)),
    );
    if (error) {
      return;
    }
    const data = result?.data || {};
    this.serviceOptions = (data.services || []).map((item: any) => ({
      id: item.id || item.serviceId || item.name,
      name: item.name || item.service || item.id,
    }));
    this.serviceInstanceOptions = data.serviceInstances || [];
    this.hostOptions = data.hosts || [];
    this.severityOptions = sortSeverities(data.severities || []);
    this.pruneSelectedFilters();
  }

  private pruneSelectedFilters () {
    const serviceIdSet = new Set(this.serviceOptions.map((item) => item.id));
    this.selectedServiceIds = this.selectedServiceIds.filter((id) => serviceIdSet.has(id));
    const serviceInstanceSet = new Set(this.serviceInstanceOptions);
    this.selectedServiceInstances = this.selectedServiceInstances.filter((instance) => serviceInstanceSet.has(instance));
    const hostSet = new Set(this.hostOptions);
    this.selectedHosts = this.selectedHosts.filter((host) => hostSet.has(host));
    const severitySet = new Set(this.severityOptions);
    this.selectedSeverities = this.selectedSeverities.filter((level) => severitySet.has(level));
  }

  private async onQueryTextChange (value?: string) {
    this.queryText = this.normalizeInputValue(value !== undefined ? value : this.queryText);
    this.listScope = 'default';
    await this.loadConditions();
    await this.reloadChartAndList();
  }

  private async onTraceIdChange (value?: string) {
    this.traceIdText = this.normalizeInputValue(value !== undefined ? value : this.traceIdText);
    this.listScope = 'default';
    await this.loadConditions();
    await this.reloadChartAndList();
  }

  private async onSpanIdChange (value?: string) {
    this.spanIdText = this.normalizeInputValue(value !== undefined ? value : this.spanIdText);
    this.listScope = 'default';
    await this.loadConditions();
    await this.reloadChartAndList();
  }

  private async onFilterChange () {
    await this.loadConditions();
    if (this.listScope === 'minute' && this.showList) {
      this.writeRoute();
      await this.$nextTick();
      await this.$refs.chartGroup?.getData();
      this.requestTableRefresh();
      return;
    }
    this.listScope = 'default';
    await this.reloadChartAndList();
  }

  private clearFilter (type: string) {
    if (type === 'service') {
      this.selectedServiceIds = [];
    } else if (type === 'serviceInstance') {
      this.selectedServiceInstances = [];
    } else if (type === 'host') {
      this.selectedHosts = [];
    } else if (type === 'severity') {
      this.selectedSeverities = [];
    }
    this.onFilterChange();
  }

  private tableInitedHandle () {
    this.tableReady = true;
    if (this.showList) {
      this.requestTableRefresh();
    }
  }

  private chartClickHandle (xAxisName: string, type?: string) {
    this.listScope = 'minute';
    const { toTime, interval } = this.timeParams;
    const fromMs = +new Date(`${xAxisName}:00`);
    const toMs = Math.min(fromMs + interval * 1000, +new Date(toTime));
    this.listTimeRange = {
      fromTimeNs: `${fromMs * 1_000_000}`,
      toTimeNs: `${toMs * 1_000_000}`,
    };

    if (!this.showList) {
      if (type === 'error') {
        this.selectedSeverities = this.resolveErrorSeverities();
      }
    } else if (type === 'error') {
      this.selectedSeverities = this.resolveErrorSeverities();
    } else if (this.isErrorSeveritySelection()) {
      this.selectedSeverities = [];
    }

    this.showList = true;
    this.writeRoute({
      sf: String(fromMs),
      st: String(fromMs),
    });
    this.chartQueryLoading = true;
    this.$nextTick(async () => {
      await this.loadConditions();
      this.$nextTick(() => {
        this.$refs.listTable?.refresh?.();
        this.chartQueryLoading = false;
      });
    });
  }

  private resolveErrorSeverities () {
    if (!this.severityOptions.length) {
      return [...ERROR_SEVERITIES];
    }
    return ERROR_SEVERITIES.filter((level) => this.severityOptions.includes(level));
  }

  private isErrorSeveritySelection () {
    return this.selectedSeverities.length > 0
      && this.selectedSeverities.every((level) => ERROR_SEVERITIES.includes(level));
  }

  private requestTableRefresh () {
    if (!this.globalTimeInited || !this.showList) {
      return;
    }
    this.writeRoute();
    const token = ++this.refreshToken;
    this.$nextTick(() => {
      this.flushTableRefresh(token, 0);
    });
  }

  private flushTableRefresh (token: number, retry: number) {
    if (token !== this.refreshToken) {
      return;
    }
    const table = this.$refs.listTable;
    if (table && typeof table.refresh === 'function') {
      this.tableReady = true;
      table.refresh();
      return;
    }
    if (retry >= 20) {
      return;
    }
    if (this.refreshRetryTimer !== null) {
      window.clearTimeout(this.refreshRetryTimer);
    }
    this.refreshRetryTimer = window.setTimeout(() => {
      this.flushTableRefresh(token, retry + 1);
    }, 100);
  }

  private refreshTable () {
    this.requestTableRefresh();
  }

  private formatFunc (data: any[]) {
    (data || []).forEach((log: any) => {
      log.id = uuidv4();
      log._timestamp = log.timestamp ? +String(log.timestamp).substring(0, 13) : '';
    });
  }

  private severityClass (level: string) {
    return getSeverityClass(level);
  }

  private toggleFilterHandle (collapsed: boolean) {
    this.collapsed = collapsed;
    this.$nextTick(() => {
      this.$refs.listTable?.getHeightHandle?.();
    });
  }

  private rowClickHandle (row: any) {
    this.detailRow = row;
    this.detailVisible = true;
  }

  private viewTraceHandle (row: any) {
    if (!row.traceId) {
      return;
    }
    this.detailVisible = false;
    this.$router.push({
      path: '/appMonitor/traceDetail',
      query: {
        ...this.getRouteTimeOrRange,
        tid: row.traceId,
        spid: row.spanId || undefined,
      },
    });
  }
}
</script>

<style lang="scss" scoped>
.logs-container {
  height: 100%;
  padding: 16px;
  overflow: hidden;
  box-sizing: border-box;
}

.logs-wrap {
  flex: 1;
  padding: 20px;
  height: 100%;
  display: flex;
  flex-direction: column;
  color: var(--color-text-regular);
  overflow: auto;
  background-color: var(--bg-color);
  box-sizing: border-box;
}

.chart-group {
  margin: 0 -8px 20px;
}

.logs-toolbar {
  .search-input {
    max-width: 360px;
  }
  .trace-input {
    max-width: 280px;
  }
}

.logs-cont {
  flex: 1;
  min-height: 300px;
  display: flex;
  position: relative;
  padding-left: 188px;
  transition: padding-left 0.3s ease;
  overflow: hidden;

  &.is-collapsed {
    padding-left: 0;
  }

  .fixed-btn {
    position: absolute;
    left: -20px;
    top: 5px;
    width: 17px;
    height: 25px;
    line-height: 25px;
    background-color: var(--bg-color);
    border-radius: 0 4px 4px 0;
    box-shadow: 0 1px 4px 0 rgba(139, 142, 147, 0.3);
  }
}

.logs-filter {
  height: 100%;
  position: absolute;
  left: 0;
  top: 0;
}

.choose-collapse {
  width: 188px;
  height: 100%;
  padding-right: 8px;
  background-color: var(--bg-color);
  opacity: 1;
  transition: transform 0.3s ease, opacity 0.3s ease;

  &.is-collapsed {
    transform: translateX(-200px);
    opacity: 0;
  }

  .choose-collapse-title {
    border-top: 1px solid var(--border-color-base);
    line-height: 28px;
    height: 36px;
    border-bottom: 1px solid var(--border-color-base);
    margin-right: 12px;
  }

  .choose-collapse-body {
    flex: 1;
    overflow: hidden;
    position: relative;
  }
}

.filter-collapse {
  border: none;

  :deep(.el-collapse-item) {
    padding: 2px 0;
    &:not(:last-child) {
      border-bottom: 1px solid var(--border-color-base);
    }
  }

  :deep(.el-collapse-item__header) {
    display: block;
    padding-left: 20px;
    padding-right: 8px;
    height: 28px;
    line-height: 28px;
    background-color: transparent;
    border: none;
    border-radius: 3px;
    font-size: 12px;
    font-weight: normal;
    position: relative;

    .el-collapse-item__arrow {
      position: absolute;
      top: 7px;
      left: 0;
    }
    &:hover {
      background-color: var(--bg-color03);
    }
  }

  :deep(.el-collapse-item__wrap) {
    background-color: transparent;
    border: none;
    .el-collapse-item__content {
      padding-bottom: 0;
    }
  }

  .filter-checkbox {
    margin: 2px 0;
    padding: 3px 0;
    min-height: 24px;
    border-radius: 3px;
    display: flex;
    align-items: center;
    line-height: 20px;
    color: var(--color-text-regular);
    font-weight: normal;
    &:hover {
      background-color: var(--bg-color03);
    }
    :deep(.el-checkbox__label) {
      font-size: 12px;
      white-space: normal;
      line-height: 1.4;
    }
    &.is-error :deep(.el-checkbox__label::before),
    &.is-warn :deep(.el-checkbox__label::before),
    &.is-info :deep(.el-checkbox__label::before),
    &.is-muted :deep(.el-checkbox__label::before) {
      content: '';
      margin-right: 5px;
      display: inline-block;
      width: 8px;
      height: 8px;
      border-radius: 4px;
      background-color: var(--color-info);
    }
    &.is-error :deep(.el-checkbox__label::before) {
      background-color: var(--color-danger);
    }
    &.is-warn :deep(.el-checkbox__label::before) {
      background-color: var(--color-warning);
    }
    &.is-info :deep(.el-checkbox__label::before) {
      background-color: var(--color-success);
    }
  }

  .filter-empty {
    margin-left: 20px;
    font-size: 12px;
  }

  .filter-clear-btn {
    font-size: 12px;
    color: var(--color-text-secondary);
    padding: 2px 4px;
    &:hover {
      color: var(--color-text-primary);
    }
  }
}

.logs-table {
  flex: 1;
  min-width: 0;
  overflow: hidden;

  :deep(.scroll-el-table-header) {
    padding-top: 0;
  }
}

.log-level-tag {
  display: inline-block;
  padding: 0 8px;
  line-height: 20px;
  border-radius: 3px;
  font-size: 12px;
  background-color: var(--bg-color03);
  color: var(--color-text-regular);

  &.is-error {
    color: var(--color-danger);
    background-color: rgba(var(--color-danger-rgb, 245, 108, 108), 0.12);
  }
  &.is-warn {
    color: var(--color-warning);
    background-color: rgba(var(--color-warning-rgb, 230, 162, 60), 0.12);
  }
  &.is-info {
    color: var(--color-success);
    background-color: rgba(var(--color-success-rgb, 103, 194, 58), 0.12);
  }
}
</style>
