package com.databuff.apm.ingest.meta;

import com.databuff.apm.common.storage.DorisJsonRow;

import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.ingest.metric.MetricWriteRouter;
import com.databuff.apm.common.storage.DorisBatchWriter;
import com.databuff.apm.common.storage.DorisTableNames;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceInstanceRegistryTest {

    @Test
    void cachesInstanceAndFlushesHeartbeatMetric() throws Exception {
        DorisBatchWriter instanceWriter = new DorisBatchWriter();
        MetricWriteRouter router = new MetricWriteRouter(
                Map.of(DorisTableNames.METRIC_SERVICE_INSTANCE, instanceWriter));
        ServiceInstanceRegistry registry = new ServiceInstanceRegistry(router, 60_000L);

        DcSpan span = new DcSpan();
        span.service = "demo-order";
        span.serviceId = "464a0a08964a061e";
        span.serviceInstance = "demo-order-1";
        span.hostName = "app-1";
        span.meta = "{\"host.ip\":\"10.0.0.8\",\"k8s.namespace.name\":\"prod\"}";

        registry.remember(span);
        assertThat(registry.cachedSize()).isEqualTo(1);

        registry.flushHeartbeats();
        assertThat(registry.cachedSize()).isZero();
        assertThat(instanceWriter.pendingCount()).isEqualTo(1);

        String json = new String(DorisJsonRow.toByteArray(instanceWriter.flushAll().get(0)));
        assertThat(json).contains("\"service\":\"demo-order\"");
        assertThat(json).contains("\"service_instance\":\"demo-order-1\"");
        assertThat(json).contains("\"metricsVal\":1");
        assertThat(json).contains("\"hostIp\":\"10.0.0.8\"");
    }

    @Test
    void mergesHostNameWhenLaterSpanProvidesIt() throws Exception {
        DorisBatchWriter writer = new DorisBatchWriter();
        ServiceInstanceRegistry registry = new ServiceInstanceRegistry(
                new MetricWriteRouter(Map.of(DorisTableNames.METRIC_SERVICE_INSTANCE, writer)), 60_000L);

        DcSpan first = new DcSpan();
        first.serviceId = "svc";
        first.service = "checkout";
        first.serviceInstance = "inst-1";

        DcSpan second = new DcSpan();
        second.serviceId = "svc";
        second.service = "checkout";
        second.serviceInstance = "inst-1";
        second.hostName = "host-2";

        registry.remember(first);
        registry.remember(second);
        registry.flushHeartbeats();

        assertThat(new String(DorisJsonRow.toByteArray(writer.flushAll().get(0)))).contains("\"hostname\":\"host-2\"");
    }
}
