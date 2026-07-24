package com.databuff.apm.ingest.metric;

import com.databuff.apm.common.storage.DorisJsonRow;

import com.databuff.apm.common.storage.DorisBatchWriter;
import com.databuff.apm.common.storage.DorisTableNames;
import com.databuff.apm.ingest.otel.OtlMetricLine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpMetricDirectWriterTest {

    @Test
    void mergesJvmRowsFromSameExportBeforeWriting() {
        DorisBatchWriter jvm = new DorisBatchWriter();
        MetricWriteRouter router = new MetricWriteRouter(Map.of(DorisTableNames.METRIC_JVM, jvm));
        OtlpMetricDirectWriter writer = new OtlpMetricDirectWriter(router);
        OtlMetricLine base = new OtlMetricLine(
                1_700_000_000_000L,
                "service-a",
                "service-a",
                "ignored",
                0,
                "service-a-1",
                "demo-host-a",
                null,
                null,
                null,
                null,
                null,
                null);

        writer.write(List.of(
                withMetric(base, "jvm.thread_count", 10),
                withMetric(base, "jvm.gc.major_collection_count", 2)));

        assertThat(jvm.pendingCount()).isEqualTo(1);
        String json = new String(DorisJsonRow.toByteArray(jvm.flushAll().get(0)));
        assertThat(json).contains("thread_count");
        assertThat(json).contains("gc_major_collection_count");
    }

    private static OtlMetricLine withMetric(OtlMetricLine template, String metric, Number value) {
        return new OtlMetricLine(
                template.tsMillis(),
                template.serviceId(),
                template.service(),
                metric,
                value,
                template.serviceInstance(),
                template.tagHost(),
                template.threadPoolName(),
                template.objectPoolName(),
                template.httpConnectionPoolName(),
                template.connectionPoolName(),
                template.poolName(),
                template.resourceMeta());
    }
}
