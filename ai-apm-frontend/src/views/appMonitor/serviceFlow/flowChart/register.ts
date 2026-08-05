import i18n from '@/i18n';
import G6, { IEdge } from '@antv/g6';
import { NsFilter } from '@/utils/filters/times'
import { NumberFilter, PercentFilter } from '@/utils/filters/number'
import { getDbIcon } from '@/assets/fonts/db-find-icon';
import { FLOW_NODE, FLOW_EDGE } from './index.types'

type G6Type = typeof G6;

export const colors: any = {
  border: '#EEEFF1',
  background: '#FFFFFF',
  headerBg: '#F5F6F7',
  headerText: '#121317',
  headerArrow: '#777A7E',
  contText: '#777A7E',
  contValue: '#121317',
  percent: '#2962FF',
  percentBg: '#EEEFF1',
  expandFill: '#FFFFFF',
  expandStroke: '#777A7E',
  borderActive: '#2962FF',
  headerBgActive: '#2962FF',
  headerTextActive: '#FFFFFF',
  headerArrowActive: '#FFFFFF',
  edge: '#777A7E',
  edgeText: '#121317',
  edgeActive: '#2962FF',
}

// 多行文本
const multipleFittingString = (str: string, maxWidth: number, fontSize: number, maxLine: number) => {
  let currentWidth = 0;
  let splitIdx = 0;
  const splits: number[] = [];
  const pattern = new RegExp('[\u4E00-\u9FA5]+'); // distinguish the Chinese charactors and letters
  str.split('').forEach((letter, i) => {
    if (currentWidth > maxWidth) {
      splitIdx = i;
      splits.push(splitIdx);
      currentWidth = 0;
    };
    if (pattern.test(letter)) {
      // Chinese charactors
      currentWidth += fontSize;
    } else {
      // get the width of single letter according to the fontSize
      currentWidth += G6.Util.getLetterWidth(letter, fontSize);
    }
    
  });
  if (str && !splits.length) {
    splits.push(str.length);
  }
  let result: string[] = [];
  splits.forEach((idx, i) => {
    if (i === 0) {
      result.push(str.slice(0, idx));
    } else {
      result.push(str.slice(splits[i - 1], idx));
    }
    if (i === splits.length - 1) {
      result.push(str.slice(idx));
    }
  });
  result = result.filter(i => i);
  const rst = result.splice(0, maxLine);
  if (result.length > 0) {
    const n = rst[rst.length - 1].length
    rst[rst.length - 1] = `${rst[rst.length - 1].substring(0, n - 2)}...`
  }
  return {
    text: rst.join('\n'),
    line: rst.length
  }
};

export const rectConfig = {
  width: 188,
  // 单行标题时的收起/展开高度；标题需 2 行时再叠加 TITLE_LINE_EXTRA
  height: 90,
  expandHeight: 138,
  lineWidth: 2,
  fontSize: 12,
  radius: 6,
  opacity: 1,
};

const ROOT_BASE_HEIGHT = 102;

const nodeOrigin = {
  x: -rectConfig.width / 2,
  y: -rectConfig.height / 2,
  padding: 12,
};

const HEADER_HEIGHT_SINGLE = 32;
const HEADER_HEIGHT_DOUBLE = 46;
export const TITLE_LINE_EXTRA = HEADER_HEIGHT_DOUBLE - HEADER_HEIGHT_SINGLE; // 14

const rectHeaderConfig = {
  width: rectConfig.width - 2,
  height: HEADER_HEIGHT_SINGLE,
  stroke: null,
  opacity: 1,
  radius: [rectConfig.radius - 1.2, rectConfig.radius - 1.2, 0, 0]
}

// 卡片标题
const rectHeaderTitleConfig = {
  fontSize: 13,
  fontWeight: 400,
  lineHeight: 16,
  textBaseline: 'top' as const,
}

