package com.databuff.apm.ingest.meta;

import com.databuff.apm.common.meta.MetaServiceInfo;
import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.common.meta.VirtualServiceResolver;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.serde.DcSpanUtil;
import com.databuff.apm.ingest.otel.OtelConverter;
import com.databuff.apm.ingest.skywalking.SkyWalkingConverter;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanLayer;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matrix coverage for service_type principles:
 * <ul>
 *   <li>Application catalog ({@code fromDcSpan}/{@code fromNames}) is always {@code web}
 *       — never from free-form service name keywords.</li>
 *   <li>Virtual middleware type comes from span component recognition
 *       ({@link VirtualServiceResolver} ← {@link DcSpanUtil#resolvePortalSpanDisplay}).</li>
 *   <li>SkyWalking: {@code db.system}/{@code messaging.system}/{@code rpc.system} from
 *       componentId (and SpanType for MQ op), never host/port/operationName guessing.</li>
 * </ul>
 */
class ServiceTypeClassificationMatrixTest {

    private final OtelConverter otel = new OtelConverter();
    private final SkyWalkingConverter skyWalking = new SkyWalkingConverter();

    @Nested
    @DisplayName("Application catalog always web")
    class ApplicationCatalog {

        @ParameterizedTest(name = "app service name \"{0}\" stays web")
        @CsvSource({
                "java-redis-demo",
                "mysql-order-service",
                "kafka-gateway",
                "payment-gateway",
                "demo-order"
        })
        void fromDcSpanIgnoresNameKeywords(String serviceName) {
            DcSpan span = new DcSpan();
            span.serviceId = "app-" + serviceName.hashCode();
            span.service = serviceName;
            span.meta = "{\"telemetry.sdk.language\":\"java\"}";

            var row = MetaServiceInfo.fromDcSpan(span).toRow("2026-06-05 10:00:00");
            assertThat(row.get("service_type")).isEqualTo("web");
            assertThat(row.get("virtual_service")).isEqualTo(0);
        }

        @Test
        void fromDcSpanIgnoresOutboundDbAttributesOnApplicationSpan() {
            // Client attrs on an app span must not flip the app catalog row to db/cache.
            DcSpan span = new DcSpan();
            span.serviceId = "java-redis-demo-id";
            span.service = "java-redis-demo";
            span.meta = "{\"db.system\":\"redis\",\"server.address\":\"10.0.0.9\",\"server.port\":\"6379\"}";

            var row = MetaServiceInfo.fromDcSpan(span).toRow("2026-06-05 10:00:00");
            assertThat(row.get("service_type")).isEqualTo("web");
        }

        @Test
        void otelHttpServerSpanApplicationCatalogIsWeb() {
            DcSpan span = convertOtel(
                    "java-redis-demo",
                    Span.SpanKind.SPAN_KIND_SERVER,
                    kv("http.request.method", "GET"),
                    kv("url.path", "/api"));
            var row = MetaServiceInfo.fromDcSpan(span).toRow("2026-06-05 10:00:00");
            assertThat(row.get("service_type")).isEqualTo("web");
            assertThat(DcSpanUtil.resolvePortalServiceType(span)).isEqualTo("web");
        }
    }

    @Nested
    @DisplayName("OTel outbound → virtual service_type")
    class OtelVirtual {

        @Test
        void redisClientIsCache() {
            DcSpan span = convertOtelClient(
                    "checkout",
                    kv("db.system", "redis"),
                    kv("server.address", "10.0.0.9"),
                    kv("server.port", "6379"));
            assertVirtual(span, "cache", "redis");
        }

        @Test
        void memcachedClientIsCache() {
            DcSpan span = convertOtelClient(
                    "checkout",
                    kv("db.system", "memcached"),
                    kv("server.address", "10.0.0.10"),
                    kv("server.port", "11211"));
            assertVirtual(span, "cache", "memcached");
        }

        @Test
        void mysqlClientIsDb() {
            DcSpan span = convertOtelClient(
                    "checkout",
                    kv("db.system", "mysql"),
                    kv("db.name", "orders"),
                    kv("server.address", "10.0.0.8"),
                    kv("server.port", "3306"));
            assertVirtual(span, "db", "mysql");
        }

        @Test
        void postgresqlClientIsDb() {
            DcSpan span = convertOtelClient(
                    "checkout",
                    kv("db.system", "postgresql"),
                    kv("db.name", "orders"),
                    kv("server.address", "pg"),
                    kv("server.port", "5432"));
            VirtualServiceResolver.ResolvedVirtualService resolved = resolveOutbound(span);
            assertThat(resolved.serviceType()).isEqualTo("db");
            assertThat(resolved.typeIcon()).isEqualTo("postgresql");
        }

        @Test
        void elasticsearchClientIsDb() {
            DcSpan span = convertOtelClient(
                    "checkout",
                    kv("db.system", "elasticsearch"),
                    kv("server.address", "es"),
                    kv("server.port", "9200"));
            assertVirtual(span, "db", "elasticsearch");
        }

        @Test
        void kafkaProducerIsMq() {
            DcSpan span = convertOtelClient(
                    "checkout",
                    kv("messaging.system", "kafka"),
                    kv("messaging.destination.name", "orders"),
                    kv("messaging.operation", "publish"),
                    kv("net.peer.name", "broker-1"));
            assertVirtual(span, "mq", "kafka");
        }

        @Test
        void rabbitmqProducerIsMq() {
            DcSpan span = convertOtelClient(
                    "checkout",
                    kv("messaging.system", "rabbitmq"),
                    kv("messaging.destination.name", "jobs"),
                    kv("messaging.operation", "publish"),
                    kv("net.peer.name", "rmq"));
            VirtualServiceResolver.ResolvedVirtualService resolved = resolveOutbound(span);
            assertThat(resolved.serviceType()).isEqualTo("mq");
            assertThat(resolved.typeIcon()).isEqualTo("rabbitmq");
        }

        @Test
        void grpcClientPortalTypeIsCustomNotRemoteCatalogYet() {
            // VirtualServiceResolver does not build HTTP/RPC remotes; portal legend is custom.
            DcSpan span = convertOtelClient(
                    "checkout",
                    kv("rpc.system", "grpc"),
                    kv("rpc.service", "Greeter"),
                    kv("server.address", "svc-b"),
                    kv("server.port", "9090"));
            assertThat(VirtualServiceResolver.resolve(span)).isNull();
            assertThat(DcSpanUtil.resolvePortalServiceType(span)).isEqualTo("custom");
            assertThat(DcSpanUtil.resolvePortalSpanDisplay(span).typeIcon()).isEqualTo("grpc");
        }

        @Test
        void appNamedJavaRedisDemoCallingRedisStillYieldsCacheVirtualPeer() {
            DcSpan span = convertOtelClient(
                    "java-redis-demo",
                    kv("db.system", "redis"),
                    kv("server.address", "redis.internal"),
                    kv("server.port", "6379"));
            // App catalog stays web; virtual peer is cache.
            assertThat(MetaServiceInfo.fromDcSpan(span).toRow("t").get("service_type")).isEqualTo("web");
            assertVirtual(span, "cache", "redis");
        }
    }

    @Nested
    @DisplayName("SkyWalking componentId → system / virtual type")
    class SkyWalkingVirtual {

        @Test
        void mysqlConnectorComponentIdMapsGenericSqlToMysqlDb() {
            DcSpan span = convertSwDb(33, "sql", "db.internal:3306", tag("db.statement", "SELECT 1"));
            Map<String, String> meta = OtelAttributeMaps.parse(span);
            assertThat(meta.get("db.system")).isEqualTo("mysql");
            assertVirtual(span, "db", "mysql");
        }

        @Test
        void withoutComponentIdDoesNotGuessMysqlFromHostOrPort() {
            DcSpan span = convertSwDb(0, "sql", "mysql.test:3306", tag("db.statement", "SELECT 1"));
            Map<String, String> meta = OtelAttributeMaps.parse(span);
            assertThat(meta.get("db.system")).isEqualTo("sql");
            // Still a DB span (layer+statement) but system stays generic.
            VirtualServiceResolver.ResolvedVirtualService resolved = resolveOutbound(span);
            assertThat(resolved.serviceType()).isEqualTo("db");
        }

        @Test
        void redisJedisComponentIdIsCache() {
            DcSpan span = convertSw(
                    SpanLayer.Cache,
                    SpanType.Exit,
                    7,
                    "Jedis/GET",
                    "10.0.0.9:6379",
                    tag("db.type", "Redis"));
            Map<String, String> meta = OtelAttributeMaps.parse(span);
            assertThat(meta.get("db.system")).isEqualTo("redis");
            assertVirtual(span, "cache", "redis");
        }

        @Test
        void memcachedComponentIdIsCache() {
            DcSpan span = convertSw(
                    SpanLayer.Cache,
                    SpanType.Exit,
                    20,
                    "Xmemcached/get",
                    "10.0.0.10:11211",
                    tag("db.type", "Memcached"));
            assertVirtual(span, "cache", "memcached");
        }

        @Test
        void postgresqlComponentIdIsDb() {
            DcSpan span = convertSwDb(37, "sql", "pg:5432", tag("db.statement", "SELECT 1"));
            Map<String, String> meta = OtelAttributeMaps.parse(span);
            assertThat(meta.get("db.system")).isEqualTo("postgresql");
            VirtualServiceResolver.ResolvedVirtualService resolved = resolveOutbound(span);
            assertThat(resolved.serviceType()).isEqualTo("db");
            assertThat(resolved.typeIcon()).isEqualTo("postgresql");
        }

        @Test
        void kafkaProducerComponentIdIsMqWithPublishFromSpanType() {
            DcSpan span = convertSw(
                    SpanLayer.MQ,
                    SpanType.Exit,
                    40,
                    "Kafka/orders/Producer",
                    "kafka:9092",
                    tag("mq.topic", "orders"),
                    tag("mq.broker", "kafka:9092"));
            Map<String, String> meta = OtelAttributeMaps.parse(span);
            assertThat(meta.get("messaging.system")).isEqualTo("kafka");
            assertThat(meta.get("messaging.operation")).isEqualTo("publish");
            assertVirtual(span, "mq", "kafka");
        }

        @Test
        void kafkaConsumerComponentIdIsMqWithProcessFromSpanType() {
            DcSpan span = convertSw(
                    SpanLayer.MQ,
                    SpanType.Entry,
                    41,
                    "Kafka/orders/Consumer/group",
                    "kafka:9092",
                    tag("mq.topic", "orders"),
                    tag("mq.broker", "kafka:9092"));
            Map<String, String> meta = OtelAttributeMaps.parse(span);
            assertThat(meta.get("messaging.system")).isEqualTo("kafka");
            assertThat(meta.get("messaging.operation")).isEqualTo("process");
            // Entry consumer is not isOut virtual extract target.
            assertThat(VirtualServiceResolver.resolve(span)).isNull();
        }

        @Test
        void mqSystemDoesNotComeFromOperationNameWithoutComponentId() {
            DcSpan span = convertSw(
                    SpanLayer.MQ,
                    SpanType.Exit,
                    0,
                    "Kafka/orders/Producer",
                    "kafka:9092",
                    tag("mq.topic", "orders"),
                    tag("mq.broker", "kafka:9092"));
            Map<String, String> meta = OtelAttributeMaps.parse(span);
            assertThat(meta.get("messaging.system")).isNull();
            assertThat(meta.get("messaging.operation")).isEqualTo("publish");
            assertThat(VirtualServiceResolver.resolve(span)).isNull();
        }

        @Test
        void dubboComponentIdSetsRpcSystemWithoutOperationNameGuess() {
            DcSpan span = convertSw(
                    SpanLayer.RPCFramework,
                    SpanType.Exit,
                    3,
                    "com.demo.OrderService.find",
                    "service-b:20880",
                    tag("url", "dubbo://service-b:20880/com.demo.OrderService.find"));
            Map<String, String> meta = OtelAttributeMaps.parse(span);
            assertThat(meta.get("rpc.system")).isEqualTo("dubbo");
            assertThat(DcSpanUtil.resolvePortalServiceType(span)).isEqualTo("custom");
            assertThat(VirtualServiceResolver.resolve(span)).isNull();
        }

        @Test
        void grpcComponentIdSetsRpcSystem() {
            DcSpan span = convertSw(
                    SpanLayer.RPCFramework,
                    SpanType.Exit,
                    23,
                    "/com.demo.Greeter/SayHello",
                    "service-b:9090");
            Map<String, String> meta = OtelAttributeMaps.parse(span);
            assertThat(meta.get("rpc.system")).isEqualTo("grpc");
            assertThat(DcSpanUtil.resolvePortalServiceType(span)).isEqualTo("custom");
        }

        @Test
        void skyWalkingAppServiceCatalogIsWebEvenWhenServiceNameContainsRedis() {
            SegmentObject segment = SegmentObject.newBuilder()
                    .setTraceId("trace-app")
                    .setTraceSegmentId("segment-app")
                    .setService("java-redis-demo")
                    .addSpans(SpanObject.newBuilder()
                            .setSpanId(0)
                            .setParentSpanId(-1)
                            .setStartTime(1_700_000_000_000L)
                            .setEndTime(1_700_000_000_050L)
                            .setOperationName("GET:/api")
                            .setSpanType(SpanType.Entry)
                            .setSpanLayer(SpanLayer.Http)
                            .setComponentId(1))
                    .build();
            DcSpan span = skyWalking.convertSegment(segment).get(0).span();
            var row = MetaServiceInfo.fromDcSpan(span).toRow("2026-06-05 10:00:00");
            assertThat(row.get("service_type")).isEqualTo("web");
        }

        @Test
        void fromVirtualServicePersistsResolvedType() {
            DcSpan span = convertSwDb(33, "sql", "10.0.0.8:3306",
                    tag("db.statement", "SELECT 1"),
                    tag("db.instance", "orders"));
            VirtualServiceResolver.ResolvedVirtualService resolved = resolveOutbound(span);
            var row = MetaServiceInfo.fromVirtualService(
                    resolved.serviceId(),
                    resolved.service(),
                    resolved.serviceType(),
                    resolved.typeIcon()).toRow("t");
            assertThat(row.get("service_type")).isEqualTo("db");
            assertThat(row.get("type")).isEqualTo("mysql");
            assertThat(row.get("virtual_service")).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Merge / portal display consistency")
    class MergeAndDisplay {

        @Test
        void metricWebDoesNotOverwriteVirtualCache() {
            MetaServiceInfo virtual = MetaServiceInfo.fromVirtualService(
                    "r-id", "[redis]10.0.0.9:6379", "cache", "redis");
            MetaServiceInfo fromMetric = MetaServiceInfo.fromMetric("r-id", "[redis]10.0.0.9:6379", "{}");
            assertThat(virtual.merge(fromMetric).toRow("t").get("service_type")).isEqualTo("cache");
        }

        @Test
        void portalDisplayForOtelRedisIsCache() {
            DcSpan span = convertOtelClient(
                    "checkout",
                    kv("db.system", "redis"),
                    kv("server.address", "r1"));
            assertThat(DcSpanUtil.resolvePortalSpanDisplay(span))
                    .isEqualTo(new DcSpanUtil.PortalSpanDisplay("cache", "redis"));
        }
    }

    private static void assertVirtual(DcSpan span, String serviceType, String typeIcon) {
        VirtualServiceResolver.ResolvedVirtualService resolved = resolveOutbound(span);
        assertThat(resolved).isNotNull();
        assertThat(resolved.serviceType()).isEqualTo(serviceType);
        assertThat(resolved.typeIcon()).isEqualTo(typeIcon);
        assertThat(DcSpanUtil.resolvePortalServiceType(span)).isEqualTo(serviceType);
    }

    private static VirtualServiceResolver.ResolvedVirtualService resolveOutbound(DcSpan span) {
        span.isOut = 1;
        if (span.type == null || span.type.isBlank()) {
            span.type = "SPAN_KIND_CLIENT";
        }
        return VirtualServiceResolver.resolve(span);
    }

    private DcSpan convertOtelClient(String service, KeyValue... attrs) {
        return convertOtel(service, Span.SpanKind.SPAN_KIND_CLIENT, attrs);
    }

    private DcSpan convertOtel(String service, Span.SpanKind kind, KeyValue... attrs) {
        Span.Builder spanBuilder = Span.newBuilder()
                .setTraceId(ByteString.fromHex("0102030405060708090a0b0c0d0e0f10"))
                .setSpanId(ByteString.fromHex("0102030405060708"))
                .setName("op")
                .setKind(kind)
                .setStartTimeUnixNano(1_700_000_000_000_000_000L)
                .setEndTimeUnixNano(1_700_000_000_050_000_000L);
        for (KeyValue attr : attrs) {
            spanBuilder.addAttributes(attr);
        }
        ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder().addAttributes(kv("service.name", service)))
                        .addScopeSpans(ScopeSpans.newBuilder().addSpans(spanBuilder)))
                .build();
        return otel.convertTraces(request).get(0).span();
    }

    private DcSpan convertSwDb(int componentId, String dbType, String peer, KeyStringValuePair... tags) {
        return convertSw(SpanLayer.Database, SpanType.Exit, componentId, "SELECT", peer,
                prepend(tag("db.type", dbType), tags));
    }

    private DcSpan convertSw(
            SpanLayer layer,
            SpanType spanType,
            int componentId,
            String operationName,
            String peer,
            KeyStringValuePair... tags) {
        SpanObject.Builder spanBuilder = SpanObject.newBuilder()
                .setSpanId(0)
                .setParentSpanId(-1)
                .setStartTime(1_700_000_000_000L)
                .setEndTime(1_700_000_000_050L)
                .setOperationName(operationName)
                .setSpanType(spanType)
                .setSpanLayer(layer);
        if (componentId > 0) {
            spanBuilder.setComponentId(componentId);
        }
        if (peer != null && !peer.isBlank()) {
            spanBuilder.setPeer(peer);
        }
        for (KeyStringValuePair t : tags) {
            spanBuilder.addTags(t);
        }
        SegmentObject segment = SegmentObject.newBuilder()
                .setTraceId("trace-matrix")
                .setTraceSegmentId("segment-matrix")
                .setService("sw-app")
                .addSpans(spanBuilder)
                .build();
        return skyWalking.convertSegment(segment).get(0).span();
    }

    private static KeyStringValuePair[] prepend(KeyStringValuePair head, KeyStringValuePair[] rest) {
        KeyStringValuePair[] out = new KeyStringValuePair[rest.length + 1];
        out[0] = head;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    private static KeyValue kv(String key, String value) {
        return KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value))
                .build();
    }

    private static KeyStringValuePair tag(String key, String value) {
        return KeyStringValuePair.newBuilder().setKey(key).setValue(value).build();
    }
}
