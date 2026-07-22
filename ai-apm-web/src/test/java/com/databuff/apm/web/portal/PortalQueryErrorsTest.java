package com.databuff.apm.web.portal;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PortalQueryErrorsTest {

    @Test
    void mapsVersionGraphToFriendlyFailEnvelope() {
        Map<String, Object> resp = PortalQueryErrors.fail(
                "查询调用链列表",
                new SQLException("errCode = 2, detailMessage = fail to find path in version_graph. spec_version: 0-4532"));
        assertThat(resp.get("status")).isEqualTo(500);
        assertThat(String.valueOf(resp.get("message")))
                .contains("查询调用链列表失败")
                .contains("版本链损坏");
    }

    @Test
    void keepsRuntimeExceptionAsIs() {
        IllegalStateException original = new IllegalStateException("boom");
        assertThat(PortalQueryErrors.propagate(original)).isSameAs(original);
    }

    @Test
    void wrapsCheckedException() {
        SQLException checked = new SQLException("down");
        RuntimeException wrapped = PortalQueryErrors.propagate(checked);
        assertThat(wrapped).isNotInstanceOf(SQLException.class);
        assertThat(wrapped.getCause()).isSameAs(checked);
    }
}