const HEADER_TITLE_MAX_LINE = 2;
const HEADER_OFFSET_Y = 2;
// 普通节点 / 根节点标题可用宽度（扣除图标与右侧按钮）
const TITLE_MAX_WIDTH_NODE = rectHeaderConfig.width - 63;
const TITLE_MAX_WIDTH_ROOT = rectHeaderConfig.width - 48;

const headerIconCenterY = (cardY: number, headerHeight: number) =>
  cardY + HEADER_OFFSET_Y + headerHeight / 2;

/** 按服务名实际行数（最多 2 行）计算 header 与卡片附加高度 */
const resolveHeaderTitle = (name: string, maxWidth: number) => {
  const fitted = multipleFittingString(
    name || '-',
    maxWidth,
    rectHeaderTitleConfig.fontSize,
    HEADER_TITLE_MAX_LINE,
  );
  const lines = Math.max(fitted.line, 1);
  const headerHeight = lines > 1 ? HEADER_HEIGHT_DOUBLE : HEADER_HEIGHT_SINGLE;
  const extra = lines > 1 ? TITLE_LINE_EXTRA : 0;
  return {
    text: fitted.text || '-',
    lines,
    headerHeight,
    extra,
    titleYOffset:
      HEADER_OFFSET_Y +
      (headerHeight - lines * rectHeaderTitleConfig.lineHeight) / 2,
  };
};

/** 布局用：按节点名是否换行返回卡片高度 */
export const getFlowNodeCardHeight = (d: any) => {
  const isRoot = d?.type === FLOW_NODE.rootName;
  const maxWidth = isRoot ? TITLE_MAX_WIDTH_ROOT : TITLE_MAX_WIDTH_NODE;
  const lines = resolveHeaderTitle(d?.name, maxWidth).lines;
  // 布局沿用收起态基准高度（与原先 getHeight 一致），仅对 2 行标题加高
  return rectConfig.height + (lines > 1 ? TITLE_LINE_EXTRA : 0);
};

// 单行标题时的 body 内容相对卡片顶部偏移；2 行时 + TITLE_LINE_EXTRA
const BODY_Y = {
  contribution: 50,
  percent: 72,
  response: 86,
  request: 110,
};
const ROOT_BODY_Y = {
  response: 50,
  request: 74,
};
// 贡献度进度条
const percentConfig = {
  height: 4,
}
const percentBgConfig = {
  width: rectConfig.width - 24,
  height: 4,
}
// viewData 展示内容标题
const viewDataTitle = {
  textBaseline: 'top',
  x: nodeOrigin.x + nodeOrigin.padding,
  textAlign: 'left',
  fontSize: 12,
} as any;
// viewData 展示内容数值
const viewDataValue = {
  textBaseline: 'top',
  x: rectConfig.width / 2 - nodeOrigin.padding,
  textAlign: 'right',
  fontSize: 12,
} as any;
// edge 展示
const edgeInfoText = {
  textAlign: 'center',
  textBaseline: 'top',
  fontSize: 12,
} as any;

const viewDataShapeNames = [
  FLOW_NODE.viewData.responseTitle,
  FLOW_NODE.viewData.responseValue,
  FLOW_NODE.viewData.requestTitle,
  FLOW_NODE.viewData.requestValue,
]
const { x } = nodeOrigin;

