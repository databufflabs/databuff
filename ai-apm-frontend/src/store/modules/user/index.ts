import { ActionTree, MutationTree, GetterTree, Commit } from 'vuex';
import UserApi from '@/api/user';
import GroupApi from '@/api/group';
import ConfigApi from '@/api/config';
import routeData from '@/router/route-data';
import { FullPropMenu } from '@/router/route.types';
import { StringIsEmpty, toAsyncWait } from '@/utils/common';
import { getAgi, setAgi, removeAgi } from '@/utils/jsCookie';
import isEqual from 'lodash/isEqual'; 
// 将menu列表转换成tree型结果
const generMenuTree = <T extends FullPropMenu | PermitMenu>(menus: T[], parentIds: number[] = [0]): T[] => {
  const tree: T[] = [];
  const lastPid = parentIds[parentIds.length - 1];
  menus.forEach((item) => {
    if (item.parentId === lastPid) {
      if ((item as FullPropMenu).menuId) { // FullPropMenu
        (item as FullPropMenu).level = parentIds.length;
        (item as FullPropMenu).menuIds = [...parentIds, (item as FullPropMenu).menuId].filter(t => !!t);
      }
      tree.push(item);
      const hasStaticChildren = menus.some(
        (child) => child.parentId === item.id && (child as FullPropMenu).isStatic,
      );
      if (!item.leaf || hasStaticChildren) {
        item.children = generMenuTree(menus, [...parentIds, item.id]);
        if (!item.children.length) {
          item.leaf = true;
          delete item.children;
        }
      }
    }
  });
  tree.sort((a, b) => a.order - b.order);
  return tree;
}

// 获取tree数据里指定id的菜单
const getTreeItemById = (menus: FullPropMenu[], id: number): FullPropMenu | null => {
  for (const item of menus) {
    if (item.id === id) {
      return item;
    }
    if (item.children) {
      const _item = getTreeItemById(item.children, id);
      if (_item) {
        return _item;
      }
    }
  }
  return null;
}

// 获取静态菜单的有效祖先菜单，用于过滤无效的静态菜单
const getParentMenu = (item: FullPropMenu, _menus: any[]): FullPropMenu | null => {
  if (item.menuId != null) {
    const anchor = routeData.find(t => t.id === item.menuId) as FullPropMenu | undefined
    if (anchor && _menus.find(t => t._path === anchor.path)) {
      return anchor
    }
  }
  const parent = routeData.find(t => t.id === item.parentId) as FullPropMenu | undefined
  if (parent && _menus.find(t => t._path === parent.path)) {
    return parent
  }
  if (item.menuId === item.id) {
    return item
  }
  const pMenu = routeData.find(t => t.id === item.menuId) || null
  const hasMenu = pMenu && (pMenu.isStatic || !!_menus.find(t => pMenu.path === t._path))
  return hasMenu ? getParentMenu(pMenu as FullPropMenu, _menus) : null
}

interface PermitMenu {
  parentId: number;
  id: number;
  name: string;
  leaf: boolean;
  order: number;
  children?: PermitMenu[];
}

export interface State {
  menus: FullPropMenu[];
  menusTree: FullPropMenu[];
  permitTree: PermitMenu[];
  currMenu: FullPropMenu | null;
  userInfo: any;
  roleList: any[];
  dsInfo: any;
  expireLimit: boolean;
  currGroup: any[];
  prevGroup: any[];
  groupList: any[];
  groupEnabled: boolean;
  aiEnabled: boolean | null;
  logoConfig: any;
}

const state: State = {
  menus: [], // 所有的菜单列表
  menusTree: [], // 左侧菜单导航列表
  permitTree: [], // 权限管理列表
  currMenu: null,
  userInfo: {},
  roleList: [],
  dsInfo: {},
  expireLimit: false,
  currGroup: [], // 用户当前选择的管理域
  prevGroup: [], // 隐藏管理域前的上一次选择
  groupList: [],
  groupEnabled: false,
  aiEnabled: null,
  logoConfig: {},
};

