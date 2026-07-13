import Vue from 'vue';
import Router, { RouteConfig, RouterOptions, Route } from 'vue-router';
import store from '../store/index';
import { getToken, removeTokenAndCid, setTokenAndCid } from '@/utils/jsCookie';
import App from '@/main';
import { EventBus } from '@/utils/common';
import AuthBuilder from '@/utils/auth';
import dayjs from 'dayjs'
import { bisectLeft } from 'd3'

import { TimeRangeMsOptions } from './time-new';
import { FullPropMenu } from './route.types';
import routeData from '@/router/route-data';

// 预加载所有 views 下的 index.vue 模块以便用于动态路由并让 Vite 能跟踪它们用于 HMR
const viewModules = import.meta.glob('/src/views/**/index.vue');

const DEFAULT_LIMIT_DAY = 31;

Vue.use(Router);
window.axiosCancel = [];

let isHistoryChange = false; // 是否为浏览器原生的前进/后退操作
window.addEventListener('popstate', (e) => {
  isHistoryChange = true;
}, false);

const authBuilder = new AuthBuilder();
let firstStatusInit = false;
let _status: any = null;

const options: RouterOptions = {
  mode: 'history',
  // base: import.meta.env.BASE_URL,
  base: '/databuff',
  linkActiveClass: 'active',
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/login/index.vue'),
    },
    {
      path: '/authorization',
      name: 'Authorization',
      component: () => import('@/views/authorization/index.vue'),
    },
    {
      path: '/singleLogin/:type',
      name: 'SingleLogin',
      component: () => import('@/views/singleLogin/index.vue'),
    },
    {
      path: '/404',
      name: '404',
      component: () => import('@/views/404/index.vue'),
    },
  ],
};
const whiteList = ['/login', '/authorization', '/404'];
const singleLoginList = ['/singleLogin/imc']
const router = new Router(options);

let firstInit = false;

// 获取菜单中支持前后切换的分钟间隔
const getTimeRageStep = (menu: any) => {
  let step = 0
  const time = `${(menu || {}).time || ''}`;
  if (time.indexOf('step-') === 0) {
    step = Number(time.replace('step-', ''))
  }
  return isNaN(step) ? 0 : step
}

