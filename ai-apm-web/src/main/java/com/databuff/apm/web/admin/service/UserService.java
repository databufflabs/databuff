package com.databuff.apm.web.admin.service;

import com.databuff.apm.web.auth.AuthService;
import com.databuff.apm.web.config.common.CommonResponse;
import com.databuff.apm.web.admin.account.PortalUserAccount;
import com.databuff.apm.web.admin.account.PortalUserManagementService;
import com.databuff.apm.web.admin.support.OpenSourceMenuCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {

    private final AuthService authService;
    private final PortalUserManagementService portalUserManagementService;
    @Nullable
    private final BuildProperties buildProperties;

    public UserService(
            AuthService authService,
            PortalUserManagementService portalUserManagementService,
            @Autowired(required = false) @Nullable BuildProperties buildProperties) {
        this.authService = authService;
        this.portalUserManagementService = portalUserManagementService;
        this.buildProperties = buildProperties;
    }

    public Map<String, Object> login(Map<String, Object> body) {
        String account = stringValue(body.get("account"));
        String password = stringValue(body.get("password"));
        Optional<AuthService.PortalLoginPayload> login = authService.portalLogin(account, password);
        if (login.isEmpty()) {
            return CommonResponse.fail(401, "帐号或密码错误");
        }
        return CommonResponse.ok(login.get().body());
    }

    public Map<String, Object> logout() {
        return CommonResponse.ok(null);
    }

    public Map<String, Object> imgcapt() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("img_uuID", UUID.randomUUID().toString());
        data.put("imgcapt", "0000");
        return CommonResponse.ok(data);
    }

    public Map<String, Object> menus(String authorizationHeader) {
        Optional<String> account = authService.resolveAccount(authorizationHeader);
        if (account.isEmpty()) {
            return CommonResponse.fail(3000, "登录已过期，请重新登录");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("menu", OpenSourceMenuCatalog.menus());
        return CommonResponse.ok(data);
    }

    public Map<String, Object> userInfo(String authorizationHeader) {
        Optional<String> account = authService.resolveAccount(authorizationHeader);
        if (account.isEmpty()) {
            return CommonResponse.fail(3000, "登录已过期，请重新登录");
        }
        Optional<PortalUserAccount> user = portalUserManagementService.findByAccount(account.get());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", user.map(PortalUserAccount::id).orElse(1L));
        payload.put("account", account.get());
        payload.put("email", user.map(PortalUserAccount::email).orElse(""));
        payload.put("cid", "NzhEMjlBOTk3MzkxRjk5MjQyMzMzQzI4");
        payload.put("responsible", user.map(PortalUserAccount::responsible).orElse(account.get()));
        Map<String, Object> role = new LinkedHashMap<>();
        role.put("roleId", 1);
        role.put("roleName", "Administrator");
        payload.put("currentRole", role);
        return CommonResponse.ok(payload);
    }

    public Map<String, Object> findRoleGroupByUser(String authorizationHeader) {
        Optional<String> account = authService.resolveAccount(authorizationHeader);
        if (account.isEmpty()) {
            return CommonResponse.fail(3000, "登录已过期，请重新登录");
        }
        Map<String, Object> role = new LinkedHashMap<>();
        role.put("roleId", 1);
        role.put("roleName", "Administrator");
        role.put("roleGroupRelations", List.of());
        return CommonResponse.ok(List.of(role));
    }

    /** Product UI version from Maven {@code project.version} (root pom {@code <revision>}). */
    public Map<String, Object> productVersion() {
        return CommonResponse.ok(resolveProductVersion());
    }

    String resolveProductVersion() {
        if (buildProperties != null) {
            String version = buildProperties.getVersion();
            if (version != null && !version.isBlank()) {
                return version.trim();
            }
        }
        return "dev";
    }

    public Map<String, Object> authLangs() {
        return CommonResponse.ok(List.of("zh_CN", "en_US"));
    }

    public Map<String, Object> isActivate() {
        return CommonResponse.ok(0);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