const actions: ActionTree<State, any> = {
  async getMenus({ commit }) {
    return new Promise(async (resolve, reject) => {
      const isAdminRole = state.userInfo?.currentRole?.roleName === 'Administrator';
      UserApi.getMenus()
        .then((res) => {
          if (res.status && res.data && res.data.menu) {
            // 数据字段格式处理
            res.data.menu.forEach((t: any) => {
              t.parentId = t.parentId || t.parent_id
              t._path = t.path[0] !== '/' ? `/${t.path}` : t.path
              // module_function字段处理，可能为空
              if (t.module_function) {
                try {
                  t.module_function = JSON.parse(t.module_function)
                } catch {
                  //
                }
              }
            })

            const unsupportedPaths = ['/sysManage/group', '/sysManage/health', '/sysManage/group/entity']
            res.data.menu = res.data.menu.filter((t: any) => !unsupportedPaths.includes(t._path))

            const hasExpireLimit = res.data.menu.filter((t: any) => t.module_function)
            commit('SET_EXPIRE_LIMIT', hasExpireLimit.length > 0)

            // 权限数据
            const permitMenus: PermitMenu[] = res.data.menu.map((t: any) => {
              const item: any = routeData.find(m => m.path === t._path) || {}
              return {
                parentId: t.parentId,
                id: t.id,
                name: item.name || t.name,
                leaf: false,
                order: item.order || t.order,
              }
            })

            // 过滤掉 hidden, 'account'，数据库 hidden == false 为隐藏
            const _menus: any[] = res.data.menu.filter((t: any) => !!t.hidden && t._path !== '/account');

            let menus: any[] = []
            if (_menus.length) {
              routeData.filter(item => !item.hidden).forEach(item => {
                const _item = _menus.find(t => t._path === item.path)
                if (_item || (item.isStatic && !!getParentMenu(item, _menus))) {
                  const parentId = _item?.parentId ?? item.parentId
                  const isConfigChild = parentId === 1003
                  menus.push({
                    ...item,
                    parentId,
                    order: _item?.order ?? item.order,
                    icon: isConfigChild ? '' : (item.icon || _item?.icon || ''),
                  })
                }
              })
            }

            const menusTree: FullPropMenu[] = generMenuTree(JSON.parse(JSON.stringify(menus)));
            commit('SET_MENUS_TREE', menusTree);
            commit('SET_PERMIT_TREE', generMenuTree(JSON.parse(JSON.stringify(permitMenus))));
            // 将menu里的数据替换为tree的数据
            menus = menus.map(item => {
              const _item = { ...(getTreeItemById(menusTree, item.id) || item) }
              if (_item.children) {
                delete _item.children;
              }
              return _item;
            });
            commit('SET_MENUS', menus);
            resolve(menus);
          } else {
            reject(res);
          }
        })
        .catch((err) => {
          console.log('get menus error: ', err);
          reject(err);
        });
    });
  },
  async getUserInfo({ commit, dispatch }) {
    return new Promise((resolve, reject) => {
      UserApi.getUserInfo()
        .then((res) => {
          if (res.status === 200 && res.data) {
            // 邮箱地址字段兼容
            res.data.email = res.data.email || res.data.emailAddr || ''

            res.data.loaded = true;
            commit('SET_USER_INFO', res.data);
            resolve(res.data);
          } else {
            reject(res);
          }
        })
        .catch((err) => {
          console.log('get userInfo error: ', err);
          if (err.message !== 'interrupt') {
            reject(err);
          }
        });
    });
  },
  async findRoleGroupByUser(context: { commit: Commit, state: State }) {
    return new Promise(async (resolve, reject) => {
      const { error, result } = await toAsyncWait(GroupApi.getGroupListByUser());
      const { error: glError, result: glResult } = await toAsyncWait(GroupApi.getGroupList({}));
      const groupIdNameMap: { [gid: string]: string } = {};
      if (!glError) {
        const { data = [] } = glResult || {};
        if (Array.isArray(data)) {
          data.forEach((g) => {
            groupIdNameMap[g.id] = g.name
          });
          context.commit('SET_GROUP_LIST', data)
        }
      }

      if (!error) {
        const { data = [] } = result || {};
        const [currentRole] = data || [];
        let groupList: any[] = [];
        // 获取本地存储上次选择的管理域，lastChooseGroupIds可能是字符串数组‘1,3,5’
        const lastChooseGroupIds = getAgi();
        if (!currentRole || !currentRole?.roleGroupRelations) {
          // 角色为空或者未分配管理域，无需额外操作
        } else {
          if (!StringIsEmpty(lastChooseGroupIds) && lastChooseGroupIds.indexOf(',') !== -1) {
            // 如果是ids表示选择了当前角色下的管理域集合，此时管理域可能会有增删，需要手动获取集合
            groupList = currentRole?.roleGroupRelations || [];
          } else if (!StringIsEmpty(lastChooseGroupIds) && lastChooseGroupIds.indexOf(',') === -1) {
            // 表示只选择了一个管理域
            const targetGroup = (currentRole?.roleGroupRelations || []).find((g: any) => g.gid === Number(lastChooseGroupIds));
            if (targetGroup) {
              groupList = [targetGroup];
            } else {
              const _groupList = Array.isArray(currentRole?.roleGroupRelations) ? currentRole.roleGroupRelations : [];
              const [_first] = _groupList;
              if (_first) {
                groupList = [_first];
              }
            }
          } else if (StringIsEmpty(lastChooseGroupIds) && Array.isArray(currentRole?.roleGroupRelations) && currentRole.roleGroupRelations.length) {
            const targetGroup = currentRole.roleGroupRelations[0];
            groupList = [targetGroup];
          }
        }
        // 如果groupList不一致再赋值
        const oldIds = context.state.currGroup.map((g) => g.gid);
        const newIds = groupList.map((g) => g.gid);
        if (!isEqual(newIds, oldIds)) {
          context.commit('SET_CURRENT_GROUP', groupList);
        }
        if (!groupList.length && !StringIsEmpty(lastChooseGroupIds)) {
          context.commit('SET_CURRENT_GROUP', []);
        }
        const formatTree = (item: any, path: number[]) => {
          item._path = [...path, item.roleId];
          if (Array.isArray(item?.children)) {
            item.children.forEach((child: any) => {
              formatTree(child, [...item._path]);
            })
          }
          if (Array.isArray(item?.roleGroupRelations)) {
            item.roleGroupRelations = item.roleGroupRelations.filter((rela: any) => !!groupIdNameMap[rela.gid]);
            item.roleGroupRelations.forEach((rela: any) => {
              rela.gname = groupIdNameMap[rela.gid]
            });
          }
        }
        (data || []).forEach((roleTree: any) => {
          formatTree(roleTree, []);
        });

        if (currentRole) {
          const _currRole = {...currentRole};
          delete _currRole.children;
          context.commit('UPDATE_USER_INFO', {
            roleid: `${currentRole?.roleId}`,
            currentRole: {..._currRole},
          });
        } else {
          context.commit('UPDATE_USER_INFO', {
            roleid: ``,
            currentRole: null,
          });
        }
        
        context.commit('UPDATE_ROLE_LIST', [...data]);
        resolve(data);
      } else {
        resolve([])
      }
    });
  },
  async getGroupEnabled (context: { commit: Commit, state: State }) {
    context.commit('SET_GROUP_ENABLED', 0);
    return 0;
  },
  async getAIEnabled (context: { commit: Commit, state: State }) {
    if (typeof context.state.aiEnabled === 'boolean') {
      return
    }
    const { result, error } = await toAsyncWait(ConfigApi.getAIConfig())
    if (!error) {
      context.commit('SET_AI_ENABLED', !!result?.data?.open);
    }
  },
  async getLogoConfig (context: { commit: Commit, state: State }) {
    const logoConfigDefault = {
      isCustom: false,
      productNameCn: 'AI Native一体化观测平台',
      productNameEn: 'DATABUFF',
      copyright: '',
      loginLogo: '/img/logo_login.png',
      headerLogo: '/img/logo_wh.svg',
      headerCollapseLogo: '/img/logo_wh.svg',
      faviconLogo: '/img/favicon.ico',
    }
    const { result, error } = await toAsyncWait(ConfigApi.getLogoConfig())
    if (!error) {
      const data = result?.data || {};
      const hasCustomBranding = [
        'productNameCn',
        'productNameEn',
        'copyright',
        'loginLogo',
        'headerLogo',
        'headerCollapseLogo',
        'faviconLogo',
      ].some((key) => key in data && data[key]);
      const logoConfig = {
        ...logoConfigDefault,
        ...data,
        isCustom: hasCustomBranding,
      };
      context.commit('SET_LOGO_CONFIG', logoConfig);
      return logoConfig;
    }
    context.commit('SET_LOGO_CONFIG', logoConfigDefault);
    return logoConfigDefault;
  },
};

