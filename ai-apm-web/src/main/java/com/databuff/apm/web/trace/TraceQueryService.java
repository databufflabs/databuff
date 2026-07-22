package com.databuff.apm.web.trace;

import com.databuff.apm.common.query.ApmQueryModels.SpanSummary;
import com.databuff.apm.common.query.ApmQueryModels.SpanDetail;

import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.common.storage.MetricQueryBuilder;
import com.databuff.apm.common.storage.TraceSpanMetricDrilldownService;
import com.databuff.apm.web.config.ApmStorageProperties;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;

@Service
public class TraceQueryService {

    private final ApmReadRepository readRepository;
    private final String traceDatabase;
    private final String metricDatabase;

    private final TraceSpanMetricDrilldownService drilldownService;

    public TraceQueryService(ApmReadRepository readRepository, ApmStorageProperties storageProperties) {
        this.readRepository = readRepository;
        this.traceDatabase = storageProperties.traceDatabase();
        this.metricDatabase = storageProperties.metricDatabase();
        this.drilldownService = new TraceSpanMetricDrilldownService(
                readRepository, storageProperties.traceDatabase(), storageProperties.metricDatabase());
    }

    public List<SpanSummary> spanList(SpanListRequest request) {
        return drilldownService.spanList(
                request.service(),
                request.serviceIds(),
                request.from(),
                request.to(),
                request.limit(),
                request.offset(),
                request.fromTimeText(),
                request.toTimeText(),
                request.isParent(),
                request.parentId(),
                request.sortField(),
                request.sortOrder(),
                request.resource(),
                request.minDuration(),
                request.error());
    }

    public long spanListCount(SpanListRequest request) {
        return drilldownService.spanListCount(
                request.service(),
                request.serviceIds(),
                request.from(),
                request.to(),
                request.fromTimeText(),
                request.toTimeText(),
                request.isParent(),
                request.parentId(),
                request.resource(),
                request.minDuration(),
                request.error());
    }

    public List<String> serviceInstances(SpanListRequest request) {
        if (request.service() == null || request.service().isBlank()) {
            return Collections.emptyList();
        }
        try {
            String sql = MetricQueryBuilder.serviceInstanceDistinctSql(
                    metricDatabase, request.service(), request.from(), request.to(), 200);
            return readRepository.queryTopGroups(sql);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public List<String> k8sNamespaces(SpanListRequest request) {
        try {
            String sql = MetricQueryBuilder.k8sNamespaceDistinctSql(
                    metricDatabase, request.from(), request.to(), 200);
            return readRepository.queryTopGroups(sql);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public Map<String, String> serviceK8sNamespaces(SpanListRequest request) {
        try {
            String sql = MetricQueryBuilder.serviceK8sNamespaceMapSql(
                    metricDatabase, request.from(), request.to(), 500);
            return readRepository.queryStringMap(sql, "map_key", "map_value");
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    public Map<String, Integer> serviceInstanceCounts(SpanListRequest request) {
        try {
            String sql = MetricQueryBuilder.serviceInstanceCountMapSql(
                    metricDatabase, request.from(), request.to(), 500);
            return readRepository.queryIntMap(sql, "map_key", "map_value");
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    public List<SpanDetail> traceDetail(TraceDetailRequest request) {
        if (request.traceId() == null || request.traceId().isBlank()) {
            return Collections.emptyList();
        }
        try {
            String sql = MetricQueryBuilder.traceDetailSql(traceDatabase, request.traceId());
            return readRepository.querySpanDetails(sql);
        } catch (Exception e) {
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(e);
        }
    }

    public record SpanListRequest(
            String service,
            List<String> serviceIds,
            long from,
            long to,
            int limit,
            int offset,
            String fromTimeText,
            String toTimeText,
            Integer isParent,
            String parentId,
            String sortField,
            String sortOrder,
            String resource,
            Long minDuration,
            Integer error) {
        public SpanListRequest(String service, long from, long to, int limit) {
            this(service, null, from, to, limit, 0, null, null, null, null, null, null, null, null, null);
        }

        public SpanListRequest(
                String service,
                long from,
                long to,
                int limit,
                String fromTimeText,
                String toTimeText) {
            this(service, null, from, to, limit, 0, fromTimeText, toTimeText, null, null, null, null, null, null, null);
        }

        public SpanListRequest(
                String service,
                List<String> serviceIds,
                long from,
                long to,
                int limit,
                int offset,
                String fromTimeText,
                String toTimeText,
                Integer isParent,
                String parentId,
                String sortField,
                String sortOrder) {
            this(
                    service,
                    serviceIds,
                    from,
                    to,
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

        public SpanListRequest {
            if (limit <= 0) {
                limit = 50;
            }
            if (offset < 0) {
                offset = 0;
            }
            if (serviceIds == null) {
                serviceIds = List.of();
            }
        }

        public List<String> resolvedServiceKeys() {
            LinkedHashSet<String> keys = new LinkedHashSet<>();
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
            return List.copyOf(keys);
        }
    }

    public record TraceDetailRequest(String traceId) {
    }
}
