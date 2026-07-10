package com.databuff.apm.common.flow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceFlowSpanRulesTest {

    @Test
    void detectsSqlResources() {
        assertThat(ServiceFlowSpanRules.isComponentResource("INSERT INTO demo_order VALUES (?)")).isTrue();
        assertThat(ServiceFlowSpanRules.isComponentResource("GET /demo/checkout")).isFalse();
    }

    @Test
    void classifiesVirtualAndDisplayResource() {
        assertThat(ServiceFlowSpanRules.isVirtualSpan(null)).isTrue();
        com.databuff.apm.common.model.DcSpan db = new com.databuff.apm.common.model.DcSpan();
        db.type = "mysql";
        db.meta = "{\"db.system\":\"mysql\"}";
        assertThat(ServiceFlowSpanRules.isVirtualSpan(db)).isTrue();

        com.databuff.apm.common.model.DcSpan web = new com.databuff.apm.common.model.DcSpan();
        web.service = "checkout";
        web.resource = "GET /orders";
        assertThat(ServiceFlowSpanRules.isVirtualServiceSpan(web)).isFalse();
        assertThat(ServiceFlowSpanRules.displayResource(web)).isEqualTo("GET /orders");

        com.databuff.apm.common.model.DcSpan virtual = new com.databuff.apm.common.model.DcSpan();
        virtual.service = "[mysql]db";
        assertThat(ServiceFlowSpanRules.isVirtualServiceSpan(virtual)).isTrue();
        assertThat(ServiceFlowSpanRules.displayResource(virtual)).isEmpty();
    }

    @Test
    void detectsRedisCommandsAndHttpResources() {
        assertThat(ServiceFlowSpanRules.isComponentResource("GET key")).isTrue();
        assertThat(ServiceFlowSpanRules.isComponentResource("POST /api/v1/orders")).isFalse();
        assertThat(ServiceFlowSpanRules.isComponentResource(null)).isTrue();
    }
}
