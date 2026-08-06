package com.databuff.apm.web.tools.local;

import com.databuff.apm.web.portal.PlatformMetricPortalService;
import com.databuff.apm.web.storage.DorisReadQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformToolsTest {

    @Mock
    private DorisReadQueryService dorisReadQueryService;
    @Mock
    private PlatformMetricPortalService platformMetricPortalService;

    private PlatformTools platformTools;

    @BeforeEach
    void setUp() {
        platformTools = new PlatformTools(dorisReadQueryService, platformMetricPortalService, new ObjectMapper());
    }

    @Test
    void querySelfMonitorMetricsSeriesDelegatesToPortalQuery() throws Exception {
        when(platformMetricPortalService.query(any())).thenReturn(Map.of(
                "series", List.of(Map.of("name", "i1", "points", List.of()))));

        String json = platformTools.querySelfMonitorMetrics(
                "2026-08-05 20:00:00",
                "2026-08-05 21:00:00",
                "series",
                List.of("ingest.write.fail"),
                null,
                null,
                List.of("ingest"),
                null,
                null,
                List.of("dim"),
                "cnt",
                60,
                null,
                null);

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(platformMetricPortalService).query(body.capture());
        assertThat(body.getValue().get("fromTime")).isEqualTo("2026-08-05 20:00:00");
        assertThat(body.getValue().get("metrics")).isEqualTo(List.of("ingest.write.fail"));
        assertThat(body.getValue().get("groupBy")).isEqualTo(List.of("dim"));
        assertThat(body.getValue().get("value")).isEqualTo("cnt");
        assertThat(json).contains("\"mode\":\"series\"");
        assertThat(json).contains("\"series\"");
    }

    @Test
    void querySelfMonitorMetricsSummaryDelegatesToPortalSummary() {
        when(platformMetricPortalService.summary(any())).thenReturn(Map.of("items", List.of()));

        platformTools.querySelfMonitorMetrics(
                "2026-08-05 20:00:00",
                "2026-08-05 21:00:00",
                "summary",
                List.of("web.doris.up"),
                null,
                null,
                List.of("web"),
                null,
                null,
                null,
                "gauge",
                null,
                null,
                null);

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(platformMetricPortalService).summary(body.capture());
        assertThat(body.getValue().get("groupBy")).isEqualTo(List.of("metric"));
        assertThat(body.getValue().get("value")).isEqualTo("gauge");
    }

    @Test
    void querySelfMonitorMetricsListDelegatesToTagValues() {
        when(platformMetricPortalService.tagValues(any())).thenReturn(Map.of(
                "tag", "metric",
                "values", List.of("ingest.write.req")));

        String json = platformTools.querySelfMonitorMetrics(
                "2026-08-05 20:00:00",
                "2026-08-05 21:00:00",
                "list",
                null,
                List.of("ingest.write."),
                null,
                List.of("ingest"),
                null,
                null,
                null,
                null,
                null,
                "metric",
                50);

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(platformMetricPortalService).tagValues(body.capture());
        assertThat(body.getValue().get("tag")).isEqualTo("metric");
        assertThat(body.getValue().get("limit")).isEqualTo(50);
        assertThat(body.getValue().get("metricPrefixes")).isEqualTo(List.of("ingest.write."));
        assertThat(json).contains("\"mode\":\"list\"");
    }
}
