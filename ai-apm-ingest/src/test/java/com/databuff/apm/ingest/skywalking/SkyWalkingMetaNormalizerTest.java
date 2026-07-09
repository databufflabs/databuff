package com.databuff.apm.ingest.skywalking;

import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.common.meta.VirtualServiceResolver;
import com.databuff.apm.common.model.DcSpan;
import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanLayer;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SkyWalkingMetaNormalizerTest {

    private final SkyWalkingConverter converter = new SkyWalkingConverter();

    @Test
    void mapsGenericSqlDbTypeToMysqlFromComponentId() {
        DcSpan span = convertDbSpan(33, "sql", "mysql.test:3306", tag("db.statement", "SELECT 1"));
        Map<String, String> meta = OtelAttributeMaps.parse(span);

        assertThat(meta.get("db.system")).isEqualTo("mysql");
        assertThat(meta.get("db.type")).isEqualTo("mysql");
    }

    @Test
    void mapsGenericSqlDbTypeToMysqlFromPeerHost() {
        DcSpan span = convertDbSpan(0, "sql", "mysql.test:3306", tag("db.statement", "SELECT 1"));
        Map<String, String> meta = OtelAttributeMaps.parse(span);

        assertThat(meta.get("db.system")).isEqualTo("mysql");
        assertThat(meta.get("db.type")).isEqualTo("mysql");
    }

    @Test
    void mapsGenericSqlDbTypeToMysqlFromPeerPort() {
        DcSpan span = convertDbSpan(0, "sql", "db.internal:3306", tag("db.statement", "SELECT 1"));
        Map<String, String> meta = OtelAttributeMaps.parse(span);

        assertThat(meta.get("db.system")).isEqualTo("mysql");
    }

    @Test
    void keepsExplicitMysqlDbType() {
        DcSpan span = convertDbSpan(33, "mysql", "mysql.test:3306", tag("db.statement", "SELECT 1"));
        Map<String, String> meta = OtelAttributeMaps.parse(span);

        assertThat(meta.get("db.system")).isEqualTo("mysql");
        assertThat(meta.get("db.type")).isEqualTo("mysql");
    }

    @Test
    void mapsDbInstanceToDbName() {
        DcSpan span = convertDbSpan(
                33,
                "sql",
                "mysql.test:3306",
                tag("db.instance", "orders"),
                tag("db.statement", "SELECT 1"));
        Map<String, String> meta = OtelAttributeMaps.parse(span);

        assertThat(meta.get("db.name")).isEqualTo("orders");
        assertThat(meta.get("db.system")).isEqualTo("mysql");
    }

    @Test
    void normalizedDbSpanProducesMysqlVirtualService() {
        DcSpan span = convertDbSpan(33, "sql", "mysql.test:3306", tag("db.statement", "SELECT 1"));
        span.type = "SPAN_KIND_CLIENT";
        span.isOut = 1;

        VirtualServiceResolver.ResolvedVirtualService resolved = VirtualServiceResolver.resolve(span);
        assertThat(resolved).isNotNull();
        assertThat(resolved.service()).isEqualTo("[mysql]mysql.test:3306");
        assertThat(resolved.typeIcon()).isEqualTo("mysql");
    }

    @Test
    void mapsPostgresqlComponentId() {
        assertThat(SkyWalkingMetaNormalizer.dbSystemFromSkyWalkingComponentId(22)).isEqualTo("postgresql");
        assertThat(SkyWalkingMetaNormalizer.dbSystemFromSkyWalkingComponentId(37)).isEqualTo("postgresql");
    }

    @Test
    void normalizesRawSqlStatementByDefault() {
        String rawSql = "SELECT id, amount FROM demo_order WHERE id = 10001 AND apiKey = HW274HYFH2492H";
        DcSpan span = convertDbSpan(33, "sql", "mysql.test:3306", tag("db.statement", rawSql));
        Map<String, String> meta = OtelAttributeMaps.parse(span);

        assertThat(meta.get("db.statement"))
                .isEqualTo("SELECT id, amount FROM demo_order WHERE id = ? AND apiKey = ?");
        assertThat(meta.get("normalized.resource"))
                .isEqualTo("SELECT id, amount FROM demo_order WHERE id = ? AND apiKey = ?");
        assertThat(span.resource)
                .isEqualTo("SELECT id, amount FROM demo_order WHERE id = ? AND apiKey = ?");
    }

    @Test
    void keepsAlreadyParameterizedSql() {
        String sql = "SELECT id FROM demo_order WHERE id = ?";
        DcSpan span = convertDbSpan(33, "mysql", "mysql.test:3306", tag("db.statement", sql));
        Map<String, String> meta = OtelAttributeMaps.parse(span);

        assertThat(meta.get("db.statement")).isEqualTo(sql);
        assertThat(span.resource).isEqualTo(sql);
    }
    private DcSpan convertDbSpan(int componentId, String dbType, String peer, KeyStringValuePair... extraTags) {
        SpanObject.Builder spanBuilder = SpanObject.newBuilder()
                .setSpanId(0)
                .setParentSpanId(-1)
                .setStartTime(1_700_000_000_000L)
                .setEndTime(1_700_000_000_050L)
                .setOperationName("SELECT demo_order")
                .setSpanType(SpanType.Exit)
                .setSpanLayer(SpanLayer.Database)
                .setPeer(peer)
                .addTags(tag("db.type", dbType));
        for (KeyStringValuePair extraTag : extraTags) {
            spanBuilder.addTags(extraTag);
        }
        if (componentId > 0) {
            spanBuilder.setComponentId(componentId);
        }
        SegmentObject segment = SegmentObject.newBuilder()
                .setTraceId("trace-abc")
                .setTraceSegmentId("segment-1")
                .setService("skyWalking-service-g")
                .addSpans(spanBuilder)
                .build();
        return converter.convertSegment(segment).get(0).span();
    }

    private static KeyStringValuePair tag(String key, String value) {
        return KeyStringValuePair.newBuilder().setKey(key).setValue(value).build();
    }
}
