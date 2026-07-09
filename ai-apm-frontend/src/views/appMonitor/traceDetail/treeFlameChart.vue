<template>
  <div
    ref="flameChart"
    :style="{ height: chartHeight + 'px' }"
    class="trace-flame-chart"></div>
</template>

<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import { Getter, State } from 'vuex-class';
import { debounce } from '@/utils/common';
import humanFormat from 'human-format';

const BAR_ITEM_HEIGHT = 47;
const BAR_TOP_GAP = 40;
const nsFormat = new humanFormat.Scale({
  ns: 1,
  µs: 1000,
  ms: 1000000,
  s: 1e9,
  min: 60 * 1e9,
})

@Component
export default class TreeFlameChart extends Vue {
  @Getter('themeVariables') private themeVars!: any;
  @State('themeChanged') private themeChanged!: boolean;

  @Prop({ default: () => ({}) }) private sourceMapping!: any;  // 全部数据id映射
  @Prop({ default: () => [] }) private displayedIds!: string[]; // 可见数据的ids
  @Prop({ default: '' }) private activeId!: string;     // 当前选中的id
  @Prop({ default: 200 }) private chartHeight!: number; // 容器高度

  public $refs!: {
    flameChart: HTMLDivElement
  }
  private chartLegends = ['web', 'db', 'cache', 'mq', 'custom', 'error']

  private chartDataIds: string[] = []; // 图表数据的ids
  private myChart: any = {};
  private resizeHandler: any = null;

  get propDataStr () {
    return JSON.stringify({
      displayedIds: this.displayedIds,
      activeId: this.activeId,
      chartHeight: this.chartHeight,
    })
  }
  @Watch('propDataStr')
  private onPropDataStrChanged(newVal: any, oldVal: any) {
    if (newVal === oldVal) {
      return
    }
    this.$nextTick(() => {
      if (this.myChart) {
        this.updateEcharts();
      } else {
        this.drawEcharts();
      }
    });
  }
  @Watch('themeChanged')
  private onThemeChanged(value: boolean) {
    if (value) {
      this.$nextTick(() => {
        if (this.myChart) {
          this.updateEcharts();
        } else {
          this.drawEcharts();
        }
      });
    }
  }

  private mounted() {
    this.$nextTick(() => {
      this.drawEcharts();
    })
    this.resizeHandler = debounce(() => {
      if (this.myChart) {
        this.myChart.resize()
      }
    }, 100)
    window.addEventListener('resize', this.resizeHandler)
  }

  private beforeDestroy() {
    window.removeEventListener('resize', this.resizeHandler)
    if (!this.myChart) {
      return
    }
    if (this.myChart.off) {
      this.myChart.off('click');
    }
    if (this.myChart.dispose) {
      this.myChart.dispose();
    }
    this.myChart = null
  }

  private drawEcharts () {
    this.myChart = this.$echarts.init(this.$refs.flameChart);
    this.myChart.setOption(this.getOption());

    // 元素的点击事件
    this.myChart.on('click', (params: any) => {
      this.$emit('on-click', params);
    });
  }

  private updateEcharts () {
    this.myChart.clear();
    this.myChart.setOption(this.getOption(), true); // clear cache
    this.myChart.resize();
  }

