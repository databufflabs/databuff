package com.databuff.apm.web.ai.platform.api;

import com.databuff.apm.web.ai.platform.AiPlatformApiException;
import com.databuff.apm.web.storage.DorisReadQueryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Authenticated read-only Doris SQL API (same JDBC pool as web).
 * Path via portal prefix: {@code POST /webapi/api/v1/ai/doris/query}.
 */
@RestController
@RequestMapping("/api/v1/ai/doris")
public class DorisQueryController {

    private final DorisReadQueryService dorisReadQueryService;

    public DorisQueryController(DorisReadQueryService dorisReadQueryService) {
        this.dorisReadQueryService = dorisReadQueryService;
    }

    @PostMapping("/query")
    public Map<String, Object> query(@RequestBody QueryRequest request) {
        if (request == null || request.sql() == null || request.sql().isBlank()) {
            throw AiPlatformApiException.badRequest("sql is required");
        }
        try {
            return dorisReadQueryService.query(request.sql(), request.maxRows()).toMap();
        } catch (IllegalArgumentException e) {
            throw AiPlatformApiException.badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            throw new AiPlatformApiException("doris_unavailable", 503, e.getMessage());
        }
    }

    public record QueryRequest(String sql, Integer maxRows) {
    }
}
