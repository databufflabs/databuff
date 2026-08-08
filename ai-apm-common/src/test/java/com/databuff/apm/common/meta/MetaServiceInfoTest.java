package com.databuff.apm.common.meta;

import com.databuff.apm.common.model.DcSpan;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetaServiceInfoTest {

    @Test
    void buildsFullRowFromOtelResourceAttributes() {
        String meta = """
                {
                  "service.name":"demo-order",
                  "host.name":"app-1",
                  "telemetry.sdk.language":"java",
                  "process.runtime.name":"OpenJDK Runtime Environment",
                  "process.runtime.version":"17.0.8",
                  "k8s.namespace.name":"prod",
                  "k8s.pod.name":"demo-order-abc",
                  "deployment.environment":"prod"
                }
                """;
        DcSpan span = new DcSpan();
        span.serviceId = "demo-order-id";
        span.service = "demo-order";
        span.meta = meta;

        MetaServiceInfo info = MetaServiceInfo.fromDcSpan(span);
        assertThat(info).isNotNull();

        var row = info.toRow("2026-06-05 10:00:00");
        assertThat(row.get("id")).isEqualTo("demo-order-id");
        assertThat(row.get("name")).isEqualTo("demo-order");
        assertThat(row.get("service")).isEqualTo("demo-order");
        assertThat(row.get("service_type")).isEqualTo("web");
        assertThat(row.get("language")).isEqualTo("java");
        assertThat(row.get("technology")).isEqualTo("jvm");
        assertThat(row.get("processRuntimeName")).isEqualTo("OpenJDK Runtime Environment");
        assertThat(row.get("processRuntimeVersion")).isEqualTo("17.0.8");
        assertThat(row.get("fqdn")).isEqualTo("app-1");
        assertThat(row.get("source")).isEqualTo("k8s");
        assertThat(row.get("container_service")).isEqualTo("demo-order-abc");
        assertThat(row.get("datasource")).isEqualTo("OTLP");
        assertThat(row.get("custom_tags")).asString().contains("deployment.environment");
    }

    @Test
    void fromDcSpanDoesNotClassifyApplicationServiceFromNameOrDbAttributes() {
        String meta = """
                {"db.system":"mysql","host.name":"db-host"}
                """;
        DcSpan span = new DcSpan();
        span.serviceId = "mysql-id";
        span.service = "[mysql]dc_databuff";
        span.meta = meta;

        // fromDcSpan is the application-service path (virtual=false). Middleware types
        // must come from fromVirtualService after span component recognition.
        var row = MetaServiceInfo.fromDcSpan(span).toRow("2026-06-05 10:00:00");
        assertThat(row.get("service_type")).isEqualTo("web");
    }

    @Test
    void classifiesVirtualServiceFromAttributesWhenVirtualFlagSet() {
        var row = MetaServiceInfo.fromNames(
                "es-id",
                "[elasticsearch]es:9200",
                "[elasticsearch]es:9200",
                java.util.Map.of(
                        "db.system", "elasticsearch",
                        "db.elasticsearch.index", "orders",
                        "host.name", "es"),
                true).toRow("2026-06-05 10:00:00");
        assertThat(row.get("service_type")).isEqualTo("db");
        assertThat(row.get("type")).isEqualTo("elasticsearch");
        assertThat(row.get("virtual_service")).isEqualTo(1);
    }

    @Test
    void buildsVirtualServiceRowFromResolverClassification() {
        MetaServiceInfo info = MetaServiceInfo.fromVirtualService(
                "c72cc83a8831e407", "[mysql]demo_apm", "db", "mysql");
        assertThat(info).isNotNull();

        var row = info.toRow("2026-06-05 10:00:00");
        assertThat(row.get("service_type")).isEqualTo("db");
        assertThat(row.get("type")).isEqualTo("mysql");
        assertThat(row.get("virtual_service")).isEqualTo(1);
    }

    @Test
    void doesNotClassifyApplicationServiceFromDbClientSpanAttributes() {
        String meta = """
                {"db.system":"mysql","db.name":"demo_apm","server.address":"mysql"}
                """;
        DcSpan span = new DcSpan();
        span.serviceId = "service-h-id";
        span.service = "service-h";
        span.meta = meta;

        var row = MetaServiceInfo.fromDcSpan(span).toRow("2026-06-05 10:00:00");
        assertThat(row.get("service_type")).isEqualTo("web");
        assertThat(row.get("type")).isEqualTo("web");
    }

    @Test
    void doesNotClassifyApplicationServiceFromMqSpanAttributes() {
        String meta = """
                {"messaging.system":"kafka","messaging.destination.name":"orders","server.address":"kafka"}
                """;
        DcSpan span = new DcSpan();
        span.serviceId = "service-j-id";
        span.service = "service-j";
        span.meta = meta;

        var row = MetaServiceInfo.fromDcSpan(span).toRow("2026-06-05 10:00:00");
        assertThat(row.get("service_type")).isEqualTo("web");
        assertThat(row.get("type")).isEqualTo("web");
    }

    @Test
    void classifiesMiddlewareAttributesOnlyWhenVirtualFlagSet() {
        var attrs = java.util.Map.of("messaging.system", "kafka");

        var appRow = MetaServiceInfo.fromNames(
                "service-j-id", "service-j", "service-j", attrs, false).toRow("2026-06-05 10:00:00");
        assertThat(appRow.get("service_type")).isEqualTo("web");

        var virtualRow = MetaServiceInfo.fromNames(
                "kafka-topic-id", "[kafka]orders", "[kafka]orders", attrs, true).toRow("2026-06-05 10:00:00");
        assertThat(virtualRow.get("service_type")).isEqualTo("mq");
        assertThat(virtualRow.get("virtual_service")).isEqualTo(1);
    }

    @Test
    void mergePrefersIncomingServiceTypeWhenClassificationChanges() {
        MetaServiceInfo legacyCustom = MetaServiceInfo.fromPoint(new com.databuff.apm.common.query.ApmQueryModels.MetaServicePoint(
                "gw-id", "payment-gateway", "payment-gateway", "custom", null, "custom",
                "custom", null, "OTLP", null, null, null, Boolean.FALSE, null, null, null, null));
        MetaServiceInfo latestWeb = MetaServiceInfo.fromNames(
                "gw-id", "payment-gateway", "payment-gateway", java.util.Map.of(), false);

        MetaServiceInfo merged = legacyCustom.merge(latestWeb);
        var row = merged.toRow("2026-06-05 10:00:00");
        assertThat(row.get("service_type")).isEqualTo("web");
        assertThat(row.get("type")).isEqualTo("web");
        assertThat(row.get("virtual_service")).isEqualTo(0);
        assertThat(legacyCustom.enrichmentDiffers(merged)).isTrue();
    }

    @Test
    void mergeKeepsVirtualMiddlewareTypeWhenMetricPathEmitsWeb() {
        MetaServiceInfo virtual = MetaServiceInfo.fromVirtualService(
                "redis-peer-id", "[redis]10.0.0.9:6379", "cache", "redis");
        MetaServiceInfo fromMetric = MetaServiceInfo.fromMetric(
                "redis-peer-id", "[redis]10.0.0.9:6379", "{}");

        MetaServiceInfo merged = virtual.merge(fromMetric);
        var row = merged.toRow("2026-06-05 10:00:00");
        assertThat(row.get("service_type")).isEqualTo("cache");
        assertThat(row.get("type")).isEqualTo("redis");
        assertThat(row.get("virtual_service")).isEqualTo(1);
    }

    @Test
    void mergeKeepsVirtualMiddlewareTypeWhenLegacyAppCustomArrives() {
        MetaServiceInfo virtual = MetaServiceInfo.fromVirtualService(
                "mysql-peer-id", "[mysql]orders", "db", "mysql");
        MetaServiceInfo legacyCustom = MetaServiceInfo.fromPoint(new com.databuff.apm.common.query.ApmQueryModels.MetaServicePoint(
                "mysql-peer-id", "[mysql]orders", "[mysql]orders", "custom", null, "custom",
                "custom", null, "OTLP", null, null, null, Boolean.FALSE, null, null, null, null));

        MetaServiceInfo merged = virtual.merge(legacyCustom);
        assertThat(merged.toRow("2026-06-05 10:00:00").get("service_type")).isEqualTo("db");
        assertThat(merged.toRow("2026-06-05 10:00:00").get("virtual_service")).isEqualTo(1);
    }

    @Test
    void mergeKeepsExistingTypeWhenIncomingClassificationBlank() {
        MetaServiceInfo existing = MetaServiceInfo.fromNames(
                "svc-id", "demo-order", "demo-order", java.util.Map.of(), false);
        MetaServiceInfo blankType = MetaServiceInfo.fromPoint(new com.databuff.apm.common.query.ApmQueryModels.MetaServicePoint(
                "svc-id", "demo-order", "demo-order", null, null, null,
                null, null, null, null, null, null, false, null, null, null, null));

        MetaServiceInfo merged = existing.merge(blankType);
        assertThat(merged.toRow("2026-06-05 10:00:00").get("service_type")).isEqualTo("web");
    }
}
