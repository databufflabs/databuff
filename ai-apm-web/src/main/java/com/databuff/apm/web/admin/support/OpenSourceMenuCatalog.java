package com.databuff.apm.web.admin.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Open-source portal menu subset aligned with databuff-portal route ids/paths. */
public final class OpenSourceMenuCatalog {

    private OpenSourceMenuCatalog() {
    }

    public static List<Map<String, Object>> menus() {
        List<Map<String, Object>> menus = new ArrayList<>();
        menus.add(menu(0, 17, "AI 平台", "/aiPlatform", "db-icon-ai", true, false, 0.5, false, false));
        menus.add(menu(17, 1701, "AI 对话", "/aiPlatform/chat", null, true, true, 1, false, false));
        menus.add(menu(17, 1702, "工具管理", "/aiPlatform/tools", null, true, true, 2, false, false));
        menus.add(menu(17, 1703, "技能管理", "/aiPlatform/skills", null, true, true, 3, false, false));
        menus.add(menu(17, 1704, "数字专家", "/aiPlatform/experts", null, true, true, 4, false, false));
        menus.add(menu(0, 1, "全局大盘", "/cockpit", "db-icon-cockpit", true, true, 1, true, true));
        menus.add(menu(0, 3, "告警中心", "/alarmCenter", "db-icon-alarm", true, false, 3, false, false));
        menus.add(menu(3, 301, "告警列表", "/alarmCenter/alarm", null, true, true, 1, true, true));
        menus.add(menu(3, 302, "事件列表", "/alarmCenter/event", null, true, true, 2, true, true));
        menus.add(menu(0, 4, "应用性能", "/appMonitor", "db-icon-app-service", true, false, 4, false, false));
        menus.add(menu(4, 401, "全局拓扑", "/appMonitor/globalTopology", null, true, true, 1, true, true));
        menus.add(menu(4, 410, "服务流", "/appMonitor/serviceFlow", null, true, true, 3.5, true, true));
        menus.add(menu(4, 403, "服务", "/appMonitor/service", null, true, true, 3, true, true));
        menus.add(menu(4, 404, "数据库", "/appMonitor/database", null, true, true, 4, true, true));
        menus.add(menu(4, 405, "消息队列", "/appMonitor/msgQueue", null, true, true, 5, true, true));
        menus.add(menu(4, 406, "缓存", "/appMonitor/cache", null, true, true, 6, true, true));
        menus.add(menu(4, 412, "外部服务", "/appMonitor/external", null, true, true, 6.5, true, true));
        menus.add(menu(4, 407, "接口分析", "/appMonitor/serviceAnalysis", null, true, true, 7, true, true));
        menus.add(menu(4, 408, "错误分析", "/appMonitor/errors", null, true, true, 8, true, true));
        menus.add(menu(4, 409, "链路追踪", "/appMonitor/trace", null, true, true, 9, true, true));
        menus.add(menu(4, 431, "日志分析", "/appMonitor/logs", null, true, true, 9.2, true, true));
        menus.add(menu(0, 18, "Agent观测", "/aiMonitor", "db-icon-agent", true, false, 4.5, false, false));
        menus.add(menu(18, 1801, "Agent列表", "/aiMonitor/applications", null, true, true, 1, true, true));
        menus.add(menu(18, 1802, "Agent拓扑", "/aiMonitor/topology", null, true, true, 2, true, true));
        menus.add(menu(18, 1808, "技能调用", "/aiMonitor/skillCalls", null, true, true, 3, true, true));
        menus.add(menu(18, 1805, "工具调用", "/aiMonitor/toolCalls", null, true, true, 4, true, true));
        menus.add(menu(18, 1804, "模型调用", "/aiMonitor/modelCalls", null, true, true, 5, true, true));
        menus.add(menu(18, 1803, "对话追踪", "/aiMonitor/sessions", null, true, true, 6, true, true));
        menus.add(menu(18, 1806, "Token分析", "/aiMonitor/tokens", null, true, true, 7, true, true));
        menus.add(menu(18, 1807, "错误分析", "/aiMonitor/errors", null, true, true, 8, true, true));
        menus.add(menu(0, 12, "安装部署", "/deploy", "db-icon-deploy", true, false, 9.5, false, false));
        menus.add(menu(12, 1201, "数据接入", "/deploy/access", null, true, true, 1, false, false));
        menus.add(menu(12, 1202, "部署状态", "/deploy/status", null, true, true, 2, false, false));
        menus.add(menu(0, 1003, "配置管理", "/config/manage", "db-icon-deploy", true, false, 12, false, false));
        menus.add(menu(1003, 1020, "告警配置", "/config/alarm", null, true, true, 1, false, false));
        menus.add(menu(1003, 1035, "模型配置", "/config/llm", null, true, true, 2, false, false));
        menus.add(menu(1003, 1036, "登录设置", "/config/login", null, true, true, 2.5, false, false));
        return menus;
    }

    private static Map<String, Object> menu(
            int parentId,
            int id,
            String name,
            String path,
            String icon,
            boolean isMenu,
            boolean leaf,
            double order,
            boolean time,
            boolean refresh) {
        Map<String, Object> menu = new LinkedHashMap<>();
        menu.put("parentId", parentId);
        menu.put("parent_id", parentId);
        menu.put("id", id);
        menu.put("name", name);
        menu.put("path", path);
        if (icon != null) {
            menu.put("icon", icon);
        }
        menu.put("isMenu", isMenu);
        menu.put("leaf", leaf);
        menu.put("order", order);
        menu.put("time", time);
        menu.put("refresh", refresh);
        // Portal DB convention: hidden=false means hidden; visible menus use hidden=true.
        menu.put("hidden", true);
        return menu;
    }
}
