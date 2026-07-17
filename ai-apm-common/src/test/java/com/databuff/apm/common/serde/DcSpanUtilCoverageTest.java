package com.databuff.apm.common.serde;

import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.model.OptimizedMetric;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DcSpanUtilCoverageTest {

    @Test
    void detectsServiceEntrySpanByIsInOnly() {
        DcSpan entry = new DcSpan();
        entry.parent_id = "0";
        entry.isIn = 1;
        assertThat(DcSpanUtil.isServiceEntrySpan(entry)).isTrue();

        DcSpan rootOutbound = new DcSpan();
        rootOutbound.parent_id = "";
        rootOutbound.isIn = 0;
        rootOutbound.isOut = 1;
        assertThat(DcSpanUtil.isServiceEntrySpan(rootOutbound)).isFalse();

        DcSpan child = new DcSpan();
        child.parent_id = "parent";
        assertThat(DcSpanUtil.isServiceEntrySpan(child)).isFalse();

        DcSpan inboundChild = new DcSpan();
        inboundChild.parent_id = "parent";
        inboundChild.isIn = 1;
        assertThat(DcSpanUtil.isServiceEntrySpan(inboundChild)).isTrue();
    }

    @Test
    void outboundRootRpcDoesNotWriteServiceMetric() {
        DcSpan span = new DcSpan();
        span.parent_id = "";
        span.is_parent = 1;
        span.isIn = 0;
        span.isOut = 1;
        span.type = "SPAN_KIND_CLIENT";
        span.service = "fraud-detection";
        span.serviceId = "fd";
        span.serviceInstance = "i1";
        span.resource = "flagd.evaluation.v1.Service/EventStream";
        span.name = span.resource;
        span.duration = 600_000_000_000L;
        span.start = 1L;
        span.end = span.start + span.duration;
        span.meta = "{\"rpc.system\":\"grpc\",\"rpc.method\":\"EventStream\"}";

        assertThat(DcSpanUtil.parseSpanData(span).stream().map(OptimizedMetric::measurement))
                .contains("service.rpc")
                .doesNotContain("service");
    }

    @Test
    void resolvesRpcSystemAndProtocolUrl() {
        assertThat(DcSpanUtil.resolveRpcSystem(Map.of("rpc.system", "grpc"), "op")).isEqualTo("grpc");
        assertThat(DcSpanUtil.isRpcProtocolUrl("grpc://checkout:50051/OrderService")).isTrue();
        assertThat(DcSpanUtil.rpcSystemFromSkyWalkingComponentId(23)).isEqualTo("grpc");
    }

    @Test
    void normalizesSqlAndHttpUrl() {
        assertThat(DcSpanUtil.normalizeSqlOperation("  SELECT ")).isEqualTo("SELECT");
        assertThat(DcSpanUtil.normalizeHttpUrl("/api/orders/123")).isEqualTo("/api/orders/123");
        assertThat(DcSpanUtil.normalizeHttpUrl("http://host/api/orders/123")).isEqualTo("/api/orders/123");
    }

    @Test
    void resolvesDbPeerAndPortalServiceType() {
        DcSpan span = new DcSpan();
        span.service = "[mysql]orders";
        span.meta = "{\"db.system\":\"mysql\",\"server.address\":\"10.0.0.1\",\"server.port\":\"3306\"}";
        assertThat(DcSpanUtil.resolveDbPeer(span, OtelAttributeMaps.parse(span))).contains("10.0.0.1");
        assertThat(DcSpanUtil.resolvePortalServiceType(span)).isEqualTo("db");
    }

    @Test
    void serviceInstanceTagsFromResource() {
        DcSpan span = new DcSpan();
        span.service = "checkout";
        span.serviceId = "checkout-id";
        span.hostName = "h1";
        Map<String, String> meta = OtelAttributeMaps.parse("{\"k8s.pod.name\":\"pod-1\",\"process.pid\":\"99\"}");
        Map<String, String> tags = DcSpanUtil.serviceInstanceTags(span, "inst-1", meta);
        assertThat(tags).containsEntry("hostname", "h1").containsEntry("k8sPodName", "pod-1");
    }
}
