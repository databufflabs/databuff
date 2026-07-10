package com.databuff.apm.web.tools.local;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricToolResultFormatTest {

    @Test
    void formatsEpochSecToReadableTime() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("epoch_sec", 1_780_900_560L);
        row.put("reqCount", 12);

        Map<String, Object> formatted = MetricToolResultFormat.formatEpochSecRow(row);

        assertThat(formatted)
                .containsEntry("time", "2026-06-08 14:36:00")
                .containsEntry("reqCount", 12)
                .doesNotContainKey("epoch_sec");
    }

    @Test
    void leavesRowsWithoutEpochSecUntouched() {
        Map<String, Object> row = Map.of("exceptionName", "RuntimeException", "metric_value", 3);

        assertThat(MetricToolResultFormat.formatEpochSecRow(row)).isSameAs(row);
        assertThat(MetricToolResultFormat.formatEpochSecRows(List.of(row))).containsExactly(row);
    }
}