export const registerFlowNodeCard = (g6Cls: G6Type) => {
  g6Cls.registerNode(
    FLOW_NODE.name,
    {
      draw (cfg: any, group) {
        // console.log(cfg)
        // 节点数据
        const { viewData = {}, serviceInfo = {}, children, childrenInfo } = cfg || {};
        const { contribution = 0, response = 0, reqCnt = 0 } = viewData || {};
        const { serviceType = 'default' } = serviceInfo || {};
        const headerTitle = resolveHeaderTitle(cfg!.name as string, TITLE_MAX_WIDTH_NODE);
        cfg.titleLines = headerTitle.lines;
        const cardHeight = rectConfig.height + headerTitle.extra;
        const y = -cardHeight / 2;
        // 主体容器
        const mainRect = group!.addShape(
          'rect',
          {
            attrs: {
              x, y,
              ...rectConfig,
              height: cardHeight,
              stroke: colors.border,
              cursor: 'pointer',
              fill: colors.background,
            },
            name: FLOW_NODE.box
          }
        );
        const rectBBox = mainRect.getBBox();

        // 内容分组
        // 所有 header, text, progressbar 都添加到这个分组
        const contentGroup = group!.addGroup({
          name: 'content-group'
        });

        // 卡片头部
        const headerRect = contentGroup!.addShape(
          'rect',
          {
            attrs: {
              x: x + 1, y: rectBBox.minY + HEADER_OFFSET_Y,
              ...rectHeaderConfig,
              height: headerTitle.headerHeight,
              fill: colors.headerBg,
              cursor: 'pointer',
            },
            name: FLOW_NODE.header.box,
          }
        );
        // header标题图标
        const titleIconText = contentGroup!.addShape(
          'text',
          {
            attrs: {
              x: x + 11,
              y: headerIconCenterY(y, headerTitle.headerHeight),
              text: getDbIcon(serviceType || 'default'),
              fontFamily: 'db-icon',
              fontSize: 14,
              fontWeight: 400,
              textBaseline: 'middle',
              fill: colors.headerText,
              cursor: 'pointer',
            },
            name: FLOW_NODE.header.titleIcon
          }
        );
        // header标题文字（能一行则一行，否则最多 2 行）
        const titleText = contentGroup!.addShape(
          'text',
          {
            attrs: {
              x: x + 30,
              y: y + headerTitle.titleYOffset,
              text: headerTitle.text,
              ...rectHeaderTitleConfig,
              fill: colors.headerText,
              cursor: 'pointer',
            },
            name: FLOW_NODE.header.title
          }
        );
        // header右侧控制展示信息按钮
        const titleToggleBtn = contentGroup!.addShape(
          'text',
          {
            attrs: {
              x: x + rectConfig.width - 22,
              y: headerIconCenterY(y, headerTitle.headerHeight),
              fill: colors.headerArrow,
              text: getDbIcon('down'),
              fontFamily: 'db-icon',
              fontSize: 14,
              textBaseline: 'middle',
              cursor: 'pointer',
            },
            name: FLOW_NODE.header.toggleIcon
          },
        );
        // header 右侧筛选图标
        // const filterIconText = contentGroup!.addShape(
        //   'text',
        //   {
        //     attrs: {
        //       x: rectConfig.width / 2 - 40,
        //       y: y + ((rectHeaderConfig.height + 16) / 2),
        //       fill: colors.headerArrow,
        //       text: getDbIcon('filter'),
        //       fontFamily: 'db-icon',
        //       fontSize: 14,
        //       cursor: 'pointer',
        //     },
        //     name: FLOW_NODE.header.filterIcon
        //   }
        // );

        const bodyExtra = headerTitle.extra;
        // 响应贡献度文字
        const conbText = contentGroup!.addShape(
          'text',
          {
            attrs: {
              ...viewDataTitle,
              fill: colors.contText,
              y: y + BODY_Y.contribution + bodyExtra,
              text: i18n.t('modules.views.appMonitor.serviceFlow.s_b71a5427') as string, textKey: 'modules.views.appMonitor.serviceFlow.s_b71a5427',
              cursor: 'pointer',
            },
            name: FLOW_NODE.viewData.conbTitle
          }
        );
        // 响应贡献度数值
        const conbValueText = contentGroup!.addShape(
          'text',
          {
            attrs: {
              ...viewDataValue,
              fill: colors.contValue,
              y: y + BODY_Y.contribution + bodyExtra,
              text: PercentFilter(contribution > 1 ? 1 : contribution, true),
              cursor: 'pointer',
            },
            name: FLOW_NODE.viewData.conbValue
          }
        );
        // 响应贡献度进度条底图
        const percentBgRect = contentGroup!.addShape(
          'rect',
          {
            attrs: {
              x: x + 12,
              y: y + BODY_Y.percent + bodyExtra,
              ...percentBgConfig,
              fill: colors.percentBg,
              cursor: 'pointer',
              radius: 1,
            },
            name: FLOW_NODE.viewData.percentBg,
          }
        );
        // 响应贡献度进度条底图
        const percentBar = contentGroup!.addShape(
          'rect',
          {
            attrs: {
              x: x + 12,
              y: y + BODY_Y.percent + bodyExtra,
              ...percentConfig,
              fill: colors.percent,
              width: percentBgConfig.width * (contribution > 1 ? 1 : (contribution < 0 ? 0 : contribution)),
              cursor: 'pointer',
              radius: 1,
            },
            name: FLOW_NODE.viewData.percentBar,
          }
        );
        // 平均响应时间文字
        const responseText = contentGroup!.addShape(
          'text',
          {
            attrs: {
              ...viewDataTitle,
              fill: colors.contText,
              y: y + BODY_Y.response + bodyExtra,
              text: i18n.t('modules.views.appMonitor.cache.s_96a0c062') as string, textKey: 'modules.views.appMonitor.cache.s_96a0c062',
              cursor: 'pointer',
            },
            name: FLOW_NODE.viewData.responseTitle
          }
        );
        // 平均响应时间数值
        const responseValue = contentGroup!.addShape(
          'text',
          {
            attrs: {
              ...viewDataValue,
              fill: colors.contValue,
              y: y + BODY_Y.response + bodyExtra,
              text: NsFilter(response),
              cursor: 'pointer',
            },
            name: FLOW_NODE.viewData.responseValue
          }
        );
        // 请求数文字
        const requestText = contentGroup!.addShape(
          'text',
          {
            attrs: {
              ...viewDataTitle,
              fill: colors.contText,
              y: y + BODY_Y.request + bodyExtra,
              text: i18n.t('modules.views.appMonitor.cache.s_8bc42b53') as string, textKey: 'modules.views.appMonitor.cache.s_8bc42b53',
              cursor: 'pointer',
            },
            name: FLOW_NODE.viewData.requestTitle
          }
        );
        // 请求数数值
        const requestValue = contentGroup!.addShape(
          'text',
          {
            attrs: {
              ...viewDataValue,
              fill: colors.contValue,
              y: y + BODY_Y.request + bodyExtra,
              text: NumberFilter(reqCnt),
              cursor: 'pointer',
            },
            name: FLOW_NODE.viewData.requestValue
          }
        );

        // 隐藏平均响应和请求数
        responseText.hide();
        responseValue.hide();
        requestText.hide();
        requestValue.hide();

        if (childrenInfo && !childrenInfo.loaded) {
          group!.addShape('marker', {
            attrs: {
              x: Math.abs(x) + 9,
              y: 0,
              r: 8,
              cursor: 'pointer',
              symbol: G6.Marker.expand,
              fill: colors.expandFill,
              stroke: colors.expandStroke,
              lineWidth: 1.3,
            },
            stateStyles: {
              loaded: {
                r: 0,
              }
            },
            name: FLOW_NODE.action.expandIcon,
          });
        }

        return mainRect;
      },
      update (cfg: any, item) {
        const { childrenInfo } = cfg || {};
        if (childrenInfo && childrenInfo.loaded) {
          const group = item.getContainer();
          const child = group.find(t => t.cfg.name === FLOW_NODE.action.expandIcon);
          if (child) {
            group!.removeChild(child);
          }
        }
      },
      setState (name, value, item) {
        if (!item || !item.getContainer()) {
          return
        }
        const group = item.getContainer();
        const cfg: any = item.getModel();

        if (name === 'nodeActive') {
          // --- 1. 找到关键元素 ---
          const keyShape = item.getKeyShape(); // 主矩形
          const contentGroup = group.find((i: any) => i.get('name') === 'content-group'); // 内容分组
            const collapseViewDatas = group.findAll((child) => {
            return viewDataShapeNames.includes(child.cfg.name) // 响应时间、调用数等
          });
          const headerBox = group.find((i: any) => i.get('name') === FLOW_NODE.header.box);
          const titleIcon = group.find((i: any) => i.get('name') === FLOW_NODE.header.titleIcon);
          const title = group.find((i: any) => i.get('name') === FLOW_NODE.header.title);
          const toggleIcon = group.find((i: any) => i.get('name') === FLOW_NODE.header.toggleIcon);

          // --- 2. 处理 'nodeActive' 状态 (展开/收起 + 样式) ---
          const titleExtra = cfg.titleLines != null
            ? (cfg.titleLines > 1 ? TITLE_LINE_EXTRA : 0)
            : resolveHeaderTitle(cfg.name, TITLE_MAX_WIDTH_NODE).extra;
          const originHeight = rectConfig.height + titleExtra;
          const originY = -originHeight / 2;
          const expandHeight = rectConfig.expandHeight + titleExtra;
          const expandY = -expandHeight / 2;
          // 高度差的一半
          const halfDelta = (expandHeight - originHeight) / 2;

          if (value) {
            // --- 设为 Active (展开) ---
            cfg.translateY = -halfDelta;
            keyShape.attr({ y: expandY, height: expandHeight });
            contentGroup.translate(0, cfg.translateY);

            // 显示隐藏的文字
            collapseViewDatas.forEach((child) => {
              child.show();
            });

            // 应用 Active 样式
            keyShape.attr('stroke', colors.borderActive);
            headerBox && headerBox.attr('fill', colors.headerBgActive);
            titleIcon && titleIcon.attr('fill', colors.headerTextActive);
            title && title.attr('fill', colors.headerTextActive);
            if (toggleIcon) {
              toggleIcon.attr('fill', colors.headerArrowActive);
              toggleIcon.attr('text', getDbIcon('up')); // 设置向上的图标
            }

          } else {
            // --- 取消 Active (收起) ---
            keyShape.attr({ y: originY, height: originHeight });
            if (cfg.translateY) {
              contentGroup.translate(0, -cfg.translateY);
              delete cfg.translateY;
            }

            // 隐藏文字
            collapseViewDatas.forEach((child) => {
              child.hide();
            });

            // 恢复为默认样式
            keyShape.attr('stroke', colors.border);
            headerBox && headerBox.attr('fill', colors.headerBg);
            titleIcon && titleIcon.attr('fill', colors.headerText);
            title && title.attr('fill', colors.headerText);
            if (toggleIcon) {
              toggleIcon.attr('fill', colors.headerArrow);
              toggleIcon.attr('text', getDbIcon('down')); // 恢复向下的图标
            }
          }
        }

        // --- 处理 'highlight' 状态 ---
        if (name === 'highlight') {
          group.attr('opacity', value ? 1 : 0.5)
        }
      },
      getAnchorPoints() {
        return [
          [0, 0.5],
          [1, 0.5],
        ];
      },
    },
    'rect'
  )
}

