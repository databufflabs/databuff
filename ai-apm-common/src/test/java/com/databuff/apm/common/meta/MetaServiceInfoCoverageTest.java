package com.databuff.apm.common.meta;

import com.databuff.apm.common.query.ApmQueryModels;
import com.databuff.apm.common.model.DcSpan;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetaServiceInfoCoverageTest {

    @Test
    void minimalAndNullFactories() {
        assertThat(MetaServiceInfo.minimal("id-1", "checkout").service()).isEqualTo("checkout");
        assertThat(MetaServiceInfo.fromDcSpan(null)).isNull();
        assertThat(MetaServiceInfo.fromPoint(null)).isNull();
        assertThat(MetaServiceInfo.fromMetric(" ", "x", "{}")).isNull();
        assertThat(MetaServiceInfo.fromVirtualService(null, "svc", "web", "web")).isNull();
    }

    @Test
    void fromPointAndMetric() {
        ApmQueryModels.MetaServicePoint point = new ApmQueryModels.MetaServicePoint(
                "svc-id", "Checkout", "checkout", "web", "key", "java", "jvm", "java",
                "OTLP", "k8s", "host-1", "pod-1", true, "desc", "{}", "OpenJDK", "17");
        MetaServiceInfo fromPoint = MetaServiceInfo.fromPoint(point);
        assertThat(fromPoint).isNotNull();
        assertThat(fromPoint.toRow("2026-06-01").get("virtual_service")).isEqualTo(1);

        MetaServiceInfo fromMetric = MetaServiceInfo.fromMetric(
                "svc-id", "checkout", "{\"process.runtime.name\":\"OpenJDK\"}");
        assertThat(fromMetric.toRow("2026-06-01").get("technology")).isEqualTo("openjdk");
    }

    @Test
    void mergeAndEnrichmentDiffers() {
        MetaServiceInfo base = MetaServiceInfo.fromNames(
                "id-1", "svc", "svc", java.util.Map.of("host.name", "h1"), false);
        MetaServiceInfo other = MetaServiceInfo.fromNames(
                "id-1", "svc", "svc", java.util.Map.of("host.name", "h2", "k8s.pod.name", "p1"), false);
        MetaServiceInfo merged = base.merge(other);
        assertThat(merged.toRow("2026-06-01").get("fqdn")).isIn("h1", "h2");
        assertThat(base.enrichmentDiffers(other)).isTrue();
        assertThat(base.enrichmentDiffers(base)).isFalse();
    }

    @Test
    void classifiesVirtualRpcAndCache() {
        MetaServiceInfo rpc = MetaServiceInfo.fromVirtualService("rpc-id", "[grpc]checkout", "rpc", "grpc");
        assertThat(rpc.toRow("2026-06-01").get("service_type")).isEqualTo("rpc");

        MetaServiceInfo redis = MetaServiceInfo.fromVirtualService("r-id", "[redis]cache", "cache", "redis");
        assertThat(redis.toRow("2026-06-01").get("service_type")).isEqualTo("cache");
    }
}
