<template>
  <div class="time-choose-wrapper flex-h">
    <el-button
      v-if="isCustomRange"
      @click="prevTimeDurationHandle"
      :disabled="disabledPrev"
      icon="el-icon-arrow-left"
      size="mini"
      class="time-choose-trigger time-choose-prev cp ml-10">
    </el-button>

    <el-popover v-show='showTimeRange'
      @show="updateNowTime"
      placement="bottom-end" popper-class="time-choose-popover" :visible-arrow='false' class="ml-10">
      <div slot="reference" class="time-choose-trigger flex-h cp">
        <span class="db-icon-time font-12"></span>
        <span class="time-choose-trigger-value">{{ dateShowValue }}</span>
        <span class="el-icon-arrow-down"></span>
      </div>

      <div
        :class="['time-choose-panel flex-h', {
          'time-choose-panel-small': getCalcRecentlyList.length <= 2,
          'auto-height': singleModel,
          'time-step-height': !!timeRageStep,
        }]">
        <div v-show='!singleModel' class="time-choose-picker-group flex-v">
          <div class="time-choose-picker">
            <h5>
              {{ $t('modules.views.layout.s_87a29491') }}
              <span v-show='limitInfo' class="custom-time-tip describe">{{ limitInfo }}</span>
            </h5>
            <div class="time-choose-picker-wrapper">
              <el-form ref='timeDateForm' :model='timeDateForm' :rules='timeDateRules' label-position='top'>
                <div class="time-choose-range-row">
                  <el-form-item :label="$t('modules.views.configManage.alarm.s_592c5958')" prop='fromTime' class="time-form-item">
                    <el-date-picker ref='fromTimePicker' v-model='timeDateForm.fromTime' size='mini' type='datetime'
                      @change='revalidHandle'
                      :picker-options="pickerOptions"
                      format="yyyy-MM-dd HH:mm"
                      prefix-icon="el-icon-date"
                      popper-class="time-choose-datepicker"></el-date-picker>
                  </el-form-item>
                  <el-form-item v-if="!timeRageStep" :label="$t('modules.components.charts.s_f782779e')" prop='toTime' class="time-form-item">
                    <el-date-picker ref='toTimePicker' v-model='timeDateForm.toTime' size='mini' type='datetime'
                      @change='revalidHandle'
                      :picker-options="pickerOptions"
                      format="yyyy-MM-dd HH:mm"
                      prefix-icon="el-icon-date"
                      popper-class="time-choose-datepicker"></el-date-picker>
                  </el-form-item>
                </div>
                <div class="mb-10">
                  <el-button @click="applyTimeRangeHandle" type="primary" size='mini' class="time-choose-picker-confirm">{{ $t('modules.views.infrastructure.hostDetail.s_5b0520a9') }}</el-button>
                </div>
              </el-form>
            </div>
          </div>
          <div class="flex-placeholder-empty"></div>
          <div class="time-choose-recently">
            <h6>{{ $t('modules.views.layout.s_fe3b09a8') }}</h6>
            <div class="time-choose-recently-wrapper">
              <div v-for='recent,ridx in getCalcRecentlyList' :key='ridx'
                @click="chooseRecentlyOptionHandle(recent, ridx)"
                class="time-choose-selector-option describe small-font">{{ recent.labelKey ? $t(recent.labelKey) : recent.label }}</div>
              <div v-if='!getCalcRecentlyList.length' class="empty-text">{{ $t('modules.views.alarmCenter.alarm.s_d81bb206') }}</div>
            </div>
          </div>
        </div>

        <div v-show='!timeRageStep' class="time-choose-selector flex-v">
          <h6>{{ $t('modules.views.layout.s_6ae9cef4') }}</h6>
          <div class="time-choose-selector-wrapper">
            <div v-for='option in getCalcLimitTimeRangeMsOptions' :key='option.value'
              @click="chooseQuickOptionHandle(option)"
              class="time-choose-selector-option">
              {{ option.labelKey ? $t(option.labelKey) : option.label }}
            </div>
          </div>
        </div>
      </div>
    </el-popover>

    <el-button
      v-if="isCustomRange"
      @click="nextTimeDurationHandle"
      :disabled="disabledNext"
      size='mini'
      icon="el-icon-arrow-right"
      class="time-choose-trigger time-choose-next cp">
    </el-button>

    <!-- 用于手动隐藏popover -->
    <span class="hidden-span" ref='hiddenSpan'></span>
  </div>
