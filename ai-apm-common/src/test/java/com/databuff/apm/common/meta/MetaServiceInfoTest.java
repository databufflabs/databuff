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
    void classifiesDatabaseSpanAttributes() {
        String meta = """
                {"db.system":"mysql","host.name":"db-host"}
                """;
        DcSpan span = new DcSpan();
        span.serviceId = "mysql-id";
        span.service = "[mysql]dc_databuff";
        span.meta = meta;

        var row = MetaServiceInfo.fromDcSpan(span).toRow("2026-06-05 10:00:00");
        assertThat(row.get("service_type")).isEqualTo("db");
        assertThat(row.get("type")).isEqualTo("mysql");
        assertThat(row.get("technology")).isEqualTo("mysql");
    }

    @Test
    void classifiesElasticsearchSpanAttributesAsDatabase() {
        String meta = """
                {"db.system":"elasticsearch","db.elasticsearch.index":"orders","host.name":"es"}
                """;
        DcSpan span = new DcSpan();
        span.serviceId = "es-id";
        span.service = "[elasticsearch]es:9200";
        span.meta = meta;

        var row = MetaServiceInfo.fromDcSpan(span).toRow("2026-06-05 10:00:00");
        assertThat(row.get("service_type")).isEqualTo("db");
        assertThat(row.get("type")).isEqualTo("elasticsearch");
        assertThat(row.get("technology")).isEqualTo("elasticsearch");
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