router.beforeResolve((to: any, from, next) => {
  const toMenu = routeData.find(m => m.path === to.path) as FullPropMenu; // to菜单数据
  if (!toMenu || !toMenu.time) { // 无时间选择权限
    next();
    return;
  }

  const tqf = to.query.fromTime;
  const tqt = to.query.toTime;
  const tqd = to.query.durationRange;
  const windowDuration = DEFAULT_LIMIT_DAY * 86400000; // 可选时间窗口（最近 N 天）
  const maxDuration = (toMenu.limitDays || DEFAULT_LIMIT_DAY) * 86400000; // 单次时间跨度
  const nowTime = new Date(); // 当前时间，并设置到当前分钟的最后1毫秒，防止tqt超出时间
  nowTime.setSeconds(59);
  nowTime.setMilliseconds(999);
  const disabledFrom = +nowTime - windowDuration - (59 * 1000 + 999); // 时间范围从何时禁止
  const timeStep = getTimeRageStep(toMenu); // 支持前后切换的分钟间隔
  const timeStepDuration = timeStep * 60 * 1000
  const customTime = toMenu.time === true || !!timeStep; // 是否有自定义时间权限
  const timeOptions = TimeRangeMsOptions.filter((item) => { // 可选最近时间
    return item.value <= maxDuration && (!timeStep || item.value === timeStepDuration)
  });

  // 判断是否更新store时间
  const commitGlobalTime = (data: any, replace?: boolean) => {
    const { type, duration } = data
    const fromTime = new Date(dayjs(data.fromTime).format('YYYY-MM-DD HH:mm'))
    const toTime = new Date(dayjs(data.toTime).format('YYYY-MM-DD HH:mm'))
    const gt = store.getters.globalTime()
    if (type === 'select' && (type !== gt.type || +duration !== +gt.duration || +duration !== +tqd)) {
      store.commit('SET_DURATION_DATES', {
        fromTime: new Date(dayjs(+nowTime - duration).format('YYYY-MM-DD HH:mm')),
        toTime: new Date(dayjs(nowTime).format('YYYY-MM-DD HH:mm')),
        type,
      });
      if (+duration !== +tqd) {
        next({
          ...to,
          query: { ...to.query, durationRange: +duration },
          replace: true,
        });
      } else {
        next();
      }
    } else if (type === 'custom' && (type !== gt.type || +fromTime !== +gt.fromTime || +toTime !== +gt.toTime)) {
      store.commit('SET_DURATION_DATES', {
        fromTime,
        toTime,
        type,
      });
      if (!replace) {
        next();
      } else {
        next({
          ...to,
          query: { ...to.query, fromTime: +fromTime, toTime: +toTime },
          replace: true,
        });
      }
    } else {
      next();
    }
  }

  // 匹配自定义时间
  if (customTime && tqf && tqt && dayjs(+tqf).isValid() && dayjs(+tqt).isValid() &&
      +tqf <= +tqt && +tqf >= disabledFrom && +tqt <= +nowTime &&
      (+tqt - +tqf) <= maxDuration &&
      (!timeStep || (+tqt - +tqf) === timeStepDuration)) {
    commitGlobalTime({
      fromTime: new Date(+tqf),
      toTime: new Date(+tqt),
      type: 'custom'
    });
    return;
  }

  // from=now-15m/now-1h/now-3d&to=now
  const { from: tqFrom, to: tqTo } = to.query;
  if (tqFrom && tqTo && tqFrom.indexOf('now-') > -1 && tqTo.indexOf('now') > -1) {
    const range = tqFrom.replace('now-', '');
    const matchOption = timeOptions.find(i => i.abbr === range);
    delete to.query.from;
    delete to.query.to;
    if (matchOption) {
      commitGlobalTime({
        duration: matchOption.value,
        type: 'select'
      });
      return;
    }
  }

  // 只含有fromTime=timestamp，为看板下钻过来，处理为最近x小时
  if (tqf && !tqt && !tqd && dayjs(+tqf).isValid()) {
    const calcDuration = +nowTime - Math.abs(+tqf);
    const msRanges = timeOptions.map(i => i.value);
    const matchedIdx = bisectLeft(msRanges, calcDuration);
    delete to.query.fromTime;
    if (matchedIdx < msRanges.length) {
      const matchOption = timeOptions[matchedIdx];
      commitGlobalTime({
        duration: matchOption.value,
        type: 'select'
      });
      return;
    }
  }

  // 匹配durationRange，或者设置默认
  const defaultOption = timeOptions.find((item) => item.default) || timeOptions[0];
  const option = timeOptions.find((item) => item.value === +tqd);
  if (option || defaultOption) {
    commitGlobalTime({
      duration: (option || defaultOption).value,
      type: 'select'
    });
    return;
  }

  // timeStep 不在可选时间范围内，处理为最新的自定义时间
  if (timeStep && !timeOptions.length) {
    delete to.query.durationRange;
    commitGlobalTime({
      fromTime: new Date(+nowTime - timeStepDuration),
      toTime: nowTime,
      type: 'custom'
    }, true);
    return;
  }
});

