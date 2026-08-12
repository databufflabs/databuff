package com.databuff.apm.web.admin.service;

import com.databuff.apm.web.admin.account.PortalPasswordCodec;
import com.databuff.apm.web.admin.account.PortalUserManagementService;
import com.databuff.apm.web.admin.settings.SessionIdleSettingsService;
import com.databuff.apm.web.auth.AuthService;
import com.databuff.apm.web.auth.JwtTokenService;
import com.databuff.apm.web.config.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class UserServiceTest {

    private final PortalUserManagementService portalUsers = new PortalUserManagementService("admin", "Databuff@123");

    private final UserService userService = new UserService(
            new AuthService(
                    new JwtTokenService(new JwtProperties("secret", 3600)),
                    portalUsers,
                    new SessionIdleSettingsService()),
            portalUsers,
            null);

    @Test
    void loginReturnsPortalToken() {
        Map<String, Object> response = userService.login(Map.of(
                "account", "admin",
                "password", PortalPasswordCodec.md5Hex("Databuff@123")));
        assertThat(response.get("status")).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertThat(data.get("token")).isNotNull();
        assertThat(data.get("cid")).isNotNull();
    }

    @Test
    void menusRequireAuthorization() {
        Map<String, Object> login = userService.login(Map.of(
                "account", "admin",
                "password", PortalPasswordCodec.md5Hex("Databuff@123")));
        @SuppressWarnings("unchecked")
        String token = (String) ((Map<String, Object>) login.get("data")).get("token");
        Map<String, Object> menus = userService.menus(token);
        assertThat(menus.get("status")).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) menus.get("data");
        @SuppressWarnings("unchecked")
        var menuList = (java.util.List<Map<String, Object>>) data.get("menu");
        assertThat(menuList).isNotEmpty();
        assertThat(menuList.stream().noneMatch(item -> "/sysManage/account".equals(item.get("path")))).isTrue();
    }

    @Test
    void productVersionFallsBackToDevWithoutBuildInfo() {
        assertThat(userService.productVersion().get("data")).isEqualTo("dev");
    }

    @Test
    void productVersionUsesBuildProperties() {
        Properties props = new Properties();
        props.setProperty("version", "0.1.7");
        UserService withBuild = new UserService(
                new AuthService(
                        new JwtTokenService(new JwtProperties("secret", 3600)),
                        portalUsers,
                        new SessionIdleSettingsService()),
                portalUsers,
                new BuildProperties(props));
        assertThat(withBuild.productVersion().get("data")).isEqualTo("0.1.7");
    }
}