  private getOption () {
    const colors: any = {
      web: '#5180FF',
      db: '#2BB9F1',
      cache: '#8F77FA',
      mq: '#35C8C0',
      custom: '#F79F46',
      error: this.themeVars.colorDanger,
      slow: this.themeVars.colorWarning,
      browser: '#d9d9d9'
    }

    const categories: string[] = []
    const pointData: any[] = []
    this.chartDataIds = [...this.displayedIds].reverse();
    const startNsList = this.chartDataIds.map((id) => {
      const span = this.sourceMapping[id] || {}
      const startNs = Number(span.startNs)
      if (Number.isFinite(startNs) && startNs > 0) {
        return startNs
      }
      const startMs = Number(span._start) || 0
      return startMs > 0 ? startMs * 1_000_000 : 0
    })
    const validStartNs = startNsList.filter((startNs: number) => startNs > 0)
    const traceStartNs = validStartNs.length ? Math.min(...validStartNs) : 0
    this.chartDataIds.forEach((id, index) => {
      const span = this.sourceMapping[id] || {}
      const { resource, duration, service_type, error } = span
      const startNs = startNsList[index]
      let relativeTime = Number(span.relativeTime)
      if (!Number.isFinite(relativeTime) || (relativeTime === 0 && startNs > traceStartNs)) {
        relativeTime = traceStartNs > 0 && startNs > 0 ? startNs - traceStartNs : 0
      }
      const color = colors[service_type] || colors.custom;
      categories.push(resource);
      pointData.push({
        name: resource,
        value: [index, relativeTime, relativeTime + duration, duration],
        itemStyle: {
          normal: {
            color,
            borderWidth: error ? 2 : 0,
            borderColor: error ? (error === 2 ? colors.slow : colors.error) : color,
          }
        }
      })
    });

    const options = {
      tooltip: {
        backgroundColor: this.themeVars.tooltipBgColor,
        borderWidth: 0,
        padding: [10, 14],
        textStyle: {
          fontSize: 12,
          color: this.themeVars.colorTextRegular,
        },
        enterable: false,
        confine: true,
        appendToBody: true, // 是否将tootip放到body内
        extraCssText: `box-shadow:1px 1px 4px 0 ${this.themeVars.tooltipShadowColor};max-width:400px;max-height:300px;overflow:auto;word-break:break-all;white-space:normal;`,
        formatter: (params: any) => {
          const _val = Math.max(0, params.value[3])
          const maxShowName = params.name.length > 40 ? `${params.name.substring(0, 40)} ...` : params.name
          return params.marker + maxShowName + ': ' + humanFormat(Number(_val || 0), { scale: nsFormat });
        },
      },
      grid: {
        show: true,
        top: BAR_TOP_GAP,
        left: 10,
        right: 20,
        bottom: 0,
        height: this.chartHeight - BAR_TOP_GAP,
        borderWidth: 0,
      },
      xAxis: {
        position: 'top',
        top: BAR_TOP_GAP,
        min: 0,
        splitLine: {
          lineStyle: {
            color: this.themeVars.borderColorLight
          }
        },
        splitNumber: 3,
        axisLine: {
          show: true,
          lineStyle: {
            color: this.themeVars.borderColorLight
          }
        },
        scale: true,
        axisLabel: {
          color: this.themeVars.colorTextSecondary,
          formatter: (val: any) => {
            const _val = Math.max(0, val)
            return humanFormat(Number(_val || 0), { scale: nsFormat })
          }
        }
      },
      yAxis: {
        data: categories,
        axisTick: { show: false },
        splitLine: { show: false },
        axisLine: { show: false },
        axisLabel: { show: false },
        boundaryGap: [0, 0]
      },
      series: [
        {
          type: 'custom',
          renderItem: this.renderItem,
          itemStyle: {
            // opacity: 0.8
          },
          encode: {
            x: [1, 2],
            y: 0
          },
          data: pointData,
          animation: false,
        }
      ]
    }

    return options
  }
  private renderItem (params: any, api: any) {
    const activeIndex = this.chartDataIds.findIndex(t => t === this.activeId)
    const categoryIndex = api.value(0);
    const start = api.coord([api.value(1), categoryIndex]);
    const end = api.coord([api.value(2), categoryIndex]);
    const height = BAR_ITEM_HEIGHT;
    const realWidth = end[0] - start[0];
    const rectShape = this.$echarts.graphic.clipRectByRect(
      {
        x: start[0],
        y: start[1] - height / 2 + BAR_ITEM_HEIGHT / 4,
        width: Math.max(2, realWidth),
        height: height / 2
      },
      {
        x: params.coordSys.x,
        y: params.coordSys.y,
        width: params.coordSys.width,
        height: params.coordSys.height
      }
    );
    const isActive = activeIndex > -1 && categoryIndex === activeIndex
    const linkColor = this.themeVars.colorTextLink || '#5180FF'
    return {
      type: 'group',
      children: [
        {
          type: 'rect',
          transition: ['shape'],
          shape: rectShape,
          style: {
            ...api.style(),
            ...(isActive ? {
              stroke: linkColor,
              lineWidth: 2,
            } : {}),
          },
          textContent: {
            style: {
              text: humanFormat(Number(api.value(3) || 0), { scale: nsFormat }),
              fontSize: 12
            }
          },
          textConfig: {
            position: realWidth <= 30 ? 'right' : 'insideLeft',
            insideFill: '#fff',
            outsideFill: this.themeVars.colorTextRegular,
          },
        },
      ]
    }
  }

  public resize () {
    if (this.myChart && this.myChart.resize) {
      this.myChart.resize()
    }
  }
}
</script>

<style lang="scss" scoped>
.trace-flame-chart {
  width: 100%;
  height: 100%;
  overflow: hidden;
  position: relative;
}
.chart-legend {
  position: absolute;
  bottom: 0;
  right: 0;
  display: flex;
  flex-wrap: nowrap;
  white-space: nowrap;
  .chart-legend-item {
    display: inline-flex;
    align-items: center;
    line-height: 22px;
    & + .chart-legend-item {
      margin-left: 15px;
    }
    &::before {
      content: '';
      box-sizing: border-box;
      margin-right: 5px;
      display: inline-block;
      width: 10px;
      height: 10px;
      border-radius: 1;
    }
    &[data-type="web"]::before {
      background: #5180FF;
    }
    &[data-type="db"]::before {
      background: #2BB9F1;
    }
    &[data-type="cache"]::before {
      background: #8F77FA;
    }
    &[data-type="mq"]::before {
      background: #35C8C0;
    }
    &[data-type="custom"]::before {
      background: #F79F46;
    }
    &[data-type="error"]::before {
      border: 2px solid var(--color-danger);
    }
  }
}
</style>
