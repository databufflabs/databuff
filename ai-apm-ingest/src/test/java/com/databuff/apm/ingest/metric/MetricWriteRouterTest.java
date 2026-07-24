package com.databuff.apm.ingest.metric;

import com.databuff.apm.common.storage.DorisJsonRow;

import com.databuff.apm.common.model.OptimizedMetric;
import com.databuff.apm.common.storage.DorisBatchWriter;
import com.databuff.apm.common.storage.DorisTableNames;
import com.databuff.apm.ingest.otel.OtlMetricLine;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricWriteRouterTest {

    @Test
    void routesByMeasurementTableName() throws Exception {
        DorisBatchWriter service = new DorisBatchWriter();
        DorisBatchWriter trace = new DorisBatchWriter();
        DorisBatchWriter flow = new DorisBatchWriter();
        DorisBatchWriter http = new DorisBatchWriter();
        MetricWriteRouter router = new MetricWriteRouter(Map.of(
                DorisTableNames.METRIC_SERVICE, service,
                DorisTableNames.METRIC_SERVICE_TRACE, trace,
                DorisTableNames.METRIC_SERVICE_FLOW, flow,
                DorisTableNames.METRIC_SERVICE_HTTP, http));

        router.offer(new OptimizedMetric().withMeasurement("service").withFieldValues(1, 0, 1).initTsId());
        router.offer(new OptimizedMetric().withMeasurement("service.trace").withFieldValues(1, 0, 1).initTsId());
        router.offer(new OptimizedMetric().withMeasurement("service.flow").withFieldValues(1, 0, 1).initTsId());
        router.offer(new OptimizedMetric().withMeasurement("service.http").withFieldValues(1, 0, 1).initTsId());

        assertThat(service.pendingCount()).isEqualTo(1);
        assertThat(trace.pendingCount()).isEqualTo(1);
        assertThat(flow.pendingCount()).isEqualTo(1);
        assertThat(http.pendingCount()).isEqualTo(1);
    }

    @Test
    void routesJvmGcOtlpLine() {
        DorisBatchWriter jvm = new DorisBatchWriter();
        MetricWriteRouter router = new MetricWriteRouter(Map.of(
                DorisTableNames.METRIC_JVM, jvm));

        router.offerOtlp(new OtlMetricLine(
                1_700_000_000_000L,
                "service-a",
                "service-a",
                "jvm.gc.minor_collection_count",
                3,
                "service-a-1",
                "demo-host-a",
                null,
                null,
                null,
                null,
                null,
                null));

        assertThat(jvm.pendingCount()).isEqualTo(1);
        assertThat(new String(DorisJsonRow.toByteArray(jvm.flushAll().get(0)))).contains("gc_minor_collection_count");
    }

    @Test
    void routesRawOtlpMetricRows() {
        DorisBatchWriter service = new DorisBatchWriter();
        DorisBatchWriter jvm = new DorisBatchWriter();
        MetricWriteRouter router = new MetricWriteRouter(Map.of(
                DorisTableNames.METRIC_SERVICE, service,
                DorisTableNames.METRIC_JVM, jvm));

        byte[] raw = ("{\"ts\":1700000000000,\"service\":\"demo\",\"service_id\":\"demo\","
                + "\"metric\":\"jvm.thread_count\",\"value\":7}").getBytes();
        router.offerRaw(raw);

        assertThat(jvm.pendingCount()).isEqualTo(1);
        assertThat(service.pendingCount()).isZero();
    }

    @Test
    void fallsBackToServiceWriterForUnknownMeasurement() throws Exception {
        DorisBatchWriter service = new DorisBatchWriter();
        MetricWriteRouter router = MetricWriteRouter.singleTable(service);
        router.offer(new OptimizedMetric().withMeasurement("custom.metric").withFieldValues(1).initTsId());
        assertThat(service.pendingCount()).isEqualTo(1);
    }
}
