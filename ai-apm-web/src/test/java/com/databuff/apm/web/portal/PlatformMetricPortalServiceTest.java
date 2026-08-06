package com.databuff.apm.web.portal;

import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.web.config.ApmStorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformMetricPortalServiceTest {

    private static final long FROM = 1_785_980_400_000L;
    private static final long TO = 1_785_981_000_000L;

    private ApmReadRepository readRepository;
    private PlatformMetricPortalService service;

    @BeforeEach
    void setUp() {
        readRepository = mock(ApmReadRepository.class);
        service = new PlatformMetricPortalService(
                readRepository, new ApmStorageProperties("databuff", "databuff", "databuff"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryReturnsEmptySeriesWhenNoMetricSelector() {
        Map<String, Object> result = service.query(Map.of());

        assertThat(result).containsEntry("stepSeconds", 60);
        assertThat((List<Map<String, Object>>) result.get("series")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryFillsAndGroupsSeries() throws Exception {
        when(readRepository.queryRows(anyString(), anyInt())).thenReturn(List.of(
                row("h1", 1_785_980_400L, 1.0)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metrics", List.of("doris.be.alive"));
        body.put("groupBy", List.of("instance"));
        body.put("component", "be");
        body.put("fromTime", FROM);
        body.put("toTime", TO);
        body.put("stepSeconds", 60);

        Map<String, Object> result = service.query(body);

        assertThat(result).containsEntry("from", FROM)
                .containsEntry("to", TO)
                .containsEntry("stepSeconds", 60)
                .containsEntry("value", "auto")
                .containsEntry("groupBy", List.of("instance"));
        List<Map<String, Object>> series = (List<Map<String, Object>>) result.get("series");
        assertThat(series).hasSize(1);
        assertThat(series.get(0)).containsEntry("name", "h1");
        List<Map<String, Object>> points = (List<Map<String, Object>>) series.get(0).get("points");
        assertThat(points).hasSize(10);
        assertThat(points.get(0)).containsEntry("t", 1_785_980_400L).containsEntry("v", 1.0);
        assertThat(points.get(1)).containsEntry("v", null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryTruncatesSeriesBeyondMax() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 205; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("metric", "m" + i);
            row.put("epoch_sec", 1_785_980_400L);
            row.put("metric_value", (double) i);
            rows.add(row);
        }
        when(readRepository.queryRows(anyString(), anyInt())).thenReturn(rows);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metricPrefix", "doris.");
        body.put("groupBy", List.of("metric"));
        body.put("fill", "false");
        body.put("fromTime", FROM);
        body.put("toTime", TO);

        Map<String, Object> result = service.query(body);

        assertThat((List<Map<String, Object>>) result.get("series")).hasSize(200);
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryReturnsEmptySeriesWhenRepositoryFails() throws Exception {
        when(readRepository.queryRows(anyString(), anyInt()))
                .thenThrow(new RuntimeException("boom"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metrics", "doris.be.alive");
        body.put("stepSeconds", 120);

        Map<String, Object> result = service.query(body);

        assertThat(result).containsEntry("stepSeconds", 120);
        assertThat((List<Map<String, Object>>) result.get("series")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void summaryBuildsItemsFromRows() throws Exception {
        when(readRepository.queryRows(anyString(), anyInt())).thenReturn(List.of(
                row("h1", 1_785_980_400L, 1.0),
                row("h2", 1_785_980_400L, 2.0)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metrics", List.of("doris.be.alive"));
        body.put("groupBy", List.of("instance"));
        body.put("fromTime", FROM);
        body.put("toTime", TO);

        Map<String, Object> result = service.summary(body);

        assertThat(result).containsEntry("from", FROM)
                .containsEntry("to", TO)
                .containsEntry("groupBy", List.of("instance"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("name", "h1")
                .containsEntry("value", 1.0)
                .hasEntrySatisfying("tags", tags ->
                        assertThat((Map<String, Object>) tags).containsEntry("instance", "h1"));
        assertThat(items.get(1)).containsEntry("name", "h2").containsEntry("value", 2.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void summaryReturnsEmptyWhenNoMetricSelector() {
        Map<String, Object> result = service.summary(Map.of("fromTime", FROM, "toTime", TO));

        assertThat(result).containsEntry("from", FROM)
                .containsEntry("to", TO)
                .containsEntry("items", List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void summaryReturnsEmptyWhenRepositoryFails() throws Exception {
        when(readRepository.queryRows(anyString(), anyInt()))
                .thenThrow(new RuntimeException("boom"));

        Map<String, Object> result = service.summary(Map.of("metrics", List.of("doris.be.alive")));

        assertThat(result).containsEntry("items", List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void tagValuesSkipsBlankRows() throws Exception {
        Map<String, Object> blankRow = new LinkedHashMap<>();
        blankRow.put("tag_value", "");
        Map<String, Object> nullRow = new LinkedHashMap<>();
        nullRow.put("tag_value", null);
        when(readRepository.queryRows(anyString(), anyInt())).thenReturn(List.of(
                Map.of("tag_value", "h1"),
                Map.of("tag_value", "h2"),
                blankRow,
                nullRow));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tag", "instance");
        body.put("metricPrefix", "doris.");

        Map<String, Object> result = service.tagValues(body);

        assertThat(result).containsEntry("tag", "instance");
        assertThat((List<String>) result.get("values")).containsExactly("h1", "h2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void tagValuesReturnsEmptyForInvalidTag() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tag", "not-a-tag");

        Map<String, Object> result = service.tagValues(body);

        assertThat(result).containsEntry("tag", "").containsEntry("values", List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void tagValuesReturnsEmptyWhenRepositoryFails() throws Exception {
        when(readRepository.queryRows(anyString(), anyInt()))
                .thenThrow(new RuntimeException("boom"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tag", "instance");
        body.put("metrics", List.of("doris.be.alive"));

        Map<String, Object> result = service.tagValues(body);

        assertThat(result).containsEntry("tag", "instance").containsEntry("values", List.of());
    }

    private static Map<String, Object> row(String instance, long epochSec, double value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("instance", instance);
        row.put("epoch_sec", epochSec);
        row.put("metric_value", value);
        return row;
    }
}
