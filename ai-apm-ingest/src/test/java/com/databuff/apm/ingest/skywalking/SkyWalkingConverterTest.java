package com.databuff.apm.ingest.skywalking;

import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.common.meta.SpanDirectionUtil;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.serde.DcSpanUtil;
import com.databuff.apm.ingest.otel.OtlLogLine;
import com.databuff.apm.ingest.otel.OtlMetricLine;
import com.databuff.apm.ingest.trace.remote.TraceDataSources;
import org.apache.skywalking.apm.network.common.v3.CPU;
import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair;
import org.apache.skywalking.apm.network.language.agent.v3.Class;
import org.apache.skywalking.apm.network.language.agent.v3.GC;
import org.apache.skywalking.apm.network.language.agent.v3.GCPhase;
import org.apache.skywalking.apm.network.language.agent.v3.JVMMetric;
import org.apache.skywalking.apm.network.language.agent.v3.JVMMetricCollection;
import org.apache.skywalking.apm.network.language.agent.v3.Memory;
import org.apache.skywalking.apm.network.language.agent.v3.RefType;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentReference;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanType;
import org.apache.skywalking.apm.network.language.agent.v3.SpanLayer;
import org.apache.skywalking.apm.network.language.agent.v3.Thread;
import org.apache.skywalking.apm.network.logging.v3.LogData;
import org.apache.skywalking.apm.network.logging.v3.LogDataBody;
import org.apache.skywalking.apm.network.logging.v3.TextLog;
import org.apache.skywalking.apm.network.logging.v3.TraceContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SkyWalkingConverterTest {

    private final SkyWalkingConverter converter = new SkyWalkingConverter();
    private final SkyWalkingJvmConverter jvmConverter = new SkyWalkingJvmConverter();
    private final SkyWalkingLogConverter logConverter = new SkyWalkingLogConverter();

    @Test
    void convertsEntrySpanWithHttpTags() {
        SegmentObject segment = SegmentObject.newBuilder()
                .setTraceId("trace-abc")
                .setTraceSegmentId("segment-1")
                .setService("order-service")
                .setServiceInstance("inst-1")
                .addSpans(SpanObject.newBuilder()
                        .setSpanId(0)
                        .setParentSpanId(-1)
                        .setStartTime(1_700_000_000_000L)
                        .setEndTime(1_700_000_000_100L)
                        .setOperationName("/orders")
                        .setSpanType(SpanType.Entry)
                        .addTags(tag("http.method", "GET"))
                        .addTags(tag("url", "http://example/orders"))
                        .addTags(tag("status_code", "200")))
                .build();

        DcSpan span = converter.convertSegment(segment).get(0).span();
        assertThat(span.service).isEqualTo("order-service");
        assertThat(span.type).isEqualTo("SPAN_KIND_SERVER");
        assertThat(span.trace_id).isEqualTo("trace-abc");
        assertThat(span.metaHttpMethod).isEqualTo("GET");
        assertThat(span.metaHttpUrl).isEqualTo("/orders");
        assertThat(span.metaHttpStatusCode).isEqualTo(200);
        assertThat(TraceDataSources.resolve(OtelAttributeMaps.parse(span))).isEqualTo(TraceDataSources.SKY_WALKING);
        assertThat(SpanDirectionUtil.resolve(span).isIn()).isEqualTo(1);
    }

    @Test
    void splitsPeerHostPortIntoOtelMeta() {
        SegmentObject segment = SegmentObject.newBuilder()
                .setTraceId("trace-abc")
                .setTraceSegmentId("segment-1")
                .setService("order-service")
                .addSpans(SpanObject.newBuilder()
                        .setSpanId(0)
                        .setParentSpanId(-1)
                        .setStartTime(1_700_000_000_000L)
                        .setEndTime(1_700_000_000_050L)
                        .setOperationName("HTTP GET payments.example.com /api/risk/check")
                        .setSpanType(SpanType.Exit)
                        .setPeer("payments.example.com:443")
                        .addTags(tag("http.method", "GET"))
                        .addTags(tag("url", "https://payments.example.com/api/risk/check"))
                        .addTags(tag("status_code", "200")))
                .build();

        DcSpan span = converter.convertSegment(segment).get(0).span();
        assertThat(span.metaPeerHostname).isEqualTo("payments.example.com");
        Map<String, String> meta = OtelAttributeMaps.parse(span);
        assertThat(meta.get("server.address")).isEqualTo("payments.example.com");
        assertThat(meta.get("server.port")).isEqualTo("443");
    }

    @Test
    void convertsNativeDubboSpanAsRpcNotHttp() {
        String dubboUrl = "dubbo://service-b:20880/com.demo.OrderService.findInventory(String)";
        SegmentObject segment = SegmentObject.newBuilder()
                .setTraceId("trace-abc")
                .setTraceSegmentId("segment-1")
                .setService("order-service")
                .setServiceInstance("inst-1")
                .addSpans(SpanObject.newBuilder()
                        .setSpanId(0)
                        .setParentSpanId(-1)
                        .setStartTime(1_700_000_000_000L)
                        .setEndTime(1_700_000_000_080L)
                        .setOperationName("com.demo.OrderService.findInventory(String)")
                        .setSpanType(SpanType.Exit)
                        .setSpanLayer(SpanLayer.RPCFramework)
                        .setComponentId(3)
                        .setPeer("service-b:20880")
                        .addTags(tag("url", dubboUrl)))
                .build();

        DcSpan span = converter.convertSegment(segment).get(0).span();
        assertThat(span.metaHttpUrl).isNull();
        assertThat(span.metaHttpMethod).isNull();
        assertThat(DcSpanUtil.isRpcSpan(span)).isTrue();
        assertThat(DcSpanUtil.resolvePortalSpanDisplay(span))
                .isEqualTo(new DcSpanUtil.PortalSpanDisplay("custom", "dubbo"));

        Map<String, String> meta = OtelAttributeMaps.meta(span);
        assertThat(meta.get("rpc.system")).isEqualTo("dubbo");
        assertThat(meta.get("skywalking.componentId")).isEqualTo("3");
        assertThat(meta.get("skywalking.spanLayer")).isEqualTo("RPCFramework");
        assertThat(meta.get("url")).isEqualTo(dubboUrl);
    }

    @Test
    void convertsNativeGrpcSpanAsRpc() {
        SegmentObject segment = SegmentObject.newBuilder()
                .setTraceId("trace-abc")
                .setTraceSegmentId("segment-1")
                .setService("order-service")
                .addSpans(SpanObject.newBuilder()
                        .setSpanId(0)
                        .setParentSpanId(-1)
                        .setStartTime(1_700_000_000_000L)
                        .setEndTime(1_700_000_000_050L)
                        .setOperationName("/com.demo.Greeter/SayHello")
                        .setSpanType(SpanType.Exit)
                        .setSpanLayer(SpanLayer.RPCFramework)
                        .setComponentId(23)
                        .setPeer("service-b:9090")
                        .addTags(tag("status.code", "0")))
                .build();

        DcSpan span = converter.convertSegment(segment).get(0).span();
        assertThat(span.metaHttpUrl).isNull();
        assertThat(DcSpanUtil.isRpcSpan(span)).isTrue();
        assertThat(OtelAttributeMaps.meta(span).get("rpc.system")).isEqualTo("grpc");
    }

    @Test
    void convertsCrossProcessParentId() {
        SegmentObject segment = SegmentObject.newBuilder()
                .setTraceId("trace-abc")
                .setTraceSegmentId("segment-child")
                .setService("downstream")
                .addSpans(SpanObject.newBuilder()
                        .setSpanId(0)
                        .setParentSpanId(-1)
                        .setStartTime(1_700_000_000_000L)
                        .setEndTime(1_700_000_000_050L)
                        .setOperationName("entry")
                        .setSpanType(SpanType.Entry)
                        .addRefs(SegmentReference.newBuilder()
                                .setRefType(RefType.CrossProcess)
                                .setParentTraceSegmentId("segment-parent")
                                .setParentSpanId(2)))
                .build();

        DcSpan span = converter.convertSegment(segment).get(0).span();
        assertThat(span.parent_id).isEqualTo(SkyWalkingIdUtil.spanId("segment-parent", 2));
        assertThat(span.is_parent).isZero();
    }

    @Test
    void marksSegmentRootWithoutCrossProcessRefAsParent() {
        SegmentObject segment = SegmentObject.newBuilder()
                .setTraceId("trace-abc")
                .setTraceSegmentId("segment-root")
                .setService("entry-service")
                .addSpans(SpanObject.newBuilder()
                        .setSpanId(0)
                        .setParentSpanId(-1)
                        .setStartTime(1_700_000_000_000L)
                        .setEndTime(1_700_000_000_050L)
                        .setOperationName("/entry")
                        .setSpanType(SpanType.Entry))
                .build();

        DcSpan span = converter.convertSegment(segment).get(0).span();
        assertThat(span.parent_id).isEmpty();
        assertThat(span.is_parent).isEqualTo(1);
    }

    @Test
    void convertsJvmMetrics() {
        JVMMetricCollection collection = JVMMetricCollection.newBuilder()
                .setService("order-service")
                .setServiceInstance("inst-1")
                .addMetrics(JVMMetric.newBuilder()
                        .setTime(1_700_000_000_000L)
                        .setCpu(CPU.newBuilder().setUsagePercent(42.0))
                        .addMemory(Memory.newBuilder().setIsHeap(true).setUsed(100).setCommitted(200))
                        .addGc(GC.newBuilder().setPhase(GCPhase.NEW).setCount(3).setTime(120))
                        .setThread(Thread.newBuilder().setLiveCount(25))
                        .setClazz(Class.newBuilder().setLoadedClassCount(999)))
                .build();

        List<OtlMetricLine> lines = jvmConverter.convert(collection).stream()
                .map(SkyWalkingJvmConverter.ConvertedMetric::line)
                .toList();
        assertThat(lines).extracting(OtlMetricLine::metric)
                .contains("jvm.cpu_load_process", "jvm.memory.heap.used", "jvm.gc.minor_collection_count",
                        "jvm.thread_count", "jvm.loaded_classes.count");
        assertThat(lines.stream().filter(l -> "jvm.cpu_load_process".equals(l.metric())).findFirst().orElseThrow().value())
                .isEqualTo(0.42);
    }

    @Test
    void convertsLogWithTraceContext() {
        LogData logData = LogData.newBuilder()
                .setTimestamp(1_700_000_000_000L)
                .setService("order-service")
                .setServiceInstance("inst-1")
                .setBody(LogDataBody.newBuilder().setText(TextLog.newBuilder().setText("hello")))
                .setTraceContext(TraceContext.newBuilder()
                        .setTraceId("trace-abc")
                        .setTraceSegmentId("segment-1")
                        .setSpanId(1))
                .build();

        OtlLogLine line = logConverter.convert(logData).line();
        assertThat(line.body()).isEqualTo("hello");
        assertThat(line.traceId()).isEqualTo("trace-abc");
        assertThat(line.spanId()).isEqualTo(SkyWalkingIdUtil.spanId("segment-1", 1));
    }

    private static KeyStringValuePair tag(String key, String value) {
        return KeyStringValuePair.newBuilder().setKey(key).setValue(value).build();
    }
}
