package com.databuff.apm.ingest.meta;

import com.databuff.apm.common.storage.DorisJsonRow;

import com.databuff.apm.common.meta.VirtualServiceResolver;
import com.databuff.apm.ingest.metric.MetricWriteRouter;
import com.databuff.apm.common.storage.DorisBatchWriter;
import com.databuff.apm.common.storage.DorisTableNames;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VirtualServiceInstanceRegistryTest {

    @Test
    void cachesVirtualInstanceAndFlushesHeartbeatMetric() throws Exception {
        DorisBatchWriter instanceWriter = new DorisBatchWriter();
        MetricWriteRouter router = new MetricWriteRouter(
                Map.of(DorisTableNames.METRIC_SERVICE_INSTANCE, instanceWriter));
        VirtualServiceInstanceRegistry registry = new VirtualServiceInstanceRegistry(router, 60_000L);

        registry.remember(new VirtualServiceResolver.ResolvedVirtualService(
                "abc123",
                "[mysql]orders",
                "10.0.0.8",
                "db",
                "mysql",
                "10.0.0.8",
                "3306"));
        assertThat(registry.cachedSize()).isEqualTo(1);

        registry.flushHeartbeats();
        assertThat(registry.cachedSize()).isZero();
        assertThat(instanceWriter.pendingCount()).isEqualTo(1);

        String json = new String(DorisJsonRow.toByteArray(instanceWriter.flushAll().get(0)));
        assertThat(json).contains("\"service\":\"[mysql]orders\"");
        assertThat(json).contains("\"service_instance\":\"10.0.0.8\"");
        assertThat(json).contains("\"virtualService\":\"1\"");
        assertThat(json).contains("\"metricsVal\":1");
    }
}