export const registerFlowRootNodeCard = (g6Cls: G6Type) => {
  g6Cls.registerNode(
    FLOW_NODE.rootName,
    {
      shapeType: FLOW_NODE.name,
      drawShape (cfg: any, group) {
        // console.log(cfg)
        // 节点数据
        const { viewData = {}, serviceInfo = {}, children, childrenInfo } = cfg || {};
        const { response = 0, reqCnt = 0 } = viewData || {};
        const { serviceType = 'default' } = serviceInfo || {};
        const headerTitle = resolveHeaderTitle(cfg!.name as string, TITLE_MAX_WIDTH_ROOT);
        cfg.titleLines = headerTitle.lines;
        const rootHeight = ROOT_BASE_HEIGHT + headerTitle.extra;
        const _y = -rootHeight / 2;
        // 主体容器
        const mainRect = group!.addShape(
          'rect',
          {
            attrs: {
              ...rectConfig,
              height: rootHeight,
              x,
              y: _y,
              stroke: colors.border,
              fill: colors.background,
            },
            name: FLOW_NODE.box
          }
        );
        const rectBBox = mainRect.getBBox();

        // 内容分组
        // 所有 header, text, progressbar 都添加到这个分组
        const contentGroup = group!.addGroup({
          name: 'content-group'
        });

        // 卡片头部
        const headerRect = contentGroup!.addShape(
          'rect',
          {
            attrs: {
              x: x + 1, y: rectBBox.minY + HEADER_OFFSET_Y,
              ...rectHeaderConfig,
              height: headerTitle.headerHeight,
              fill: colors.headerBg,
            },
            name: FLOW_NODE.header.box,
          }
        );
        // header标题图标
        const titleIconText = contentGroup!.addShape(
          'text',
          {
            attrs: {
              x: x + 11,
              y: headerIconCenterY(_y, headerTitle.headerHeight),
              text: getDbIcon(serviceType || 'default'),
              fontFamily: 'db-icon',
              fontSize: 14,
              fontWeight: 400,
              textBaseline: 'middle',
              fill: colors.headerText,
            },
            name: FLOW_NODE.header.titleIcon
          }
        );
        // header标题文字（能一行则一行，否则最多 2 行）
        const titleText = contentGroup!.addShape(
          'text',
          {
            attrs: {
              x: x + 30,
              y: _y + headerTitle.titleYOffset,
              text: headerTitle.text,
              ...rectHeaderTitleConfig,
              fill: colors.headerText,
            },
            name: FLOW_NODE.header.title
          }
        );

        const bodyExtra = headerTitle.extra;
        // 平均响应时间文字
        const responseText = contentGroup!.addShape(
          'text',
          {
            attrs: {
              ...viewDataTitle,
              fill: colors.contText,
              y: _y + ROOT_BODY_Y.response + bodyExtra,
              text: i18n.t('modules.views.appMonitor.cache.s_96a0c062') as string, textKey: 'modules.views.appMonitor.cache.s_96a0c062',
            },
            name: FLOW_NODE.viewData.responseTitle
          }
        );
        // 平均响应时间数值
        const responseValue = contentGroup!.addShape(
          'text',
          {
            attrs: {
              ...viewDataValue,
              fill: colors.contValue,
              y: _y + ROOT_BODY_Y.response + bodyExtra,
              text: NsFilter(response),
            },
            name: FLOW_NODE.viewData.responseValue
          }
        );
        // 请求数文字
        const requestText = contentGroup!.addShape(
          'text',
          {
            attrs: {
              ...viewDataTitle,
              fill: colors.contText,
              y: _y + ROOT_BODY_Y.request + bodyExtra,
              text: i18n.t('modules.views.appMonitor.cache.s_8bc42b53') as string, textKey: 'modules.views.appMonitor.cache.s_8bc42b53',
            },
            name: FLOW_NODE.viewData.requestTitle
          }
        );
        // 请求数数值
        const requestValue = contentGroup!.addShape(
          'text',
          {
            attrs: {
              ...viewDataValue,
              fill: colors.contValue,
              y: _y + ROOT_BODY_Y.request + bodyExtra,
              text: NumberFilter(reqCnt),
            },
            name: FLOW_NODE.viewData.requestValue
          }
        );

        if (childrenInfo && !childrenInfo.loaded) {
          group!.addShape('marker', {
            attrs: {
              x: Math.abs(x) + 11,
              y: 0,
              r: 10,
              cursor: 'pointer',
              symbol: G6.Marker.expand,
              fill: colors.expandFill,
              stroke: colors.expandStroke,
              lineWidth: 1.5,
            },
            stateStyles: {
              loaded: {
                r: 0,
              }
            },
            name: FLOW_NODE.action.expandIcon,
          });
        }

        return mainRect;
      },
      update (cfg: any, item) {
        const { childrenInfo } = cfg || {};
        if (childrenInfo && childrenInfo.loaded) {
          const group = item.getContainer();
          const child = group.find(t => t.cfg.name === FLOW_NODE.action.expandIcon);
          if (child) {
            group!.removeChild(child);
          }
        }
      },
      getAnchorPoints() {
        return [
          [0, 0.5],
          [1, 0.5],
        ];
      },
    },
  )
}

