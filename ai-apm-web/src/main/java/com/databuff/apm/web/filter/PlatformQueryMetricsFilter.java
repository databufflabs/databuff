package com.databuff.apm.web.filter;

import com.databuff.apm.common.platform.PlatformMetricNames;
import com.databuff.apm.common.platform.PlatformMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Records web API query volume, latency, and failures into {@link PlatformMetrics}.
 * Scoped to {@code /webapi/**} and standard MCP {@code /mcp}.
 */
@Component
public class PlatformQueryMetricsFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String domain = resolveDomain(request.getRequestURI());
        long startMs = System.currentTimeMillis();
        PlatformMetrics.counter(PlatformMetricNames.query(domain, PlatformMetricNames.KIND_REQ)).inc();
        try {
            filterChain.doFilter(request, response);
        } finally {
            PlatformMetrics.timer(PlatformMetricNames.query(domain, PlatformMetricNames.KIND_COST_MS))
                    .add(System.currentTimeMillis() - startMs);
            if (response.getStatus() >= 400) {
                PlatformMetrics.counter(PlatformMetricNames.query(domain, PlatformMetricNames.KIND_FAIL)).inc();
            }
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return true;
        }
        return !path.startsWith("/webapi/") && !path.equals("/mcp") && !path.startsWith("/mcp/");
    }

    static String resolveDomain(String path) {
        if (path == null || path.isBlank()) {
            return "other";
        }
        if (path.equals("/mcp") || path.startsWith("/mcp/")) {
            return "ai";
        }
        String relative = path;
        if (relative.startsWith("/webapi/")) {
            relative = relative.substring("/webapi/".length());
        } else {
            return "other";
        }
        int query = relative.indexOf('?');
        if (query >= 0) {
            relative = relative.substring(0, query);
        }
        if (relative.startsWith("trace")) {
            return "trace";
        }
        if (relative.startsWith("metric") || relative.startsWith("metrics")) {
            return "metric";
        }
        if (relative.startsWith("alarm") || relative.startsWith("monitor")) {
            return "alarm";
        }
        if (relative.startsWith("api/v1/ai")) {
            return "ai";
        }
        if (relative.startsWith("log")) {
            return "log";
        }
        if (relative.startsWith("service")
                || relative.startsWith("cockpit")
                || relative.startsWith("business")
                || relative.startsWith("globalTopology")
                || relative.startsWith("platform")) {
            return "portal";
        }
        return "other";
    }
}