</template>

<script lang='ts'>
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import i18n from '@/i18n';
import { TimeRangeMsOptions } from '@/router/time-new';
import { setDateBySeconds } from '@/utils/timeFormat';
import { Getter, namespace } from 'vuex-class';
import { FullPropMenu } from '@/router/route.types';

import { Form } from 'element-ui';
import dayjs from 'dayjs'

const DEFAULT_LIMIT_DAY = 31;

const UserModel = namespace('User');

interface TROption {
  label: string;
  type: TimeChooseType;
  value?: number;
  fromTime?: number;
  toTime?: number;
  duration?: number;
}

enum TimeChooseType {
  SELECT = 'select',
  CUSTOM = 'custom'
}

@Component
export default class DbFooterTime extends Vue {
  @Getter('globalTime') private globalTimeFunc!: any;
  @Getter('globalTimeInited') private globalTimeInited!: any;
  @UserModel.State private currMenu!: FullPropMenu | null;

  public $refs!: {
    timeDateForm: Form;
    hiddenSpan: HTMLSpanElement;
    fromTimePicker: { placement?: string; currentPlacement?: string };
    toTimePicker?: { placement?: string; currentPlacement?: string };
  }

  @Watch('$route', { immediate: true })
  private onRouterChange (newVal: any) {
    if (this.globalTimeInited) {
      this.updateTime()
    }
  }

  // select | custom
  private chooseType: TimeChooseType = TimeChooseType.SELECT;

  private dateShowValue = '最近1小时';

  get singleModel () {
    return this.currMenu && this.currMenu.time === 'latest'
  }

  get isCustomRange () {
    return this.dateShowValue.indexOf(i18n.t('modules.views.layout.s_046c6233') as string) === -1
  }

  // 支持前后切换的分钟间隔
  get timeRageStep () {
    let step = 0
    const time = `${(this.currMenu || {}).time || ''}`;
    if (time.indexOf('step-') === 0) {
      step = Number(time.replace('step-', ''))
    }
    return isNaN(step) ? 0 : step
  }
  // 切换时间间隔是在快捷选项的Option中，没有则为null
  get timeStepOption () {
    const duration = this.timeRageStep * 60 * 1000
    return this.getCalcLimitTimeRangeMsOptions.find(t => t.value === duration) || null
  }

  get showTimeRange () {
    return this.currMenu && !!this.currMenu.time
  }

  private initNowTime: Date = setDateBySeconds(new Date(), 0) // 当前时间
  get disabledFrom () { // 从何时禁止
    return new Date(+this.initNowTime - 1000 * 3600 * 24 * DEFAULT_LIMIT_DAY);
  }
  // 单次时间跨度（天）
  get limitDays () {
    return (this.currMenu || {}).limitDays || DEFAULT_LIMIT_DAY
  }
  get limitInfo () {
    if (this.limitDays >= DEFAULT_LIMIT_DAY) {
      return i18n.t('modules.views.layout.s_4fb889e1', { value0: DEFAULT_LIMIT_DAY }) as string
    }
    const limit = this.limitDays > 1 ? i18n.t('modules.views.layout.s_34447aaa', { value0: this.limitDays }) as string : i18n.t('modules.views.layout.s_408e524f', { value0: this.limitDays * 24 }) as string
    return i18n.t('modules.views.layout.s_3749e226', { value0: limit }) as string
  }

  // 限制快捷时间选项
  get getCalcLimitTimeRangeMsOptions () {
    const maxDuration = this.limitDays * 86400000
    return TimeRangeMsOptions.filter((item) => item.value <= maxDuration)
  }

  // 最近选择时间列表 - 本地记录
  private recentlyList: TROption[] = [];

