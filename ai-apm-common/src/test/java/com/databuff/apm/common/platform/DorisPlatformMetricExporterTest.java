package com.databuff.apm.common.platform;

import com.databuff.apm.common.storage.DorisStreamLoader;
import com.databuff.apm.common.storage.DorisStreamLoader.StreamLoadResult;
import com.databuff.apm.common.storage.DorisTableNames;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DorisPlatformMetricExporterTest {

    private final DorisStreamLoader loader = mock(DorisStreamLoader.class);

    @Test
    void constructor_defaultsBlankDatabase() throws Exception {
        org.mockito.Mockito.when(loader.loadNdjsonRows(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new StreamLoadResult(true, 200, "ok"));
        DorisPlatformMetricExporter e1 = new DorisPlatformMetricExporter(loader, null);
        DorisPlatformMetricExporter e2 = new DorisPlatformMetricExporter(loader, "  ");
        DorisPlatformMetricExporter e3 = new DorisPlatformMetricExporter(loader, "my_db");

        e1.export(List.of(row("a", 1)));
        e2.export(List.of(row("a", 1)));
        e3.export(List.of(row("a", 1)));

        verify(loader, org.mockito.Mockito.times(2)).loadNdjsonRows(org.mockito.ArgumentMatchers.eq("databuff"),
                org.mockito.ArgumentMatchers.eq(DorisTableNames.METRIC_PLATFORM),
                org.mockito.ArgumentMatchers.anyList());
        verify(loader).loadNdjsonRows(org.mockito.ArgumentMatchers.eq("my_db"),
                org.mockito.ArgumentMatchers.eq(DorisTableNames.METRIC_PLATFORM),
                org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void export_nullOrEmptyRowsIsNoop() throws Exception {
        DorisPlatformMetricExporter exporter = new DorisPlatformMetricExporter(loader, "databuff");
        exporter.export(null);
        exporter.export(List.of());
        verify(loader, never()).loadNdjsonRows(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void export_skipsRowsWithoutWritableValue() throws Exception {
        DorisPlatformMetricExporter exporter = new DorisPlatformMetricExporter(loader, "databuff");
        Map<String, Object> noNumeric = Map.of("metric", "doris.up");
        Map<String, Object> negativeGauge = Map.of("val_gauge", -1.0);
        Map<String, Object> nanGauge = Map.of("val_gauge", Double.NaN);
        Map<String, Object> negativeCnt = Map.of("cnt", -3L);
        exporter.export(List.of(noNumeric, negativeGauge, nanGauge, negativeCnt));
        verify(loader, never()).loadNdjsonRows(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void export_writesWritableRowsToMetricPlatform() throws Exception {
        DorisPlatformMetricExporter exporter = new DorisPlatformMetricExporter(loader, "databuff");
        Map<String, Object> gauge = row("gauge", 7.0);
        Map<String, Object> counter = Map.of("metric", "ingest.otel.trace.req", "cnt", 12L);
        exporter.export(List.of(gauge, counter));

        ArgumentCaptor<List<com.databuff.apm.common.storage.DorisJsonRow>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(loader).loadNdjsonRows(org.mockito.ArgumentMatchers.eq("databuff"),
                org.mockito.ArgumentMatchers.eq(DorisTableNames.METRIC_PLATFORM),
                rowsCaptor.capture());
        assertThat(rowsCaptor.getValue()).hasSize(2);
    }

    @Test
    void export_usesConstructorDatabase() throws Exception {
        DorisPlatformMetricExporter exporter = new DorisPlatformMetricExporter(loader, "my_db");
        exporter.export(List.of(row("a", 1)));
        verify(loader).loadNdjsonRows(org.mockito.ArgumentMatchers.eq("my_db"),
                org.mockito.ArgumentMatchers.eq(DorisTableNames.METRIC_PLATFORM),
                org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void export_failedLoadIsLoggedNotThrown() throws Exception {
        DorisPlatformMetricExporter exporter = new DorisPlatformMetricExporter(loader, "databuff");
        org.mockito.Mockito.when(loader.loadNdjsonRows(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new StreamLoadResult(false, 200, longBody(300)));
        assertThatCode(() -> exporter.export(List.of(row("a", 1)))).doesNotThrowAnyException();
    }

    @Test
    void export_nullFailureBodyIsLoggedNotThrown() throws Exception {
        DorisPlatformMetricExporter exporter = new DorisPlatformMetricExporter(loader, "databuff");
        org.mockito.Mockito.when(loader.loadNdjsonRows(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new StreamLoadResult(false, 500, null));
        assertThatCode(() -> exporter.export(List.of(row("a", 1)))).doesNotThrowAnyException();
    }

    @Test
    void export_loaderExceptionIsSwallowed() throws Exception {
        DorisPlatformMetricExporter exporter = new DorisPlatformMetricExporter(loader, "databuff");
        org.mockito.Mockito.when(loader.loadNdjsonRows(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(new RuntimeException("boom"));
        assertThatCode(() -> exporter.export(List.of(row("a", 1)))).doesNotThrowAnyException();
    }

    @Test
    void hasWritableValue_coversGaugeAndCntRules() {
        assertThat(DorisPlatformMetricExporter.hasWritableValue(null)).isFalse();
        assertThat(DorisPlatformMetricExporter.hasWritableValue(Map.of())).isFalse();
        assertThat(DorisPlatformMetricExporter.hasWritableValue(Map.of("val_gauge", 0.0))).isTrue();
        assertThat(DorisPlatformMetricExporter.hasWritableValue(Map.of("val_gauge", 5.0))).isTrue();
        assertThat(DorisPlatformMetricExporter.hasWritableValue(Map.of("val_gauge", -0.5))).isFalse();
        assertThat(DorisPlatformMetricExporter.hasWritableValue(Map.of("val_gauge", Double.NaN))).isFalse();
        assertThat(DorisPlatformMetricExporter.hasWritableValue(Map.of("val_gauge", Double.POSITIVE_INFINITY))).isFalse();
        assertThat(DorisPlatformMetricExporter.hasWritableValue(Map.of("val_gauge", Double.NEGATIVE_INFINITY))).isFalse();
        assertThat(DorisPlatformMetricExporter.hasWritableValue(Map.of("cnt", 0L))).isTrue();
        assertThat(DorisPlatformMetricExporter.hasWritableValue(Map.of("cnt", 3L))).isTrue();
        assertThat(DorisPlatformMetricExporter.hasWritableValue(Map.of("cnt", -1L))).isFalse();
        assertThat(DorisPlatformMetricExporter.hasWritableValue(Map.of("metric", "x"))).isFalse();
    }

    private static Map<String, Object> row(String metric, Number value) {
        return Map.of("metric", metric, "val_gauge", value);
    }

    private static String longBody(int len) {
        return "x".repeat(Math.max(0, len));
    }
}
