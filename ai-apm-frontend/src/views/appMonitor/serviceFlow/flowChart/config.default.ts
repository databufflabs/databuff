import G6 from '@antv/g6';
import { rectConfig, colors, getFlowNodeCardHeight } from './register';
import { FLOW_NODE, FLOW_EDGE } from './index.types'

export default function getDefaultCfg () {
  return {
    width: 400,
    height: 400,
    minZoom: 0.3,
    maxZoom: 1.5,
    animate: true,
    animateCfg: {
      duration: 300,
      easing: 'easePolyIn',
    },
    defaultNode: {
      type: FLOW_NODE.name,
    },
    defaultEdge: {
      type: FLOW_EDGE.name,
      style: {
        stroke: colors.edge,
        lineWidth: 1,
        endArrow: {
          path: G6.Arrow.triangle(10, 8, 0),
          fill: colors.edge,
          stroke: 'transparent',
          d: 0.75,
        },
      },
    },
    layout: {
      type: 'compactBox',
      direction: 'LR',
      getWidth: () => rectConfig.width,
      getHeight: (d: any) => getFlowNodeCardHeight(d),
      getHGap: () => 65,
      getVGap: () => 35,
    },
    padding: [20, 50],
    modes: {
      default: ['drag-canvas', 'zoom-canvas'],
      // 自定义大数据量模式
      optimize: [
        {
          type: 'drag-canvas',
          enableOptimize: true
        },
        {
          type: 'zoom-canvas',
          enableOptimize: true
        },
      ]
    },
  }
}