  // 计算可选的最近选择时间
  get getCalcRecentlyList () {
    const maxDuration = this.limitDays * 86400000
    const list = this.recentlyList.filter((item: any) => {
      return item.duration <= maxDuration && item.fromTime >= +this.disabledFrom && item.toTime <= +this.initNowTime
    })
    // 过滤时间间隔 !== step的记录
    if (this.timeRageStep) {
      return list.filter((t: any) => Math.floor(t.duration / 1000 / 60) === this.timeRageStep)
    }
    return list
  }

  private timeDateForm = {
    fromTime: new Date(+this.initNowTime - 1000 * 3600),
    toTime: this.initNowTime,
  }

  private manualApplyStatus = false;

  // 定时器相关
  private timer: any = null;

  // 校验时间
  get timeDateRules () {
    const _initNowTime = this.initNowTime.getTime()
    const limitDuration = this.limitDays * 86400000
    const fromLimitTime = _initNowTime - DEFAULT_LIMIT_DAY * 86400000
    const { fromTime, toTime } = this.timeDateForm
    const limit = this.limitDays > 1 ? i18n.t('modules.views.layout.s_34447aaa', { value0: this.limitDays }) as string : i18n.t('modules.views.layout.s_408e524f', { value0: this.limitDays * 24 }) as string
    return {
      fromTime: [
        { trigger: 'blur', validator: (rule: any, value: any, cb: any) => {
          // 点击应用按钮时校验
          if (this.manualApplyStatus) {
            if (!value) {
              cb(new Error(i18n.t('modules.views.alarmCenter.rootCauseAnalysis.s_90fae33b') as string))
            } else if (!this.timeRageStep && toTime && +value > +toTime) {
              cb(new Error(i18n.t('modules.views.alarmCenter.rootCauseAnalysis.s_9863e93b') as string))
            } else if (+value < fromLimitTime) {
              cb(new Error(i18n.t('modules.views.layout.s_b86c8c05', { value0: DEFAULT_LIMIT_DAY }) as string))
            } else if (toTime && +toTime - +value > limitDuration) {
              cb(new Error(i18n.t('modules.views.layout.s_6ff8935b', { value0: limit }) as string))
            } else {
              cb()
            }
          } else {
            // 非点击模式直接跳过校验
            cb()
          }
        } },
      ],
      toTime: [
        { trigger: 'blur', validator: (rule: any, value: any, cb: any) => {
          // 点击应用按钮时校验
          if (this.manualApplyStatus) {
            if (!value) {
              cb(new Error(i18n.t('modules.views.alarmCenter.rootCauseAnalysis.s_69b76a1d') as string))
            } else if (fromTime && +value < +fromTime) {
              cb(new Error(i18n.t('modules.views.alarmCenter.rootCauseAnalysis.s_9402e822') as string))
            } else if (fromTime && +value > _initNowTime) {
              cb(new Error(i18n.t('modules.views.layout.s_d8f93ef7') as string))
            } else if (+value < fromLimitTime) {
              cb(new Error(i18n.t('modules.views.layout.s_b86c8c05', { value0: DEFAULT_LIMIT_DAY }) as string))
            } else if (fromTime && +value - +fromTime > limitDuration) {
              cb(new Error(i18n.t('modules.views.layout.s_6ff8935b', { value0: limit }) as string))
            } else {
              cb()
            }
          } else {
            // 非点击模式直接跳过校验
            cb()
          }
        } },
      ],
    }
  }

  get disabledPrev () {
    return this.timeDateForm.fromTime && this.timeDateForm.fromTime.getTime() <= +this.disabledFrom
  }
  get disabledNext () {
    return this.timeDateForm.toTime && this.timeDateForm.toTime.getTime() >= this.initNowTime.getTime()
  }

  get pickerOptions () {
    // 判断有无传入的disabledFrom
    const _disabledFrom = this.disabledFrom
    // 设置为当天时间的最大值，防止“此刻”无法点击
    const _initNowTime = new Date(+this.initNowTime)
    _initNowTime.setHours(23, 59, 59, 999)
    return {
      disabledDate (time: Date) {
        return time.getTime() > _initNowTime.getTime() || time.getTime() < _disabledFrom.getTime()
      }
    }
  }

  private mounted () {
    this.placeTimePickersOutside()
  }

