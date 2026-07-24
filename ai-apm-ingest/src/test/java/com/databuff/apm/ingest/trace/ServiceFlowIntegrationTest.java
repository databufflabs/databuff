package com.databuff.apm.ingest.trace;

import com.databuff.apm.common.cluster.cache.CacheRegionPolicy;
import com.databuff.apm.common.cluster.cache.ClusterCacheRegistry;
import com.databuff.apm.common.flow.ServiceFlowPathIds;
import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.common.metric.MetricSchemaRegistry;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.model.OptimizedMetric;
import com.databuff.apm.common.storage.DorisBatchWriter;
import com.databuff.apm.common.storage.DorisTableNames;
import com.databuff.apm.ingest.meta.VirtualServiceInstanceRegistry;
import com.databuff.apm.ingest.metric.MetricWriteRouter;
import com.databuff.apm.ingest.trace.remote.PeerServerServiceCache;
import com.databuff.apm.ingest.trace.remote.RemoteAssociationStore;
import com.databuff.apm.ingest.trace.remote.RemoteCallProcessor;
import com.databuff.apm.ingest.trace.remote.RemoteServiceSettings;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Ingest integration: demo checkout trace produces service.flow metrics including virtual services. */
class ServiceFlowIntegrationTest {

    private static final String SERVICE_A_ID = "9bf61532d56eb7b5";
    private static final String DEMO_CHECKOUT_RESOURCE = "GET /demo/checkout";

    @Test
    void demoCheckoutTraceProducesServiceLevelFlowWithVirtualServices() throws Exception {
        List<DcSpan> spans = processDemoCheckoutTrace();
        List<OptimizedMetric> flowMetrics = ServiceFlowExtractor.extractFromTrace(spans);
        Set<String> services = flowMetrics.stream()
                .map(metric -> tagValue(metric, "service"))
                .collect(Collectors.toSet());

        assertThat(services).contains(
                "service-a",
                "service-b",
                "[mysql]demo_apm",
                "[redis]redis:6379",
                "[kafka]order-events",
                "[elasticsearch]es:9200",
                "[remote]payments.example.com:443");

        OptimizedMetric entry = flowMetrics.stream()
                .filter(metric -> "service-a".equals(tagValue(metric, "service")))
                .filter(metric -> tagValue(metric, "parentPathId").isBlank())
                .findFirst()
                .orElseThrow();
        assertThat(tagValue(entry, "resource")).isEqualTo(DEMO_CHECKOUT_RESOURCE);
        assertThat(tagValue(entry, "entryPathId"))
                .isEqualTo(ServiceFlowPathIds.entryPathId(SERVICE_A_ID));
    }

    @Test
    void demoCheckoutTraceProducesInterfaceLevelFlowRows() throws Exception {
        List<DcSpan> spans = processDemoCheckoutTrace();
        List<OptimizedMetric> flowMetrics = ServiceFlowExtractor.extractFromTrace(spans);
        String entryInterfacePathId = ServiceFlowPathIds.entryInterfacePathId(SERVICE_A_ID, DEMO_CHECKOUT_RESOURCE);

        List<OptimizedMetric> interfaceRows = flowMetrics.stream()
                .filter(metric -> entryInterfacePathId.equals(tagValue(metric, "entryInterfacePathId")))
                .toList();

        Set<String> services = interfaceRows.stream()
                .map(metric -> tagValue(metric, "service"))
                .collect(Collectors.toSet());

        assertThat(services).contains(
                "service-a",
                "service-b",
                "[mysql]demo_apm",
                "[redis]redis:6379",
                "[kafka]order-events",
                "[elasticsearch]es:9200",
                "[remote]payments.example.com:443");

        assertThat(interfaceRows.stream().map(metric -> tagValue(metric, "resource")).distinct())
                .contains(DEMO_CHECKOUT_RESOURCE, "GET /api/orders/{orderId}");
    }

    private static List<DcSpan> processDemoCheckoutTrace() throws Exception {
        List<DcSpan> spans = DemoTraceSpans.checkoutTrace();
        FillPathAndRelationUtil.fillRelations(spans);

        RemoteCallProcessor remoteCallProcessor = new RemoteCallProcessor(
                new RemoteServiceSettings(true, 0L, List.of()),
                new PeerServerServiceCache(),
                remoteAssociationStore(),
                null,
                virtualServiceExtractor());
        for (DcSpan span : spans) {
            if ("remote-http".equals(span.span_id)) {
                OtelAttributeMaps.put(span, "data.source", "Databuff");
            }
        }
        remoteCallProcessor.processAfterFill(spans);
        virtualServiceExtractor().extractFromTrace(spans);
        return spans;
    }

    private static VirtualServiceExtractor virtualServiceExtractor() {
        return new VirtualServiceExtractor(new VirtualServiceInstanceRegistry(
                new MetricWriteRouter(java.util.Map.of(
                        DorisTableNames.METRIC_SERVICE_INSTANCE, new DorisBatchWriter())),
                60_000L));
    }

    private static RemoteAssociationStore remoteAssociationStore() {
        ClusterCacheRegistry registry = new ClusterCacheRegistry();
        registry.region("ingest.remote", CacheRegionPolicy.REPLICATED, Duration.ofHours(1));
        return new RemoteAssociationStore(registry.get("ingest.remote"));
    }

    private static String tagValue(OptimizedMetric metric, String column) {
        String[] tags = metric.tagValues();
        var schema = MetricSchemaRegistry.schema(metric.measurement()).orElseThrow();
        int index = schema.tagColumns().indexOf(column);
        return index >= 0 && index < tags.length ? tags[index] : "";
    }
}
