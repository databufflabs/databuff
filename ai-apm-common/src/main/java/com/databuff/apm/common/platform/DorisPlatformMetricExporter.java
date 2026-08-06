package com.databuff.apm.common.platform;

import com.databuff.apm.common.serde.ReusableJson;
import com.databuff.apm.common.storage.DorisJsonRow;
import com.databuff.apm.common.storage.DorisStreamLoader;
import com.databuff.apm.common.storage.DorisTableNames;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Stream-Load exporter for {@code metric_platform} (used by ingest and web). */
public final class DorisPlatformMetricExporter implements PlatformMetricExporter {

    private static final Logger log = LoggerFactory.getLogger(DorisPlatformMetricExporter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DorisStreamLoader loader;
    private final String database;

    public DorisPlatformMetricExporter(DorisStreamLoader loader, String database) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.database = database == null || database.isBlank() ? "databuff" : database;
    }

    @Override
    public void export(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        try {
            List<DorisJsonRow> jsonRows = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                if (!hasWritableValue(row)) {
                    continue;
                }
                byte[] bytes = ReusableJson.writeValueAsBytes(JSON, row);
                jsonRows.add(DorisJsonRow.ofBytes(bytes));
            }
            if (jsonRows.isEmpty()) {
                return;
            }
            DorisStreamLoader.StreamLoadResult result =
                    loader.loadNdjsonRows(database, DorisTableNames.METRIC_PLATFORM, jsonRows);
            if (!result.success()) {
                log.warn(
                        "platform metric stream load failed rows={} body={}",
                        jsonRows.size(),
                        truncate(result.body()));
            }
        } catch (Throwable t) {
            log.warn("platform metric export failed rows={}: {}", rows.size(), t.toString());
        }
    }

    /**
     * Skip rows whose primary numeric field is missing or &lt; 0 (sentinel for "no value").
     * Zero remains writable (queue empty, doris.up=0, etc.).
     */
    static boolean hasWritableValue(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return false;
        }
        Object gauge = row.get("val_gauge");
        if (gauge instanceof Number n) {
            double v = n.doubleValue();
            return Double.isFinite(v) && v >= 0.0d;
        }
        Object cnt = row.get("cnt");
        if (cnt instanceof Number n) {
            return n.longValue() >= 0L;
        }
        // No numeric payload — nothing to store
        return false;
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 240 ? body : body.substring(0, 240) + "...";
    }
}