  // Element DatePicker 的 align 只能落到 bottom-*，面板会盖住竖排的结束时间。
  // 钉到 left-start：日历开在输入框左侧/外侧，结束时间仍能直接点。
  private placeTimePickersOutside () {
    const pin = (picker?: { placement?: string; currentPlacement?: string }) => {
      if (!picker) return
      picker.placement = 'left-start'
      picker.currentPlacement = 'left-start'
    }
    pin(this.$refs.fromTimePicker)
    pin(this.$refs.toTimePicker)
  }

  private created () {
    // 初始化时间范围
    // 判断路由中有无时间范围
    // 判断开始时间是否超过 DEFAULT_LIMIT_DAY 天前
    // 判断开始时间是否大于结束时间
    // 根据标识显示文本 - manualType - custom / select
    this.updateTime()
    // 将 inited 置为 true,初始化完成，可以监听时间范围变化
    this.$store.commit('SET_DURATION_DATES_INIT_STATUS', true);
    const localRecently = window.localStorage.getItem('DB_TIMERANGE_RECENTLY')
    if (localRecently) {
      try {
        const _localRecently = JSON.parse(localRecently);
        if (Object.prototype.toString.call(_localRecently) === '[object Array]') {
          this.recentlyList = _localRecently.map((t: any) => {
            const fromTime = +setDateBySeconds(new Date(t.fromTime), 0)
            const toTime = +setDateBySeconds(new Date(t.toTime), 0)
            return {
              ...t,
              fromTime,
              toTime,
              duration: toTime - fromTime,
              label: `${dayjs(fromTime).format('YYYY-MM-DD HH:mm')} - ${dayjs(toTime).format('YYYY-MM-DD HH:mm')}`,
            }
          })
        }
      } catch (err) {
        //
      }
    }
  }

  private beforeDestroy() {
    if (this.timer) {
      window.clearTimeout(this.timer);
      this.timer = null;
    }
  }

  private updateTime () {
    this.initNowTime = setDateBySeconds(new Date(), 0)
    const { fromTime, toTime, durationRange } = this.$route.query
    const _fromTime = Number(fromTime)
    const _toTime = Number(toTime)
    const matchOption = this.getCalcLimitTimeRangeMsOptions.find((item) => item.value === +durationRange);
    if (fromTime && toTime && dayjs(_fromTime).isValid() && dayjs(_toTime).isValid()
      && _fromTime > +this.disabledFrom && _fromTime <= _toTime && (_toTime - _fromTime) <= this.limitDays * 86400000
      && _toTime <= +this.initNowTime) {
      this.timeDateForm.fromTime = setDateBySeconds(new Date(_fromTime), 0)
      this.timeDateForm.toTime = setDateBySeconds(new Date(_toTime), 0)
      this.dateShowValue = `${dayjs(_fromTime).format('YYYY-MM-DD HH:mm')} - ${dayjs(_toTime).format('YYYY-MM-DD HH:mm')}`
    } else if (matchOption) {
      this.timeDateForm.fromTime = new Date(+this.initNowTime - matchOption.value!)
      this.timeDateForm.toTime = this.initNowTime
      this.dateShowValue = matchOption.label
    }
  }

  private applyTimeRangeHandle () {
    // 应用自定义时间范围
    this.manualApplyStatus = true;
    this.chooseType = TimeChooseType.CUSTOM;
    this.$refs.timeDateForm.validate((valid: any) => {
      if (valid) {
        // 显示当前时间范围
        let from = dayjs(this.timeDateForm.fromTime).format('YYYY-MM-DD HH:mm')
        let to = dayjs(this.timeDateForm.toTime).format('YYYY-MM-DD HH:mm')
        // 时间可前后切换
        if (this.timeRageStep) {
          const duration = this.timeRageStep * 60 * 1000
          if (+this.initNowTime - +new Date(from) >= duration) {
            to = dayjs(+new Date(from) + duration).format('YYYY-MM-DD HH:mm')
          } else if (this.timeStepOption) {
            // 切换为最近时间
            this.chooseQuickOptionHandle(this.timeStepOption)
            return
          } else {
            from = dayjs(+this.initNowTime - duration).format('YYYY-MM-DD HH:mm')
            to = dayjs(+this.initNowTime).format('YYYY-MM-DD HH:mm')
          }
        }
        this.dateShowValue = `${from} - ${to}`;
        // 追加最近时间选项 & 本地记录
        this.commitDurationChange({
          label: this.dateShowValue,
          duration: +new Date(to) - +new Date(from),
          fromTime: +new Date(from),
          toTime: +new Date(to),
          type: TimeChooseType.CUSTOM,
        })
        // 关闭popover
        this.$refs.hiddenSpan.click();
      }
    })
  }