const mutations: MutationTree<State> = {
  ['SET_USER_INFO'](userState: State, payload: any) {
    userState.userInfo = payload;
  },
  ['UPDATE_USER_INFO'](userState: State, payload: any) {
    userState.userInfo = {
      ...userState.userInfo,
      ...payload,
    };
  },
  ['UPDATE_ROLE_LIST'](userState: State, roleList: any[]) {
    userState.roleList = roleList
  },
  ['SET_MENUS'](userState: State, menus: FullPropMenu[]) {
    userState.menus = menus;
  },
  ['SET_MENUS_TREE'](userState: State, menusTree: FullPropMenu[]) {
    userState.menusTree = menusTree;
  },
  ['SET_PERMIT_TREE'](userState: State, permitTree: PermitMenu[]) {
    userState.permitTree = permitTree;
  },
  ['SET_CURR_MENU'](userState: State, currMenu: FullPropMenu | null) {
    userState.currMenu = currMenu;
  },
  ['SET_DS_INFO'](userState: State, payload: any) {
    if (payload) {
      userState.dsInfo = payload;
    }
  },
  ['SET_EXPIRE_LIMIT'](userState: State, payload: any) {
    userState.expireLimit = payload
  },
  ['SET_CURRENT_GROUP'](userState: State, payload: any[]) {
    userState.currGroup = payload
    if (Array.isArray(payload) && payload.length && userState.groupEnabled) {
      setAgi(payload.map(i => i.gid).join(','))
    } else if (!userState.groupEnabled || (Array.isArray(payload) && !payload.length)) {
      removeAgi();
    }
  },
  ['SET_PREV_GROUP'](userState: State, payload: any[]) {
    userState.prevGroup = Array.isArray(payload) ? payload : [];
  },
  ['SET_GROUP_LIST'](userState: State, payload: any[]) {
    if (Array.isArray(payload)) {
      userState.groupList = payload;
    }
  },
  ['SET_GROUP_ENABLED'](userState: State, payload: number) {
    if (typeof payload === 'number') {
      userState.groupEnabled = Boolean(payload);
      if (!payload) {
        removeAgi();
      }
    }
  },
  ['SET_AI_ENABLED'](userState: State, payload: boolean) {
    userState.aiEnabled = !!payload;
  },
  ['SET_LOGO_CONFIG'](userState: State, payload: any) {
    userState.logoConfig = payload;
  },
};

