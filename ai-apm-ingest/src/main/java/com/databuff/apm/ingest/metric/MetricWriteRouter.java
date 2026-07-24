package com.databuff.apm.ingest.metric;

import com.databuff.apm.common.model.OptimizedMetric;
import com.databuff.apm.common.serde.MetricDorisJsonRow;
import com.databuff.apm.common.storage.DorisBatchWriter;
import com.databuff.apm.common.storage.DorisJsonRow;
import com.databuff.apm.common.storage.DorisTableNames;
import com.databuff.apm.common.storage.MetricIdentifierParser;
import com.databuff.apm.ingest.otel.OtlMetricLine;
import com.databuff.apm.ingest.otel.OtlpMetricDebugLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Routes optimized metrics to Doris batch writers by measurement → table name. */
public final class MetricWriteRouter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, DorisBatchWriter> writersByTable;
    private final DorisBatchWriter defaultWriter;

    public MetricWriteRouter(Map<String, DorisBatchWriter> writersByTable) {
        this.writersByTable = Map.copyOf(writersByTable);
        this.defaultWriter = writersByTable.getOrDefault(
                DorisTableNames.METRIC_SERVICE, writersByTable.values().iterator().next());
    }

    public void offer(OptimizedMetric metric) {
        Objects.requireNonNull(metric, "metric");
        String table = MetricIdentifierParser.dorisTableName(metric.measurement());
        DorisBatchWriter writer = writersByTable.getOrDefault(table, defaultWriter);
        writer.offer(MetricDorisJsonRow.of(metric));
    }

    public void offerOtlp(OtlMetricLine line) {
        Optional<OtlpMetricRowMapper.MappedRow> mapped = OtlpMetricRowMapper.map(line);
        if (mapped.isEmpty()) {
            OtlpMetricDebugLogger.mapSkipped(line, "no mapped row");
            return;
        }
        offerMappedRow(mapped.get());
    }

    public void offerRaw(byte[] row) {
        OtlpMetricRowMapper.map(row).ifPresentOrElse(
                this::offerMappedRow,
                () -> defaultWriter.offer(row));
    }

    void offerMappedRow(OtlpMetricRowMapper.MappedRow mapped) {
        DorisBatchWriter writer = writersByTable.getOrDefault(mapped.table(), defaultWriter);
        writer.offer(mapped.toDorisJsonRow());
    }

    void offerJvmRow(DorisJsonRow row) {
        DorisBatchWriter writer = writersByTable.get(DorisTableNames.METRIC_JVM);
        if (writer != null) {
            logQueuedJvmRow(row);
            writer.offer(row);
        }
    }

    private static void logQueuedJvmRow(DorisJsonRow row) {
        if (!OtlpMetricDebugLogger.isDebugEnabled()) {
            return;
        }
        String json = new String(DorisJsonRow.toByteArray(row));
        try {
            JsonNode node = JSON.readTree(json);
            OtlpMetricDebugLogger.queuedRow(
                    DorisTableNames.METRIC_JVM,
                    node.path("service").asText(""),
                    json);
        } catch (Exception ignored) {
            OtlpMetricDebugLogger.queuedRow(DorisTableNames.METRIC_JVM, "", json);
        }
    }

    public static MetricWriteRouter singleTable(DorisBatchWriter writer) {
        return new MetricWriteRouter(Map.of(DorisTableNames.METRIC_SERVICE, writer));
    }
}