  private chooseQuickOptionHandle (option: TROption) {
    this.manualApplyStatus = false;
    this.chooseType = TimeChooseType.SELECT;
    // 是左侧面板时间联动
    this.commitDurationChange(option);
  }

  private chooseRecentlyOptionHandle (option: TROption, idx: number) {
    this.manualApplyStatus = true;
    this.chooseType = TimeChooseType.CUSTOM;
    // 是左侧面板时间联动
    this.commitDurationChange(option, idx);
  }

  // 触发时间变化
  private commitDurationChange (option: TROption, spliceIndex?: number) {
    // 更新initNowTime 用于计算next按钮是否可点击
    this.initNowTime = setDateBySeconds(new Date(), 0);
    // 格式化时间
    // 适用于快速选择最近xx时间段
    const { type, fromTime, toTime, value } = option
    const to = type === TimeChooseType.SELECT ? this.initNowTime : new Date(toTime!);
    const from = type === TimeChooseType.SELECT ? new Date(to.getTime() - value!) : new Date(fromTime!);
    // 显示当前选项文本
    this.dateShowValue = option.label
    this.timeDateForm.fromTime = new Date(dayjs(from).format('YYYY-MM-DD HH:mm'));
    this.timeDateForm.toTime = new Date(dayjs(to).format('YYYY-MM-DD HH:mm'));
    // 路由中添加range，用于刷新不丢失时间范围
    if (type === TimeChooseType.CUSTOM) {
      this.localAndRecently(spliceIndex);
      const _query = {
        ...this.$route.query
      }
      delete _query.durationRange
      this.$router.replace({
        query: {
          ..._query,
          fromTime: `${+this.timeDateForm.fromTime}`,
          toTime: `${+this.timeDateForm.toTime}`,
        }
      })
    } else if (type === TimeChooseType.SELECT) {
      const _query = {
        ...this.$route.query
      }
      delete _query.fromTime
      delete _query.toTime
      this.$router.replace({
        query: {
          ..._query,
          durationRange: `${option.value}`,
        }
      })
    }
    // 点击隐藏的span按钮，关闭popover
    if (this.$refs.hiddenSpan) {
      this.$refs.hiddenSpan.click();
    }
  }

  // 最近时间选项 & 本地记录
  // 追加 || 提前
  private localAndRecently (spliceIndex?: number) {
    // 最近时间点击，需将当前项提前，并非追加
    if (typeof spliceIndex === 'number') {
      const currOption: TROption[] = this.recentlyList.splice(spliceIndex, 1)
      this.recentlyList.unshift(currOption[0]);
    } else {
      const fromTime = +setDateBySeconds(new Date(this.timeDateForm.fromTime), 0)
      const toTime = +setDateBySeconds(new Date(this.timeDateForm.toTime), 0)
      this.recentlyList.unshift({
        label: this.dateShowValue,
        fromTime,
        toTime,
        duration: toTime - fromTime,
        type: TimeChooseType.CUSTOM,
      });
    }
    if (this.recentlyList.length > 4) {
      this.recentlyList.splice(4)
    }
    window.localStorage.setItem('DB_TIMERANGE_RECENTLY', JSON.stringify(this.recentlyList))
  }

  // 重新触发一次表单校验，防止不同步
  private revalidHandle () {
    this.updateNowTime()
    // 设置时间的秒、毫秒为0，防止点击“此刻”的时间有秒、毫秒
    if (this.timeDateForm.fromTime) {
      this.timeDateForm.fromTime = setDateBySeconds(this.timeDateForm.fromTime, 0);
    }
    if (this.timeDateForm.toTime) {
      this.timeDateForm.toTime = setDateBySeconds(this.timeDateForm.toTime, 0);
    }
    this.$refs.timeDateForm.validate((valid) => {
      // do nothing
    })
  }