const getters: GetterTree<State, any> = {
  getMenus: (userState) => userState.menus,
  getMenusTree: (userState) => userState.menusTree,
  getCurrMenu: (userState) => userState.currMenu,
  getUserInfo: (userState) => userState.userInfo,
  getUserRoleList: (userState) => userState.roleList,
  getGroupEnabled: (userState) => userState.groupEnabled,
  hasSystemNoticeMenu: (userState) => userState.menus.find((menu) => menu.path === '/sysManage/notice'),
  hasNetworkMenu: (userState) => userState.menus.some((menu) => menu.path === '/npm'),
  isExpireLimit: (userState) => userState.expireLimit,
  getCurrGroup: (userState) => userState.currGroup,
  getPrevGroup: (userState) => userState.prevGroup,
  getGroupMapping: (userState) => {
    const mapping: any = {};
    userState.groupList.forEach((g: any) => {
      mapping[g.id] = g.name;
    })
    return mapping;
  },
  getIsAdmin: (userState) => userState.userInfo?.currentRole?.roleName === 'Administrator',
  getHasAlarmManageAuth: (userState) => {
    if (userState.userInfo?.currentRole?.roleName === 'Administrator') {
      return true
    } else {
      if (!userState.groupEnabled) {
        return true
      } else {
        const hasEdit = userState.currGroup.some(g => g.configAuth);
        return hasEdit;
      }
    }
  },
  getAIEnabled: (userState) => userState.aiEnabled,
  getLogoConfig: (userState) => userState.logoConfig,
};

export default {
  namespaced: true,
  state,
  actions,
  mutations,
  getters,
};
