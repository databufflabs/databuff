package com.databuff.apm.common.meta;

import com.databuff.apm.common.model.DcSpan;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class VirtualServiceResolverTest {

    @Test
    void resolvesDbVirtualServiceFromOutboundSpan() {
        DcSpan span = new DcSpan();
        span.service = "checkout";
        span.serviceId = "checkout-id";
        span.isOut = 1;
        span.type = "SPAN_KIND_CLIENT";
        span.meta = "{\"db.system\":\"mysql\",\"db.name\":\"orders\",\"server.address\":\"10.0.0.8\",\"server.port\":\"3306\"}";

        VirtualServiceResolver.ResolvedVirtualService resolved = VirtualServiceResolver.resolve(span);

        assertThat(resolved).isNotNull();
        assertThat(resolved.service()).isEqualTo("[mysql]orders");
        assertThat(resolved.serviceInstance()).isEqualTo("10.0.0.8");
        assertThat(resolved.serviceType()).isEqualTo("db");
    }

    @Test
    void resolvesElasticsearchVirtualServiceAsDatabase() {
        DcSpan span = new DcSpan();
        span.service = "checkout";
        span.serviceId = "checkout-id";
        span.isOut = 1;
        span.type = "SPAN_KIND_CLIENT";
        span.name = "elasticsearch.rest.query";
        span.meta = "{\"db.system\":\"elasticsearch\",\"db.elasticsearch.index\":\"orders\","
                + "\"server.address\":\"es\",\"server.port\":\"9200\"}";

        VirtualServiceResolver.ResolvedVirtualService resolved = VirtualServiceResolver.resolve(span);

        assertThat(resolved).isNotNull();
        assertThat(resolved.service()).isEqualTo("[elasticsearch]es:9200");
        assertThat(resolved.serviceType()).isEqualTo("db");
        assertThat(resolved.typeIcon()).isEqualTo("elasticsearch");
    }

    @Test
    void resolvesMqVirtualServiceFromOutboundSpan() {
        DcSpan span = new DcSpan();
        span.service = "checkout";
        span.isOut = 1;
        span.type = "SPAN_KIND_CLIENT";
        span.meta = "{\"messaging.system\":\"kafka\",\"messaging.destination.name\":\"order-events\",\"net.peer.name\":\"broker-1\"}";

        VirtualServiceResolver.ResolvedVirtualService resolved = VirtualServiceResolver.resolve(span);

        assertThat(resolved).isNotNull();
        assertThat(resolved.service()).isEqualTo("[kafka]order-events");
        assertThat(resolved.serviceInstance()).isEqualTo("broker-1");
        assertThat(resolved.serviceType()).isEqualTo("mq");
    }

    @Test
    void resolvesRedisVirtualServiceTypeFromSpanComponentNotServiceName() {
        DcSpan span = new DcSpan();
        span.service = "java-redis-demo";
        span.serviceId = "java-redis-demo-id";
        span.isOut = 1;
        span.type = "SPAN_KIND_CLIENT";
        span.meta = "{\"db.system\":\"redis\",\"server.address\":\"10.0.0.9\",\"server.port\":\"6379\"}";

        VirtualServiceResolver.ResolvedVirtualService resolved = VirtualServiceResolver.resolve(span);

        assertThat(resolved).isNotNull();
        assertThat(resolved.service()).isEqualTo("[redis]10.0.0.9:6379");
        assertThat(resolved.serviceType()).isEqualTo("cache");
        assertThat(resolved.typeIcon()).isEqualTo("redis");
    }

    @Test
    void ignoresNonOutboundSpan() {
        DcSpan span = new DcSpan();
        span.isOut = 0;
        span.meta = "{\"db.system\":\"mysql\",\"db.name\":\"orders\",\"server.address\":\"10.0.0.8\"}";

        assertThat(VirtualServiceResolver.resolve(span)).isNull();
    }

    @Test
    void doesNotCreateHttpOrRpcVirtualServices_useRemoteCallProcessorInstead() {
        DcSpan http = new DcSpan();
        http.service = "service-a";
        http.isOut = 1;
        http.type = "SPAN_KIND_CLIENT";
        http.metaHttpMethod = "GET";
        http.meta = "{\"http.method\":\"GET\",\"server.address\":\"service-b\",\"server.port\":\"8080\"}";
        assertThat(VirtualServiceResolver.resolve(http, Set.of("service-a", "service-b"))).isNull();

        DcSpan rpc = new DcSpan();
        rpc.service = "service-a";
        rpc.isOut = 1;
        rpc.type = "SPAN_KIND_CLIENT";
        rpc.meta = "{\"rpc.system\":\"dubbo\",\"net.peer.name\":\"service-b\",\"net.peer.port\":\"20880\"}";
        assertThat(VirtualServiceResolver.resolve(rpc, Set.of("service-a", "service-b"))).isNull();
    }

    @Test
    void normalizePeerHostKeepsIpv6Literal() {
        assertThat(VirtualServiceResolver.normalizePeerHost("[2001:db8::1]:3306"))
                .isEqualTo("2001:db8::1");
        assertThat(VirtualServiceResolver.normalizePeerHost("[2001:db8::1]"))
                .isEqualTo("2001:db8::1");
        assertThat(VirtualServiceResolver.normalizePeerHost("[mysql]10.0.0.8:3306"))
                .isEqualTo("10.0.0.8");
    }

    @Test
    void doesNotUseJdbcConnectionStringAsPeerHost() {
        DcSpan span = new DcSpan();
        span.service = "checkout";
        span.isOut = 1;
        span.type = "SPAN_KIND_CLIENT";
        span.meta = "{\"db.system\":\"mysql\",\"db.connection_string\":\"jdbc:mysql://10.0.0.8:3306/orders\"}";

        VirtualServiceResolver.ResolvedVirtualService resolved = VirtualServiceResolver.resolve(span);
        assertThat(resolved).isNotNull();
        assertThat(resolved.service()).doesNotContain("jdbc:");
    }
}