  // 时间范围 prev按钮
  private prevTimeDurationHandle () {
    this.manualApplyStatus = true
    this.chooseType = TimeChooseType.CUSTOM
    // 开始时间与结束时间之间的间隔，是否大于开始时间与disabledFrom之间的间隔
    // 小于则向前平移 toTime = fromTime
    // 大于则变更为 disabledFrom ~ (disabledFrom + durationRange)
    const fromValue = +this.timeDateForm.fromTime
    const toValue = +this.timeDateForm.toTime
    const duration = Math.abs(toValue - fromValue)
    const maxDuration = Math.abs(fromValue - +this.disabledFrom)
    const from = maxDuration > duration ? new Date(fromValue - duration) : new Date(+this.disabledFrom)
    const to = maxDuration > duration ? new Date(fromValue) : new Date(+this.disabledFrom + duration)
    this.dateShowValue = `${dayjs(from).format('YYYY-MM-DD HH:mm')} - ${dayjs(to).format('YYYY-MM-DD HH:mm')}`
    // 追加最近时间选项 & 本地记录
    this.commitDurationChange({
      label: this.dateShowValue,
      duration,
      fromTime: +from,
      toTime: +to,
      type: TimeChooseType.CUSTOM,
    })
  }
  // 时间范围 next按钮
  private nextTimeDurationHandle () {
    // 开始时间与结束时间之间的间隔，是否大于结束时间与initNowTime之间的间隔
    // 小于则向后平移 fromTime = toTime
    // 大于则变更为 (initNowTime - durationRange) ~ initNowTime
    const fromValue = +this.timeDateForm.fromTime
    const toValue = +this.timeDateForm.toTime
    const duration = Math.abs(toValue - fromValue)
    const maxDuration = Math.abs(+this.initNowTime - toValue)

    // 时间间隔是否在快捷选项的Option中，没有则为null
    const durationOption = this.getCalcLimitTimeRangeMsOptions.find(t => t.value === duration) || null
    if (maxDuration <= duration && durationOption) {
      // 最近时间
      this.chooseQuickOptionHandle(durationOption)
      return
    }

    // 自定义时间
    this.manualApplyStatus = true
    this.chooseType = TimeChooseType.CUSTOM
    const from = maxDuration > duration ? new Date(toValue) : new Date(+this.initNowTime - duration)
    const to = maxDuration > duration ? new Date(toValue + duration) : this.initNowTime
    this.dateShowValue = `${dayjs(from).format('YYYY-MM-DD HH:mm')} - ${dayjs(to).format('YYYY-MM-DD HH:mm')}`
    // 追加最近时间选项 & 本地记录
    this.commitDurationChange({
      label: this.dateShowValue,
      duration,
      fromTime: +from,
      toTime: +to,
      type: TimeChooseType.CUSTOM,
    })
  }

  // 更新当前时间
  private updateNowTime () {
    this.initNowTime = setDateBySeconds(new Date(), 0)
    this.placeTimePickersOutside()
  }
}
</script>

<style lang='scss' scoped>
.time-choose-trigger {
  padding: 0 8px;
  height: 32px;
  line-height: 30px;
  display: flex;
  align-items: center;
  background-color: var(--background-color-base);
  color: var(--color-text-primary);
  transition: all .3s ease;
  position: relative;
  font-size: 13px;
  border-radius: 4px;

  .time-choose-icon {
    margin-top: -2px;
    display: inline-block;
    vertical-align: middle;
    fill: var(--color-text-primary);
  }

  .time-choose-trigger-value {
    padding: 0 20px 0 8px;
  }

  &.is-disabled {
    opacity: 0.5;
    border-color: var(--border-color-base);
  }

  &:not(.is-disabled):hover {
    background-color: #1c2730;
    border-color: #1b3a56;
    color: #fff;
    z-index: 1;
    .time-choose-icon {
      fill: #fff;
    }
  }
}

