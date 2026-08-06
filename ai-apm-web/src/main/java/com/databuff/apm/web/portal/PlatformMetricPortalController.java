package com.databuff.apm.web.portal;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generic self-monitoring APIs over {@code metric_platform}.
 * Frontend pages share these endpoints — new charts only need different request params.
 */
@RestController
@RequestMapping("/platform/metrics")
public class PlatformMetricPortalController {

    private final PlatformMetricPortalService platformMetricPortalService;

    public PlatformMetricPortalController(PlatformMetricPortalService platformMetricPortalService) {
        this.platformMetricPortalService = platformMetricPortalService;
    }

    /** Time series: metrics / metricPrefixes + optional filters / groupBy / value. */
    @PostMapping("/query")
    public Map<String, Object> query(@RequestBody Map<String, Object> body) {
        return portalEnvelope(platformMetricPortalService.query(body));
    }

    /** Window aggregate for KPI cards. */
    @PostMapping("/summary")
    public Map<String, Object> summary(@RequestBody Map<String, Object> body) {
        return portalEnvelope(platformMetricPortalService.summary(body));
    }

    /** Distinct tag values: tag=metric|instance|dim|component. */
    @PostMapping("/tagValues")
    public Map<String, Object> tagValues(@RequestBody Map<String, Object> body) {
        return portalEnvelope(platformMetricPortalService.tagValues(body));
    }

    private static Map<String, Object> portalEnvelope(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", 200);
        response.put("message", "success");
        response.put("data", data);
        return response;
    }
}
