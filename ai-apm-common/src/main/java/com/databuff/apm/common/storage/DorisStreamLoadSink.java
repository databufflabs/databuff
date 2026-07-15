package com.databuff.apm.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Flushes {@link DorisBatchWriter} batches via {@link DorisStreamLoader}.
 */
public final class DorisStreamLoadSink {

    private static final Logger log = LoggerFactory.getLogger(DorisStreamLoadSink.class);

    private final DorisBatchWriter batchWriter;
    private final DorisStreamLoader streamLoader;
    private final String database;
    private final String table;

    public DorisStreamLoadSink(
            DorisBatchWriter batchWriter,
            DorisStreamLoader streamLoader,
            String database,
            String table) {
        this.batchWriter = Objects.requireNonNull(batchWriter);
        this.streamLoader = Objects.requireNonNull(streamLoader);
        this.database = Objects.requireNonNull(database);
        this.table = Objects.requireNonNull(table);
    }

    public int flushReady() throws IOException {
        List<byte[]> batch = batchWriter.drainIfReady();
        return loadBatch(batch);
    }

    public int flushAll() throws IOException {
        List<byte[]> batch = batchWriter.flushAll();
        return loadBatch(batch);
    }

    private int loadBatch(List<byte[]> batch) throws IOException {
        if (batch.isEmpty()) {
            return 0;
        }
        byte[] body = joinJsonLines(batch);
        try {
            DorisStreamLoader.StreamLoadResult result = streamLoader.loadJsonLines(database, table, body);
            if (!result.success()) {
                String sample = sampleRow(batch);
                String hint = DorisTableNames.META_SERVICE.equals(table)
                        ? " (re-run deploy/common/sql/databuff.sql if meta_service schema is outdated)"
                        : "";
                logPipelineStreamLoad(table, batch.size(), sample, false, result.body());
                throw new IOException("Doris stream load failed" + hint + ": " + result.body()
                        + (sample.isEmpty() ? "" : " sampleRow=" + sample));
            }
            logPipelineStreamLoad(table, batch.size(), sampleRow(batch), true, result.body());
            log.debug("Stream loaded {} rows to {}.{}", batch.size(), database, table);
            return batch.size();
        } catch (IOException e) {
            batchWriter.offerAll(batch);
            throw e;
        }
    }

    static byte[] joinJsonLines(List<byte[]> rows) {
        if (rows.isEmpty()) {
            return new byte[0];
        }
        int size = 0;
        for (byte[] row : rows) {
            size += row.length + 1;
        }
        byte[] out = new byte[size - 1];
        int pos = 0;
        for (int i = 0; i < rows.size(); i++) {
            byte[] row = rows.get(i);
            System.arraycopy(row, 0, out, pos, row.length);
            pos += row.length;
            if (i < rows.size() - 1) {
                out[pos++] = '\n';
            }
        }
        return out;
    }

    public String table() {
        return table;
    }

    public String database() {
        return database;
    }

    private static String sampleRow(List<byte[]> batch) {
        if (batch.isEmpty()) {
            return "";
        }
        String row = new String(batch.get(0), StandardCharsets.UTF_8);
        return row.length() > 500 ? row.substring(0, 500) + "..." : row;
    }

    private static void logPipelineStreamLoad(
            String table,
            int rowCount,
            String sampleRow,
            boolean success,
            String body) {
        if (!DorisTableNames.METRIC_JVM.equals(table)) {
            return;
        }
        String status = truncate(body, 300);
        if (success) {
            log.debug(
                    "[metric-pipeline] STREAM_LOAD table={} rows={} success=true sample={} doris={}",
                    table,
                    rowCount,
                    sampleRow,
                    status);
        } else {
            log.warn(
                    "[metric-pipeline] STREAM_LOAD table={} rows={} success=false sample={} doris={}",
                    table,
                    rowCount,
                    sampleRow,
                    status);
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }
}