.time-choose-prev {
  margin-right: -11px;
  padding: 0 8px;
  border: none;
  border-radius: 4px 0 0 4px;
  .time-choose-icon {
    transform: rotate(90deg);
  }
  & + span .time-choose-trigger {
    border-radius: 0;
  }
}
.time-choose-next {
  margin-left: -1px;
  padding: 0 8px;
  border: none;
  border-radius: 0 4px 4px 0;
  .time-choose-icon {
    transform: rotate(-90deg);
  }
}

.time-choose-panel {
  // min-height: 320px;
  height: 395px;
  align-items: flex-start;
  &.time-choose-panel-small {
    height: 374px;
  }
  &.auto-height {
    height: auto;
  }
  &.time-step-height {
    height: 315px;
    &.time-choose-panel-small {
      height: 263px;
    }
    .time-choose-picker-group {
      width: 263px;
    }
  }

  .time-choose-picker-group {
    width: 468px;
    height: 100%;
    border-right: 1px solid var(--border-color-lighter);

    h5 {
      margin: 0 0 16px 0;
      font-weight: normal;
    }

    h6 {
      font-size: 12px;
      margin: 8px 0 5px 8px;
      font-weight: normal;
    }
    .time-choose-picker {
      padding: 8px 0 0 8px;

      .custom-time-tip {
        font-size: 12px;
      }
    }
    .time-choose-picker-confirm {
      width: 100%;
    }

    .time-choose-range-row {
      display: flex;
      align-items: flex-start;
      gap: 12px;

      .time-form-item {
        flex: 1;
        min-width: 0;
        margin-bottom: 16px;
      }

      :deep(.el-date-editor.el-input) {
        width: 100%;
      }
    }

    .flex-placeholder-empty {
      flex: 1;
    }
    .time-choose-recently {
      padding: 0 0 8px 0;
      overflow: hidden;

      .time-choose-recently-wrapper {

        .time-choose-selector-option:hover {
          color: var(--color-text-regular);
        }
      }
    }
  }

  .time-form-item {
    margin-bottom: 20px;

    :deep(.el-form-item__label) {
      line-height: 1;
      font-size: 12px;
      color: var(--color-text-secondary);
      padding: 0;
    }
  }
  .time-choose-selector {
    width: 150px;
    overflow: hidden;
    flex: 1;
    height: 100%;

    & > h6 {
      margin: 8px 0 10px 8px;
      font-weight: normal;
    }

    .time-choose-selector-wrapper {
      flex: 1;
      overflow-y: auto;
    }
  }
  .time-choose-selector-option {
    padding: 7px 9px;
    transition: all .3s ease;
    cursor: pointer;

    &.active, &:hover {
      background-color: var(--background-color-base);
    }
  }
  .empty-text {
    font-size: 12px;
    color: var(--color-text-secondary);
    margin-left: 8px;
  }
  .small-font {
    font-size: 12px;
  }
}

.hidden-span {
  opacity: 0;
  width: 0;
  height: 0;
  font-size: 0;
  user-select: none;
}

:root[data-theme=light] {
  .time-choose-trigger:hover {
    color: var(--color-primary);
    background: #e8f2fc;
    border-color: #a2cbf1;
    .time-choose-icon {
      fill: var(--color-primary);
    }
  }
}
</style>

<style lang="scss">
.el-popover.el-popper.time-choose-popover {
  margin-top: 5px;
  border-radius: 2px;
  padding: 0;
}
.el-picker-panel.el-popper.time-choose-datepicker {
  margin-top: 5px;
  width: 270px;

  .el-date-picker__header {
    margin: 6px;
  }
  .el-date-picker__header .el-date-picker__header-label {
    font-size: 14px;
  }
  .el-date-picker__header .el-picker-panel__icon-btn {
    margin-top: 4px;
  }
  .el-picker-panel__content {
    width: 256px;
    margin: 6px;
  }
  .el-picker-panel__content .el-date-table td {
    width: 26px;
    height: 24px;
    padding: 2px;
  }
  .el-picker-panel__content .el-date-table td div {
    height: 24px;
    padding: 0;
  }
}
</style>