router.beforeEach(async (to: any, from, next) => {
  // 优先处理单点登录
  if (singleLoginList.indexOf(to.path) !== -1) {
    next();
    return;
  }
  // 处理Root Lab实验室的token 和 cid
  if (getToken() && to.query.dbt && to.query.dbc) {
    removeTokenAndCid();
  }

  // 新窗口打开判断
  const isNewWindow = getNewWindow(to, from)
  if (isHistoryChange) {
    // 浏览器原生的前进/后退操作，不做处理
    isHistoryChange = false;
  } else if (isNewWindow) {
    const _to = router.resolve({
      path: to.path,
      query: { ...to.query, ...formatRouteQuery(to), __nw: 't' }
    });
    window.open(_to.href, '_blank');
    return;
  }
  if (window.axiosCancel.length !== 0) {
    for (const func of window.axiosCancel) {
      setTimeout(func('interrupt'), 0);
    }
    window.axiosCancel = [];
  }

  if (!['/', ...whiteList].includes(to.path) && (store.getters['User/getMenus'] || []).length) {
    const _query = formatRouteQuery(to, from);
    if (_query) {
      // 写在getStatus上面，防止重复请求 getStatus
      next({ ...to, query: _query })
      return;
    }
  }

  // auth拦截器处理后的状态（账号过期等，不含平台激活检查）
  if (to.path !== from.path || !firstStatusInit) {
    _status = await authBuilder.getStatus()
    firstStatusInit = true
  }

  // 处理Root Lab实验室的token 和 cid
  if (!getToken() && to.query.dbt && to.query.dbc) {
    setTokenAndCid(decodeURIComponent(to.query.dbt), decodeURIComponent(to.query.dbc));
    delete to.query.dbt;
    delete to.query.dbc;
  }

  try {
    // 判断登录是否过期，未过期登录页自动跳转回原页面
    if (getToken()) {
      if (!store.getters['User/getUserInfo'] || !store.getters['User/getUserInfo'].loaded) {
        await store.dispatch('User/getUserInfo');
        await store.dispatch('User/getGroupEnabled');
        // Do not block route navigation on Doris-backed service catalog (may hang ~60s when BE is down).
        store.dispatch('Service/GET_BASIC_SERVICE');
      }
      if (!store.getters['User/getMenus'] || store.getters['User/getMenus'].length === 0) {
        // 判断是否有过期限制 - 过期仍可使用非分析功能模块
        try {
          const menus = await store.dispatch('User/getMenus');
          // 如果菜单为空，提示联系管理员分配权限
          if (menus.length === 0) {
            throw new Error('当前帐号未分配权限，请联系管理员添加');
          } else {
            formatMenusSource()
          }
        } catch (err: any) {
          App.$message.error(`登录失败: ${err.message}`);
          removeTokenAndCid();
          next({
            path: '/login',
            replace: true,
            query: to.fullPath !== '/' ? { next: encodeURIComponent(to.fullPath) } : {},
          });
          EventBus.$emit('loginFailed', false, true);
          return;
        }
      }
      const isExpireLimit = store.getters['User/isExpireLimit']
      // 2-平台已过期，3-平台重新授权
      if ((_status && _status > 2) || (_status === 2 && !isExpireLimit)) {
        next('/authorization');
        return;
      }
      if (to.path === '/login') {
        next({
          path: '/',
          query: { __ps: 'm' },
          replace: true,
        });
      } else if (!firstInit) {
        const _query = { ...to.query };
        delete _query.dbt;
        delete _query.dbc;
        if (from.path === '/' && ['/', ...whiteList].includes(to.path)) {
          _query.__ps = 'm';
        }
        next({ ...to, replace: true, query: _query });
        firstInit = true;
      } else {
        const _query = formatRouteQuery(to, from);
        _query ? next({ ...to, query: _query }) : next();
      }
    } else {
      if (whiteList.indexOf(to.path) !== -1) {
        next();
      } else {
        const redirect = to.fullPath !== '/' && to.query.__redirect !== 'false'
        next({
          path: '/login',
          query: redirect ? { next: encodeURIComponent(to.fullPath) } : {},
          replace: true,
        });
      }
    }
  } catch (e) {
    console.log(e);
  }
});

function generRouter(menu: FullPropMenu): RouteConfig {
  const _route: RouteConfig = {
    path: menu.path,
    name: menu.name,
    component: () => {
      // 特殊处理，router-view-temp是一个通用的包裹层
      if (menu.filePath === 'router-view-temp') {
        return import('@/components/router-view-temp/index.vue');
      }
  // Vite 不支持任意深度的变量动态 import（变量只能代表一层文件名），
  // 所以使用 import.meta.glob 预注册所有可能的视图模块，并按路径查找加载器。
  // 视图文件在项目中的实际路径为 /src/views/.../index.vue
  const tryPath = `/src/views${menu.filePath}/index.vue`;
  const loader = viewModules[tryPath as keyof typeof viewModules] as (() => Promise<any>) | undefined;
      if (loader) {
        return loader();
      }
      // 如果没有匹配到模块，回退到 404 或抛出错误以便排查
      console.warn(`dynamic import not found for path: ${tryPath}`);
      return import('@/views/404/index.vue');
    },
    redirect: '',
  };
  if (menu.children && menu.children.length > 0) {
    _route.children = menu.children.map((item) => generRouter(item));
    // 仅对可见子菜单做默认重定向；静态子路由（如 /config/rule）不应抢占父页面
    const menuChildren = menu.children.filter((item) => item.isMenu);
    if (menuChildren.length > 0) {
      _route.redirect = menuChildren[0].path;
    }
  }
  return _route;
}