export const registerFlowEdge = (g6Cls: G6Type) => {
  g6Cls.registerEdge(
    FLOW_EDGE.name,
    {
      afterDraw(cfg, group) {
        if (!cfg || !group) {
          return
        }
        // 获取图形组中的第一个图形，在这里就是边的路径图形
        const shape = group.get('children')[0];
        // 获取路径图形的中点坐标
        const midPoint = shape.getPoint(0.5);
        const requestText = group.addShape('text', {
          attrs: {
            ...edgeInfoText,
            x: midPoint.x,
            y: midPoint.y - 18,
            text: i18n.t('modules.views.appMonitor.serviceFlow.s_75b16081') as string, textKey: 'modules.views.appMonitor.serviceFlow.s_75b16081',
            fillOpacity: 0,
            fill: colors.edgeText,
          },
          name: FLOW_EDGE.viewData.request
        });
        const avgText = group.addShape('text', {
          attrs: {
            ...edgeInfoText,
            x: midPoint.x,
            y: midPoint.y + 6,
            text: i18n.t('modules.views.appMonitor.serviceFlow.s_f50f4346') as string, textKey: 'modules.views.appMonitor.serviceFlow.s_f50f4346',
            fillOpacity: 0,
            fill: colors.edgeText,
          },
          name: FLOW_EDGE.viewData.avgCall
        });
      },
      setState (name, value, item) {
        if (!item || !item.getContainer()) {
          return
        }
        const group = item.getContainer();
        const keyShape = group!.get('children')[0];
        const requestText = group.find((i: any) => i.get('name') === FLOW_EDGE.viewData.request);
        const avgText = group.find((i: any) => i.get('name') === FLOW_EDGE.viewData.avgCall);

        if (name === 'edgeActive') {
          if (value) {
            keyShape.attr({
              stroke: colors.edgeActive,
              lineWidth: 2,
              endArrow: {
                ...keyShape.attrs.endArrow,
                fill: colors.edgeActive,
              },
            });
            const { callPct, avgReq }: any = (item as IEdge).getTarget().getModel()?.viewData || {};
            requestText.attr('text', i18n.t('modules.views.appMonitor.serviceFlow.s_390576d4', { value0: PercentFilter(callPct || 0, true) }) as string)
            avgText.attr('text', i18n.t('modules.views.appMonitor.serviceFlow.s_7e7d30f8', { value0: NumberFilter(avgReq || 0, true) }) as string)
          } else {
            keyShape.attr({
              stroke: colors.edge,
              lineWidth: 1,
              endArrow: {
                ...keyShape.attrs.endArrow,
                fill: colors.edge,
              },
            });
          }
        }

        if (name === 'highlight') {
          keyShape.attr('opacity', value ? 1 : 0.5);
          requestText.attr('fillOpacity', value ? 1 : 0)
          avgText.attr('fillOpacity', value ? 1 : 0)
        }
      },
      update: undefined,
    },
    'cubic-horizontal'
  )
}

export default {
  registerFlowNodeCard,
};
