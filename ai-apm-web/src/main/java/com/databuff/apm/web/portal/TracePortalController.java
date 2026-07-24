package com.databuff.apm.web.portal;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Portal-compatible trace APIs ({@code POST /webapi/trace/*}).
 */
@RestController
@RequestMapping("/trace")
public class TracePortalController {

    private final TracePortalService tracePortalService;
    private final ServicePortalService servicePortalService;

    public TracePortalController(
            TracePortalService tracePortalService,
            ServicePortalService servicePortalService) {
        this.tracePortalService = tracePortalService;
        this.servicePortalService = servicePortalService;
    }

    @PostMapping("/list")
    public Map<String, Object> list(@RequestBody Map<String, Object> body) {
        return tracePortalService.list(body);
    }

    @PostMapping("/query_params")
    public Map<String, Object> queryParams(@RequestBody Map<String, Object> body) {
        return portalEnvelope(tracePortalService.queryParams(body));
    }

    @PostMapping("/cnt_graph_stats")
    public Map<String, Object> cntGraphStats(@RequestBody Map<String, Object> body) {
        return portalEnvelope(tracePortalService.cntGraphStats(body));
    }

    @PostMapping("/error_cnt_graph_stats")
    public Map<String, Object> errorCntGraphStats(@RequestBody Map<String, Object> body) {
        return portalEnvelope(tracePortalService.errorCntGraphStats(body));
    }

    @PostMapping("/graph_stats")
    public Map<String, Object> graphStats(@RequestBody Map<String, Object> body) {
        return portalEnvelope(tracePortalService.graphStats(body));
    }

    @PostMapping("/allCnt")
    public Map<String, Object> allCnt(@RequestBody Map<String, Object> body) {
        return portalEnvelope(servicePortalService.allCntForSingleResource(body));
    }

    @PostMapping("/spanList")
    public Map<String, Object> spanList(@RequestBody Map<String, Object> body) {
        return tracePortalService.spanList(body);
    }

    @PostMapping("/slowSpanList")
    public Map<String, Object> slowSpanList(@RequestBody Map<String, Object> body) {
        return tracePortalService.slowSpanList(body);
    }

    @PostMapping("/errorSpanList")
    public Map<String, Object> errorSpanList(@RequestBody Map<String, Object> body) {
        return tracePortalService.errorSpanList(body);
    }

    @PostMapping("/exceptionList")
    public Map<String, Object> exceptionList(@RequestBody Map<String, Object> body) {
        return tracePortalService.exceptionList(body);
    }

    @PostMapping("/slowCnt")
    public Map<String, Object> slowCnt(@RequestBody Map<String, Object> body) {
        return portalEnvelope(servicePortalService.slowCntForSingleResource(body));
    }

    @PostMapping("/errorCnt")
    public Map<String, Object> errorCnt(@RequestBody Map<String, Object> body) {
        return portalEnvelope(servicePortalService.errorCntForSingleResource(body));
    }

    @PostMapping("/spans")
    public Map<String, Object> spans(@RequestBody Map<String, Object> body) {
        return tracePortalService.traceSpans(body);
    }

    @PostMapping("/call_spans")
    public Map<String, Object> callSpans(@RequestBody Map<String, Object> body) {
        return tracePortalService.callSpans(body);
    }

    @PostMapping("/serviceFlowEndpoint")
    public Map<String, Object> serviceFlowEndpoint(@RequestBody Map<String, Object> body) {
        return portalEnvelope(tracePortalService.serviceFlowEndpoint(body));
    }

    @PostMapping("/serviceFlow")
    public Map<String, Object> serviceFlow(@RequestBody Map<String, Object> body) {
        return portalEnvelope(tracePortalService.serviceFlow(body));
    }

    @PostMapping("/multipleServiceFlow")
    public Map<String, Object> multipleServiceFlow(@RequestBody Map<String, Object> body) {
        return portalEnvelope(tracePortalService.multipleServiceFlow(body));
    }

    @PostMapping("/serviceInstanceCounts")
    public Map<String, Object> serviceInstanceCounts(@RequestBody Map<String, Object> body) {
        return portalEnvelope(tracePortalService.serviceInstanceCounts(body));
    }

    @PostMapping("/serviceK8sNamespaces")
    public Map<String, Object> serviceK8sNamespaces(@RequestBody Map<String, Object> body) {
        return portalEnvelope(tracePortalService.serviceK8sNamespaces(body));
    }

    private static Map<String, Object> portalEnvelope(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", 200);
        response.put("message", "success");
        response.put("data", data);
        return response;
    }
}