function formatMenusSource () {
  const menusTree: FullPropMenu[] = store.getters['User/getMenusTree'];
  const dynamicRoutes = menusTree.map((item) => generRouter(item));
  const _routes: RouteConfig[] = [
    {
      path: '/',
      component: () => import('@/views/layout/index.vue'),
      children: dynamicRoutes,
      redirect: '',
    },
  ];
  // 默认跳转到 AI 平台对话页
  const menus: FullPropMenu[] = store.getters['User/getMenus'] || [];
  if (menus.some((item) => item.path === '/aiPlatform/chat')) {
    _routes[0].redirect = '/aiPlatform/chat';
  } else if (dynamicRoutes.find((t) => t.path === '/cockpit')) {
    _routes[0].redirect = '/cockpit';
  } else {
    // 跳转第一个子菜单路径
    _routes[0].redirect = dynamicRoutes[0].path;
  }
  _routes.push({
    path: '*',
    component: () => import('@/views/404/index.vue'),
    children: [],
    redirect: '/404'
  });
  router.addRoutes(_routes);
}

// 是否新窗口打开，有无返回按钮
function getNewWindow (to: Route, from: Route) {
  const isMenuSource = to.query.__ps === 'm' // 从左侧菜单点击来的
  const wList = [ '/', ...whiteList, ...singleLoginList ] // 白名单
  if (isMenuSource || wList.includes(to.path) || wList.includes(from.path)) {
    return false
  }
  const menus: FullPropMenu[] = store.getters['User/getMenus'] || []
  const toMenu = menus.find(t => t.path === to.path)
  const fromMenu = menus.find(t => t.path === from.path)
  if (!menus.length || !toMenu || !fromMenu || toMenu.module === fromMenu.module) {
    return false
  }
  return true
}

// 获取时间参数，有时间权限但query没有时间参数时返回全局时间
function getTimeParams (to: Route) {
  const toMenu = routeData.find(m => m.path === to.path) as FullPropMenu; // to菜单数据
  if (!toMenu || !toMenu.time) { // 无时间选择权限
    return null;
  }
  const { fromTime: tqf, toTime: tqt, durationRange: tqd, from: tqFrom, to: tqTo } = to.query
  if (tqf || tqt || tqd || tqFrom || tqTo) {
    return null;
  }
  const globalTime = store.getters.globalTime()
  if (globalTime.type === 'custom') {
    return { fromTime: +globalTime.fromTime, toTime: +globalTime.toTime }
  } else {
    return { durationRange: +globalTime.duration }
  }
}

function formatRouteQuery (to: Route, from?: Route) {
  if (['/', ...whiteList].includes(to.path)) {
    return null;
  }
  const menus: FullPropMenu[] = store.getters['User/getMenus'] || []
  const isMenu = !!(menus.find(t => t.path === to.path) || {}).isMenu;
  let _query: any = { ...to.query, ...getTimeParams(to) };
  delete _query.dbt;
  delete _query.dbc;
  // 非菜单，删除__ps
  if (!isMenu && _query.__ps === 'm') {
    delete _query.__ps;
  }
  if (from && whiteList.includes(from.path) && isMenu && to.query.__ps !== 'm') {
    _query = { ..._query, __ps: 'm' };
  }
  if (JSON.stringify(_query) === JSON.stringify(to.query)) {
    return null;
  } else {
    return _query;
  }
}

export default router;
