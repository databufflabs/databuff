package com.databuff.apm.common.storage;

import com.databuff.apm.common.query.ApmQueryModels;

import java.util.List;

/**
 * Trace list / count against {@code trace_dc_span}.
 */
public final class TraceSpanMetricDrilldownService {

    private final ApmReadRepository readRepository;
    private final String traceDatabase;

    public TraceSpanMetricDrilldownService(
            ApmReadRepository readRepository, String traceDatabase, String metricDatabase) {
        this(readRepository, traceDatabase);
    }

    public TraceSpanMetricDrilldownService(ApmReadRepository readRepository, String traceDatabase) {
        this.readRepository = readRepository;
        this.traceDatabase = traceDatabase;
    }

    public List<ApmQueryModels.SpanSummary> spanList(
            String service,
            java.util.List<String> serviceIds,
            long fromMillis,
            long toMillis,
            int limit,
            int offset,
            String fromTimeText,
            String toTimeText,
            Integer isParent,
            String parentId,
            String sortField,
            String sortOrder) {
        return spanList(
                service,
                serviceIds,
                fromMillis,
                toMillis,
                limit,
                offset,
                fromTimeText,
                toTimeText,
                isParent,
                parentId,
                sortField,
                sortOrder,
                null,
                null,
                null);
    }

    public List<ApmQueryModels.SpanSummary> spanList(
            String service,
            java.util.List<String> serviceIds,
            long fromMillis,
            long toMillis,
            int limit,
            int offset,
            String fromTimeText,
            String toTimeText,
            Integer isParent,
            String parentId,
            String sortField,
            String sortOrder,
            String resourceExact,
            Long minDurationNs,
            Integer error) {
        int safeLimit = limit <= 0 ? 50 : Math.min(limit, 500);
        java.util.List<String> serviceKeys = resolveServiceKeys(service, serviceIds);
        try {
            return readRepository.querySpanSummaries(spanListSql(
                    serviceKeys,
                    fromMillis,
                    toMillis,
                    safeLimit,
                    offset,
                    fromTimeText,
                    toTimeText,
                    isParent,
                    parentId,
                    sortField,
                    sortOrder,
                    resourceExact,
                    minDurationNs,
                    error));
        } catch (Exception e) {
            // Do not swallow: portal/API boundary maps Throwable to a user-facing message.
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(e);
        }
    }

    public long spanListCount(
            String service,
            java.util.List<String> serviceIds,
            long fromMillis,
            long toMillis,
            String fromTimeText,
            String toTimeText,
            Integer isParent,
            String parentId) {
        return spanListCount(
                service,
                serviceIds,
                fromMillis,
                toMillis,
                fromTimeText,
                toTimeText,
                isParent,
                parentId,
                null,
                null,
                null);
    }

    public long spanListCount(
            String service,
            java.util.List<String> serviceIds,
            long fromMillis,
            long toMillis,
            String fromTimeText,
            String toTimeText,
            Integer isParent,
            String parentId,
            String resourceExact,
            Long minDurationNs,
            Integer error) {
        java.util.List<String> serviceKeys = resolveServiceKeys(service, serviceIds);
        try {
            return readRepository.queryCallSpanCount(MetricQueryBuilder.spanListCountSql(
                    traceDatabase,
                    serviceKeys,
                    fromMillis,
                    toMillis,
                    fromTimeText,
                    toTimeText,
                    isParent,
                    parentId,
                    resourceExact,
                    minDurationNs,
                    error));
        } catch (Exception e) {
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(e);
        }
    }

    private String spanListSql(
            java.util.List<String> serviceKeys,
            long fromMillis,
            long toMillis,
            int limit,
            int offset,
            String fromTimeText,
            String toTimeText,
            Integer isParent,
            String parentId,
            String sortField,
            String sortOrder,
            String resourceExact,
            Long minDurationNs,
            Integer error) {
        return MetricQueryBuilder.spanListSql(
                traceDatabase,
                serviceKeys,
                fromMillis,
                toMillis,
                limit,
                offset,
                fromTimeText,
                toTimeText,
                isParent,
                parentId,
                sortField,
                sortOrder,
                resourceExact,
                minDurationNs,
                error);
    }

    private static java.util.List<String> resolveServiceKeys(String service, java.util.List<String> serviceIds) {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        if (service != null && !service.isBlank()) {
            keys.add(service.trim());
        }
        if (serviceIds != null) {
            for (String serviceId : serviceIds) {
                if (serviceId != null && !serviceId.isBlank()) {
                    keys.add(serviceId.trim());
                }
            }
        }
        return java.util.List.copyOf(keys);
    }
}
