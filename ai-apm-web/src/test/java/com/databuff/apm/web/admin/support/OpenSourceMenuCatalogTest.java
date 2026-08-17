package com.databuff.apm.web.admin.support;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class OpenSourceMenuCatalogTest {

    @Test
    void aiPlatformMenuIsAboveCockpit() {
        List<Map<String, Object>> menus = OpenSourceMenuCatalog.menus();

        assertThat(menus.get(0))
                .containsEntry("id", 17)
                .containsEntry("name", "AI 平台")
                .containsEntry("path", "/aiPlatform")
                .containsEntry("order", 0.5)
                .containsEntry("leaf", false);
        assertThat(menus.get(1))
                .containsEntry("id", 1701)
                .containsEntry("name", "AI 对话")
                .containsEntry("path", "/aiPlatform/chat");
        assertThat(menus.get(5))
                .containsEntry("id", 1)
                .containsEntry("name", "全局大盘")
                .containsEntry("path", "/cockpit")
                .containsEntry("order", 1.0);
    }

    @Test
    void aiPlatformSidebarMenusMatchRouteData() {
        List<Map<String, Object>> menus = OpenSourceMenuCatalog.menus();
        List<String> aiPlatformPaths = menus.stream()
                .filter(m -> Integer.valueOf(17).equals(m.get("parentId")))
                .map(m -> (String) m.get("path"))
                .sorted()
                .toList();

        assertThat(aiPlatformPaths).containsExactly(
                "/aiPlatform/chat",
                "/aiPlatform/experts",
                "/aiPlatform/skills",
                "/aiPlatform/tools");
    }

    @Test
    void appMonitorSidebarMenusMatchRouteData() {
        List<Map<String, Object>> menus = OpenSourceMenuCatalog.menus();
        List<String> appMonitorPaths = menus.stream()
                .filter(m -> Integer.valueOf(4).equals(m.get("parentId")))
                .map(m -> (String) m.get("path"))
                .sorted()
                .collect(Collectors.toList());

        assertThat(appMonitorPaths).containsExactly(
                "/appMonitor/cache",
                "/appMonitor/database",
                "/appMonitor/errors",
                "/appMonitor/external",
                "/appMonitor/globalTopology",
                "/appMonitor/logs",
                "/appMonitor/msgQueue",
                "/appMonitor/service",
                "/appMonitor/serviceAnalysis",
                "/appMonitor/serviceFlow",
                "/appMonitor/trace");
    }

    @Test
    void appMonitorIsAboveConfigManage() {
        List<Map<String, Object>> menus = OpenSourceMenuCatalog.menus();
        double appMonitorOrder = menus.stream()
                .filter(m -> "/appMonitor".equals(m.get("path")))
                .mapToDouble(m -> ((Number) m.get("order")).doubleValue())
                .findFirst()
                .orElseThrow();
        double configManageOrder = menus.stream()
                .filter(m -> "/config/manage".equals(m.get("path")))
                .mapToDouble(m -> ((Number) m.get("order")).doubleValue())
                .findFirst()
                .orElseThrow();

        assertThat(appMonitorOrder).isLessThan(configManageOrder);
    }

    @Test
    void configManageAlarmMenusMatchRouteData() {
        List<Map<String, Object>> menus = OpenSourceMenuCatalog.menus();

        Map<String, Object> configMenu = menus.stream()
                .filter(m -> "/config/manage".equals(m.get("path")))
                .findFirst()
                .orElseThrow();
        assertThat(configMenu)
                .containsEntry("name", "配置管理")
                .containsEntry("icon", "db-icon-deploy");

        List<String> configChildren = menus.stream()
                .filter(m -> Integer.valueOf(1003).equals(m.get("parentId")))
                .map(m -> (String) m.get("path"))
                .sorted()
                .toList();

        assertThat(configChildren).containsExactly(
                "/config/alarm",
                "/config/capabilities",
                "/config/llm",
                "/config/login");
    }

    @Test
    void deployStatusHasTimeAndRefresh() {
        List<Map<String, Object>> menus = OpenSourceMenuCatalog.menus();
        Map<String, Object> status = menus.stream()
                .filter(m -> "/deploy/status".equals(m.get("path")))
                .findFirst()
                .orElseThrow();
        assertThat(status)
                .containsEntry("name", "部署状态")
                .containsEntry("time", true)
                .containsEntry("refresh", true);
        assertThat(menus.stream().noneMatch(m -> String.valueOf(m.get("path")).startsWith("/selfMonitor")))
                .isTrue();
    }

    @Test
    void openSourceMenuExcludesInfrastructureNpmAndSysManage() {
        List<String> paths = OpenSourceMenuCatalog.menus().stream()
                .map(m -> String.valueOf(m.get("path")))
                .toList();

        assertThat(paths).noneMatch(path -> path.startsWith("/infrastructure"));
        assertThat(paths).noneMatch(path -> path.startsWith("/npm"));
        assertThat(paths).noneMatch(path -> path.startsWith("/sysManage"));
    }
}
