package com.databuff.apm.web.portal;

import com.databuff.apm.common.flow.ServiceFlowPathIds;
import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.common.time.ApmTimeZones;
import com.databuff.apm.common.util.PortalServiceIdResolver;
import com.databuff.apm.common.query.ApmQueryModels.CallSpanRow;
import com.databuff.apm.common.query.ApmQueryModels.ExceptionListPoint;
import com.databuff.apm.common.query.ApmQueryModels.MetaServicePoint;
import com.databuff.apm.common.query.ApmQueryModels.ServiceFlowEdge;
import com.databuff.apm.common.query.ApmQueryModels.ServiceFlowEntryPoint;
import com.databuff.apm.common.query.ApmQueryModels.ServiceFlowTreeRow;
import com.databuff.apm.common.query.ApmQueryModels.ComponentTrendBucketPoint;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.query.ApmQueryModels.SpanDetail;
import com.databuff.apm.common.query.ApmQueryModels.SpanSummary;
import com.databuff.apm.common.query.TimeSeriesFillUtil;
import com.databuff.apm.common.serde.DcSpanUtil;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.common.storage.DorisTableNames;
import com.databuff.apm.common.storage.LogQueryBuilder;
import com.databuff.apm.common.storage.MetricQueryBuilder;
import com.databuff.apm.web.flow.MultipleServiceFlowTreeBuilder;
import com.databuff.apm.web.flow.ServiceFlowService;
import com.databuff.apm.web.flow.TraceServiceFlowBuilder;
import com.databuff.apm.web.config.ApmStorageProperties;
import com.databuff.apm.web.config.common.CommonResponse;
import com.databuff.apm.web.trace.TraceQueryService;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TracePortalService {

    private final TraceQueryService traceQueryService;
    private final ServiceFlowService serviceFlowService;
    private final ApmReadRepository readRepository;
    private final String metricDatabase;
    private final String traceDatabase;

    public TracePortalService(
            TraceQueryService traceQueryService,
            ServiceFlowService serviceFlowService,
            ApmReadRepository readRepository,
            ApmStorageProperties storageProperties) {
        this.traceQueryService = traceQueryService;
        this.serviceFlowService = serviceFlowService;
        this.readRepository = readRepository;
        this.metricDatabase = storageProperties.metricDatabase();
        this.traceDatabase = storageProperties.traceDatabase();
    }

    public Map<String, Object> list(Map<String, Object> body) {
        try {
            String traceId = decodeTraceId(body.get("traceId"));
            int offset = ServicePortalService.intValue(body.get("offset"), 0);
            int size = ServicePortalService.intValue(body.get("size"), 50);

            List<Map<String, Object>> spans;
            if (traceId != null && !traceId.isBlank()) {
                spans = loadTraceDetailPortalSpans(traceId);
            } else {
                spans = loadPortalSpans(body);
            }

            List<Map<String, Object>> filtered = filterPortalSpans(spans, body);
            List<Map<String, Object>> page = traceId != null && !traceId.isBlank()
                    ? paginate(filtered, offset, size)
                    : filtered;

            if (traceId != null && !traceId.isBlank()) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("status", 200);
                response.put("message", "success");
                response.put("data", page);
                response.put("total", filtered.size());
                return response;
            }

            long total = traceQueryService.spanListCount(buildSpanListRequest(body, offset, size));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("list", page);
            data.put("total", total);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", 200);
            response.put("message", "success");
            response.put("data", data);
            return response;
        } catch (Exception e) {
            return PortalQueryErrors.fail("查询调用链列表", e);
        }
    }

    /** Interface-level span list ({@code POST /webapi/trace/spanList}). */
    public Map<String, Object> spanList(Map<String, Object> body) {
        return queryResourceSpanList(body, null);
    }

    /** Slow interface spans ({@code POST /webapi/trace/slowSpanList}). */
    public Map<String, Object> slowSpanList(Map<String, Object> body) {
        return queryResourceSpanList(body, query -> query.put("minDuration", 1_000_000_000L));
    }

    /** Error interface spans ({@code POST /webapi/trace/errorSpanList}). */
    public Map<String, Object> errorSpanList(Map<String, Object> body) {
        int offset = ServicePortalService.intValue(body.get("offset"), 0);
        int size = ServicePortalService.intValue(body.get("size"), 50);
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        List<String> serviceKeys = resolveTraceServiceKeys(body);
        boolean virtualServiceFilter = isVirtualTraceServiceFilter(body);
        String exception = decodeQueryValue(body.get("exception"));
        String resource = resolveInterfaceUrlFilter(body);
        String sortField = ServicePortalService.stringValue(body.get("sortField"), "startTime");
        String sortOrder = ServicePortalService.stringValue(body.get("sortOrder"), "desc");
        int limit = Math.max(1, Math.min(size <= 0 ? 50 : size, 500));

        try {
            String listSql = MetricQueryBuilder.errorSpanListSql(
                    traceDatabase,
                    serviceKeys,
                    virtualServiceFilter,
                    from,
                    to,
                    limit,
                    Math.max(0, offset),
                    PortalTimeParser.rangeFromText(body),
                    PortalTimeParser.rangeToText(body),
                    sortField,
                    sortOrder,
                    resource,
                    exception);
            String countSql = MetricQueryBuilder.errorSpanListCountSql(
                    traceDatabase,
                    serviceKeys,
                    virtualServiceFilter,
                    from,
                    to,
                    PortalTimeParser.rangeFromText(body),
                    PortalTimeParser.rangeToText(body),
                    resource,
                    exception);
            List<Map<String, Object>> spans = readRepository.querySpanSummaries(listSql).stream()
                    .map(this::toPortalSpan)
                    .toList();
            Map<String, Object> filterBody = new LinkedHashMap<>(body);
            filterBody.put("error", 1);
            List<Map<String, Object>> filtered = filterPortalSpans(spans, filterBody, true);
            long total = readRepository.queryCallSpanCount(countSql);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("list", filtered);
            data.put("total", total);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", 200);
            response.put("message", "success");
            response.put("data", data);
            return response;
        } catch (Exception e) {
            return PortalQueryErrors.fail("查询错误调用链列表", e);
        }
    }

    /** Exception detail rows from {@code metric_service_exception} ({@code POST /webapi/trace/exceptionList}). */
    public Map<String, Object> exceptionList(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        String serviceId = resolveExceptionListServiceId(body);
        String serviceInstance = resolveExceptionListServiceInstance(body);
        String resourceQuery = decodeQueryValue(body.get("resourceQuery"));
        String exceptionQuery = decodeQueryValue(body.get("errorTypeQuery"));
        if (exceptionQuery == null || exceptionQuery.isBlank()) {
            exceptionQuery = decodeQueryValue(body.get("exception"));
        }
        String rootResourceQuery = decodeQueryValue(body.get("rootResourceQuery"));
        int offset = ServicePortalService.intValue(body.get("offset"), 0);
        int size = ServicePortalService.intValue(body.get("size"), 50);
        String sortField = ServicePortalService.stringValue(body.get("sortField"), "start");
        String sortOrder = ServicePortalService.stringValue(body.get("sortOrder"), "desc");

        try {
            String countSql = MetricQueryBuilder.exceptionListCountSql(
                    metricDatabase, from, to, serviceId, serviceInstance,
                    resourceQuery, exceptionQuery, rootResourceQuery);
            long total = readRepository.queryExceptionListCount(countSql);
            String listSql = MetricQueryBuilder.exceptionListSql(
                    metricDatabase, from, to, serviceId, serviceInstance,
                    resourceQuery, exceptionQuery, rootResourceQuery,
                    sortField, sortOrder, offset, size);
            List<Map<String, Object>> rows = readRepository.queryExceptionList(listSql).stream()
                    .map(this::toExceptionListRow)
                    .toList();
            return CommonResponse.listPage(rows, total, offset, rows.size());
        } catch (Exception e) {
            return PortalQueryErrors.fail("查询异常列表", e);
        }
    }

    private Map<String, Object> toExceptionListRow(ExceptionListPoint point) {
        long startMs = point.ts() < 1_000_000_000_000L ? point.ts() * 1000L : point.ts();
        String serviceInstance = point.serviceInstance() == null ? "" : point.serviceInstance();
        String resource = point.resource() == null || point.resource().isBlank()
                ? ""
                : point.resource();
        String exceptionName = point.exceptionName() == null ? "" : point.exceptionName();

        Map<String, Object> meta = new LinkedHashMap<>();
        if (!exceptionName.isBlank()) {
            meta.put("error.type", exceptionName);
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("start", startMs);
        row.put("end", startMs + 60_000L);
        row.put("duration", 0L);
        row.put("error", 1);
        row.put("resource", resource);
        row.put("service", point.service());
        row.put("serviceId", PortalServiceIdResolver.resolve(point.serviceId(), point.service()));
        row.put("serviceInstance", serviceInstance);
        row.put("hostName", serviceInstance);
        row.put("rootResource", point.rootResource() == null ? "" : point.rootResource());
        row.put("meta", meta);
        row.put("errorType", exceptionName.isBlank() ? "Unknown Error" : exceptionName);
        row.put("errCnt", point.errorCount());
        return row;
    }

    private static String resolveExceptionListServiceId(Map<String, Object> body) {
        String serviceId = ServicePortalService.stringValue(body.get("serviceId"), null);
        if (serviceId == null) {
            serviceId = ServicePortalService.stringValue(body.get("sid"), null);
        }
        return serviceId;
    }

    private static String resolveExceptionListServiceInstance(Map<String, Object> body) {
        String serviceInstance = ServicePortalService.stringValue(body.get("serviceInstance"), null);
        if (serviceInstance == null) {
            serviceInstance = ServicePortalService.stringValue(body.get("si"), null);
        }
        return serviceInstance;
    }

    private Map<String, Object> queryResourceSpanList(
            Map<String, Object> body, java.util.function.Consumer<Map<String, Object>> extraFilter) {
        try {
            Map<String, Object> query = new LinkedHashMap<>(body);
            if (extraFilter != null) {
                extraFilter.accept(query);
            }

            int offset = ServicePortalService.intValue(query.get("offset"), 0);
            int size = ServicePortalService.intValue(query.get("size"), 50);
            List<Map<String, Object>> spans = loadResourcePortalSpans(query);
            List<Map<String, Object>> filtered = filterPortalSpans(spans, query);

            long total = traceQueryService.spanListCount(buildResourceSpanListRequest(query, offset, size));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("list", filtered);
            data.put("total", total);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", 200);
            response.put("message", "success");
            response.put("data", data);
            return response;
        } catch (Exception e) {
            return PortalQueryErrors.fail("查询接口调用链列表", e);
        }
    }

    public Map<String, Object> queryParamsV2(Map<String, Object> body) {
        final List<Map<String, Object>> spans;
        try {
            spans = loadPortalSpans(body);
        } catch (Exception e) {
            return PortalQueryErrors.fail("查询调用链筛选参数", e);
        }

        Map<String, Integer> status = new LinkedHashMap<>();
        status.put("0", 0);
        status.put("1", 0);
        Map<String, String> services = new LinkedHashMap<>();
        Map<String, Integer> resources = new LinkedHashMap<>();
        long minDuration = Long.MAX_VALUE;
        long maxDuration = 0;

        for (Map<String, Object> span : spans) {
            int error = ServicePortalService.intValue(span.get("error"), 0);
            status.merge(String.valueOf(error), 1, Integer::sum);

            String service = ServicePortalService.stringValue(span.get("service"), null);
            String serviceId = ServicePortalService.stringValue(span.get("serviceId"), service);
            if (serviceId != null) {
                services.putIfAbsent(service, serviceId);
            }

            String resource = ServicePortalService.stringValue(span.get("resource"), null);
            if (resource != null) {
                resources.merge(resource, 1, Integer::sum);
            }

            long duration = toLong(span.get("duration"));
            if (duration > 0) {
                minDuration = Math.min(minDuration, duration);
                maxDuration = Math.max(maxDuration, duration);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        data.put("service", services);
        data.put("resource", resources);
        if (spans.isEmpty()) {
            data.put("duration", Map.of("min", 0, "max", 0));
        } else {
            data.put("duration", Map.of("min", minDuration, "max", maxDuration));
        }
        return data;
    }

    public Map<String, Object> cntGraphStats(Map<String, Object> body) {
        Map<String, Object> graphs = buildSpanGraphs(body);
        return Map.of("callCnts", graphs.getOrDefault("callCnts", Map.of()));
    }

    public Map<String, Object> errorCntGraphStats(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        int interval = ServicePortalService.intValue(body.get("interval"), 60);
        return Map.of("errorCnts", bucketTraceErrorGraphs(body, from, to, interval));
    }

    public Map<String, Object> graphStats(Map<String, Object> body) {
        Map<String, Object> graphs = buildSpanGraphs(body);
        return Map.of("avgLatencys", graphs.getOrDefault("avgLatencys", Map.of()));
    }

    public Map<String, Object> traceSpans(Map<String, Object> body) {
        try {
            String traceId = decodeTraceId(body.get("traceId"));
            List<Map<String, Object>> spans = traceId == null || traceId.isBlank()
                    ? List.of()
                    : loadTraceDetailPortalSpans(traceId);
            int limit = Math.min(ServicePortalService.intValue(body.get("size"), 1000), 1000);
            List<Map<String, Object>> page = paginate(spans, 0, limit);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", 200);
            response.put("message", "success");
            response.put("data", page);
            response.put("total", spans.size());
            return response;
        } catch (Exception e) {
            return PortalQueryErrors.fail("查询调用链详情", e);
        }
    }

    public Map<String, Object> callSpans(Map<String, Object> body) {
        Map<String, Object> response = emptyCallSpansEnvelope();
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        String fromTimeText = PortalTimeParser.rangeFromText(body);
        String toTimeText = PortalTimeParser.rangeToText(body);

        String componentType = ServicePortalService.stringValue(body.get("componentType"), "service.http");
        String serviceId = ServicePortalService.stringValue(body.get("serviceId"), null);
        String srcServiceId = ServicePortalService.stringValue(body.get("srcServiceId"), null);
        String serviceInstance = ServicePortalService.stringValue(body.get("serviceInstance"), null);
        String srcServiceInstance = ServicePortalService.stringValue(body.get("srcServiceInstance"), null);
        String resource = decodeQueryValue(body.get("resource"));
        String httpMethod = ServicePortalService.stringValue(body.get("httpMethod"), null);
        String rootResourceQuery = decodeQueryValue(body.get("rootResourceQuery"));
        int offset = ServicePortalService.intValue(body.get("offset"), 0);
        int size = ServicePortalService.intValue(body.get("size"), 50);
        String sortField = ServicePortalService.stringValue(body.get("sortField"), "start");
        String sortOrder = ServicePortalService.stringValue(body.get("sortOrder"), "desc");
        boolean includeTotal = !"false".equalsIgnoreCase(String.valueOf(body.getOrDefault("includeTotal", true)));

        MetaServicePoint srcMeta = loadMetaService(srcServiceId);
        MetaServicePoint dstMeta = loadMetaService(serviceId);
        boolean allWebCall = isAllWebCall(srcMeta, dstMeta, srcServiceId, serviceId);

        if (!allWebCall) {
            return queryVirtualCallSpans(
                    response, from, to, fromTimeText, toTimeText, componentType, serviceId, srcServiceId,
                    serviceInstance, srcServiceInstance, resource, httpMethod, rootResourceQuery,
                    offset, size, sortField, sortOrder, includeTotal);
        }

        CallSpanQuery clientQuery = webCallClientQuery(
                from, to, fromTimeText, toTimeText, componentType, serviceId, srcServiceId, serviceInstance,
                srcServiceInstance, resource, httpMethod, rootResourceQuery, offset, size, sortField, sortOrder);
        CallSpanPage page = queryWebCallSpans(clientQuery, serviceId, true, includeTotal);
        if (page.rows().isEmpty()) {
            CallSpanQuery serverQuery = webCallServerQuery(
                    from, to, fromTimeText, toTimeText, componentType, serviceId, srcServiceId, serviceInstance,
                    srcServiceInstance, resource, httpMethod, rootResourceQuery, offset, size, sortField, sortOrder);
            page = queryWebCallSpans(serverQuery, serviceId, false, includeTotal);
        }

        response.put("data", page.rows());
        response.put("total", page.total());
        return response;
    }

    public Map<String, Object> serviceFlowEndpoint(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        String serviceFilter = MetricQueryBuilder.serviceFlowEntryServiceFilter(
                ServicePortalService.stringValue(body.get("serviceId"), null),
                ServicePortalService.stringValue(body.get("service"), null),
                decodeQueryValue(body.get("resource")));

        try {
            List<String> entryPathIds = readRepository.queryDistinctStrings(
                    MetricQueryBuilder.serviceFlowEntryPathIdsSql(metricDatabase, from, to, serviceFilter),
                    "entry_path_id");
            if (!entryPathIds.isEmpty()) {
                List<ServiceFlowEntryPoint> entryPoints = readRepository.queryServiceFlowEntryPoints(
                        MetricQueryBuilder.serviceFlowEntryPointsSql(metricDatabase, from, to, entryPathIds));
                List<Map<String, Object>> rows = toEntryPointRows(entryPoints);
                if (!rows.isEmpty()) {
                    return Map.of("entryPoints", rows);
                }
            }
        } catch (Exception ignored) {
            // fall back below
        }

        int limit = Math.min(ServicePortalService.intValue(body.get("limit"), 500), 500);
        List<ServiceFlowEdge> edges = serviceFlowService.listFlows(null, from, to, limit);
        Set<String> dstSet = new HashSet<>();
        for (ServiceFlowEdge edge : edges) {
            if (edge.dstService() != null && !edge.dstService().isBlank()) {
                dstSet.add(edge.dstService());
            }
        }
        LinkedHashSet<String> entryServices = new LinkedHashSet<>();
        for (ServiceFlowEdge edge : edges) {
            String src = edge.srcService();
            if (src == null || src.isBlank()) {
                continue;
            }
            if (!dstSet.contains(src)) {
                entryServices.add(src);
            }
        }
        if (entryServices.isEmpty()) {
            for (ServiceFlowEdge edge : edges) {
                if (edge.srcService() != null && !edge.srcService().isBlank()) {
                    entryServices.add(edge.srcService());
                }
                if (edge.dstService() != null && !edge.dstService().isBlank()) {
                    entryServices.add(edge.dstService());
                }
            }
        }
        String filterService = ServicePortalService.stringValue(body.get("service"), null);
        String filterServiceId = ServicePortalService.stringValue(body.get("serviceId"), null);
        if (filterService != null && !filterService.isBlank()) {
            LinkedHashSet<String> matched = new LinkedHashSet<>();
            for (String service : entryServices) {
                if (filterService.equals(service)
                        || PortalServiceIdResolver.matches(filterServiceId, service)) {
                    matched.add(service);
                }
            }
            if (!matched.isEmpty()) {
                entryServices = matched;
            }
        }
        List<Map<String, Object>> entryPoints = new ArrayList<>();
        for (String service : entryServices) {
            if (service == null || service.isBlank()) {
                continue;
            }
            String serviceId = PortalServiceIdResolver.normalize(service);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("service", service);
            row.put("serviceId", serviceId);
            row.put("entrypointPathId", ServiceFlowPathIds.entryPathId(serviceId));
            entryPoints.add(row);
        }
        return Map.of("entryPoints", entryPoints);
    }

    /** Interface-level service flow tree ({@code POST /webapi/trace/multipleServiceFlow}). */
    public Map<String, Object> multipleServiceFlow(Map<String, Object> body) {
        String entrypointPathId = ServicePortalService.stringValue(body.get("entrypointPathId"), null);
        if (entrypointPathId == null || entrypointPathId.isBlank()) {
            return Map.of("serviceFlows", Map.of());
        }

        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        entrypointPathId = resolveEntryPathId(entrypointPathId);

        Set<String> entryInterfacePathIds = new LinkedHashSet<>();
        String resource = decodeQueryValue(body.get("resource"));
        if (resource != null && !resource.isBlank()) {
            try {
                entryInterfacePathIds.addAll(readRepository.queryDistinctStrings(
                        MetricQueryBuilder.serviceFlowEntryInterfacePathIdsSql(
                                metricDatabase, from, to, entrypointPathId, resource),
                        "entry_interface_path_id"));
                if (entryInterfacePathIds.isEmpty()) {
                    return Map.of("serviceFlows", Map.of());
                }
            } catch (Exception ignored) {
                return Map.of("serviceFlows", Map.of());
            }
        }

        List<ServiceFlowTreeRow> rows;
        try {
            rows = readRepository.queryServiceFlowTreeRows(
                    MetricQueryBuilder.multipleServiceFlowSql(
                            metricDatabase, from, to, entrypointPathId, entryInterfacePathIds));
        } catch (Exception e) {
            rows = List.of();
        }
        if (rows.isEmpty()) {
            return Map.of("serviceFlows", Map.of());
        }

        String dstServiceId = ServicePortalService.stringValue(body.get("dstServiceId"), null);
        Set<String> pathIds = parseStringSet(body.get("pathIds"));
        Map<String, Map<String, Object>> serviceFlows =
                MultipleServiceFlowTreeBuilder.build(rows, dstServiceId, pathIds);
        return Map.of("serviceFlows", serviceFlows);
    }

    private static List<Map<String, Object>> toEntryPointRows(List<ServiceFlowEntryPoint> entryPoints) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ServiceFlowEntryPoint point : entryPoints) {
            String service = point.service() == null ? "" : point.service().trim();
            String serviceId = point.serviceId() == null ? "" : point.serviceId().trim();
            if (service.isBlank() && serviceId.isBlank()) {
                continue;
            }
            if (service.isBlank()) {
                service = serviceId;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("service", service);
            row.put("serviceId", PortalServiceIdResolver.resolve(serviceId, service));
            row.put("entrypointPathId", resolveEntrypointPathId(point));
            rows.add(row);
        }
        return rows;
    }

    private static String resolveEntrypointPathId(ServiceFlowEntryPoint point) {
        String pathId = point.entrypointPathId();
        if (pathId != null && !pathId.isBlank()) {
            return pathId;
        }
        return ServiceFlowPathIds.entryPathId(
                PortalServiceIdResolver.resolve(point.serviceId(), point.service()));
    }

    private static String resolveEntryPathId(String entrypointPathId) {
        String trimmed = entrypointPathId.trim();
        if (trimmed.chars().allMatch(Character::isDigit) || trimmed.startsWith("-")) {
            return trimmed;
        }
        return ServiceFlowPathIds.entryPathId(PortalServiceIdResolver.normalize(trimmed));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> parseStringSet(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (Object item : list) {
            if (item != null) {
                values.add(String.valueOf(item));
            }
        }
        return values;
    }

    public Map<String, Object> serviceFlow(Map<String, Object> body) {
        String traceId = decodeTraceId(body.get("traceId"));
        if (traceId != null && !traceId.isBlank()) {
            List<Map<String, Object>> spans = loadTraceDetailPortalSpans(traceId);
            return TraceServiceFlowBuilder.build(spans);
        }

        String rootId = ServicePortalService.stringValue(body.get("entrypointPathId"), null);
        if (rootId == null) {
            rootId = ServicePortalService.stringValue(body.get("serviceId"), null);
        }
        if (rootId == null) {
            rootId = ServicePortalService.stringValue(body.get("sid"), null);
        }
        if (rootId == null) {
            rootId = ServicePortalService.stringValue(body.get("service"), null);
        }
        if (rootId == null || rootId.isBlank()) {
            return Map.of("serviceFlows", Map.of());
        }

        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        int limit = Math.min(ServicePortalService.intValue(body.get("limit"), 500), 500);

        List<ServiceFlowEdge> edges = serviceFlowService.listFlows(rootId, from, to, limit);
        Map<String, Object> rootNode = buildFlowNode(rootId, edges, new HashSet<>());
        String rootName = String.valueOf(rootNode.getOrDefault("name", rootId));
        return Map.of("serviceFlows", Map.of(rootName, rootNode));
    }

    public Map<String, Integer> serviceInstanceCounts(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        int limit = ServicePortalService.intValue(body.get("limit"), 500);
        String service = ServicePortalService.stringValue(body.get("service"), null);
        if (service == null) {
            service = ServicePortalService.stringValue(body.get("serviceId"), null);
        }
        return traceQueryService.serviceInstanceCounts(
                new TraceQueryService.SpanListRequest(service, from, to, limit));
    }

    public Map<String, String> serviceK8sNamespaces(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        int limit = ServicePortalService.intValue(body.get("limit"), 500);
        return traceQueryService.serviceK8sNamespaces(
                new TraceQueryService.SpanListRequest(null, from, to, limit));
    }

    public List<String> tabnavStatus(Map<String, Object> body) {
        Map<String, Object> query = new LinkedHashMap<>(body);
        applyInterfaceFilterToQuery(query);
        List<Map<String, Object>> spans = filterPortalSpans(loadPortalSpans(query), query);

        List<String> tabs = new ArrayList<>();
        if (spans.stream().anyMatch(span -> ServicePortalService.intValue(span.get("error"), 0) == 1)) {
            tabs.add("tab-error");
        }
        if (spans.stream().anyMatch(span -> toLong(span.get("duration")) >= 1_000_000_000L)) {
            tabs.add("tab-slow");
        }
        if (!spans.isEmpty()) {
            tabs.add("tab-log");
        }
        return tabs;
    }

    public Map<String, Object> resourcePercent(Map<String, Object> body) {
        long duration = toLong(body.get("duration"));
        Map<String, Object> query = new LinkedHashMap<>(body);
        applyInterfaceFilterToQuery(query);
        String serviceId = resolveService(body);
        if (serviceId != null && !serviceId.isBlank()) {
            query.put("serviceId", serviceId);
        }

        List<Long> durations = filterPortalSpans(loadPortalSpans(query), query).stream()
                .map(span -> toLong(span.get("duration")))
                .filter(value -> value > 0)
                .sorted()
                .toList();

        if (durations.isEmpty()) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("this", 50);
            fallback.put("p99", duration);
            fallback.put("p95", duration);
            fallback.put("p90", duration);
            fallback.put("p75", duration);
            fallback.put("p50", duration);
            fallback.put("max", duration);
            return fallback;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("max", durations.get(durations.size() - 1));
        result.put("p50", percentile(durations, 0.5));
        result.put("p75", percentile(durations, 0.75));
        result.put("p90", percentile(durations, 0.9));
        result.put("p95", percentile(durations, 0.95));
        result.put("p99", percentile(durations, 0.99));
        result.put("this", percentileRank(duration, durations));
        return result;
    }

    private static int percentileRank(long value, List<Long> sortedDurations) {
        int lessOrEqual = 0;
        for (long current : sortedDurations) {
            if (current <= value) {
                lessOrEqual++;
            }
        }
        return (int) Math.round(100.0 * lessOrEqual / sortedDurations.size());
    }

    private Map<String, Object> buildFlowNode(
            String serviceId,
            List<ServiceFlowEdge> edges,
            Set<String> visited) {
        List<ServiceFlowEdge> childEdges = edges.stream()
                .filter(edge -> serviceId.equals(edge.srcService()))
                .toList();

        List<Map<String, Object>> children = new ArrayList<>();
        for (ServiceFlowEdge edge : childEdges) {
            Set<String> childVisited = new HashSet<>(visited);
            childVisited.add(serviceId);
            Map<String, Object> base = new LinkedHashMap<>();
            base.put("serviceId", PortalServiceIdResolver.normalize(edge.dstService()));
            base.put("service", edge.dstService());
            base.put("name", edge.dstService());
            base.put("call", edge.callCount());
            base.put("avgDuration", avgDurationMsToNs(edge.avgDuration()));
            base.put("avgCall", avgDurationMsToNs(edge.avgDuration()));
            base.put("callPct", 0);
            base.put("durationCvPct", 0);
            if (childVisited.contains(edge.dstService())) {
                base.put("children", List.of());
                children.add(base);
                continue;
            }
            Map<String, Object> nested = buildFlowNode(edge.dstService(), edges, childVisited);
            base.put("children", nested.getOrDefault("children", List.of()));
            children.add(base);
        }

        long totalCall = childEdges.stream().mapToLong(ServiceFlowEdge::callCount).sum();
        double avgDuration = childEdges.isEmpty() ? 0 : childEdges.get(0).avgDuration();
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("serviceId", PortalServiceIdResolver.normalize(serviceId));
        node.put("service", serviceId);
        node.put("name", serviceId);
        node.put("call", totalCall);
        node.put("avgDuration", avgDurationMsToNs(avgDuration));
        node.put("avgCall", avgDurationMsToNs(avgDuration));
        node.put("callPct", 100);
        node.put("durationCvPct", 0);
        node.put("serviceDurationRange", List.of());
        node.put("children", children);
        return node;
    }

    private static long avgDurationMsToNs(double avgDurationMs) {
        return (long) (avgDurationMs * 1_000_000L);
    }

    private Map<String, Object> buildSpanGraphs(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        int interval = ServicePortalService.intValue(body.get("interval"), 60);
        return bucketTraceMetricGraphs(body, from, to, interval);
    }

    private Map<String, Object> bucketTraceMetricGraphs(
            Map<String, Object> body, long from, long to, int interval) {
        Map<String, Long> callCnts = new LinkedHashMap<>();
        Map<String, Map<String, Long>> errorCnts = new LinkedHashMap<>();
        Map<String, Double> sumDurationNs = new LinkedHashMap<>();

        List<String> serviceKeys = resolveGraphServiceKeys(body);
        java.util.Collection<String> services = serviceKeys.isEmpty() ? null : serviceKeys;

        try {
            String sql = MetricQueryBuilder.componentTrendBucketsSql(
                    metricDatabase,
                    DorisTableNames.METRIC_SERVICE_TRACE,
                    from,
                    to,
                    interval,
                    services,
                    resolveTraceGraphServiceInstance(body),
                    resolveTraceGraphResourceQuery(body),
                    null,
                    null,
                    null,
                    null);
            List<ComponentTrendBucketPoint> points = readRepository.queryComponentTrendBuckets(sql);
            for (ComponentTrendBucketPoint point : points) {
                String bucket = String.valueOf(point.bucketEpochSec() * 1000L);
                callCnts.merge(bucket, point.requestCount(), Long::sum);

                if (point.errorCount() > 0) {
                    errorCnts.computeIfAbsent(bucket, key -> new LinkedHashMap<>());
                    errorCnts.get(bucket).merge(point.service(), point.errorCount(), Long::sum);
                }

                if (point.requestCount() > 0 && point.sumDurationNs() > 0) {
                    sumDurationNs.merge(bucket, point.sumDurationNs(), Double::sum);
                }
            }
        } catch (Exception ignored) {
            return emptyGraphs(from, to, interval);
        }

        return buildGraphResult(callCnts, errorCnts, sumDurationNs, from, to, interval);
    }

    private Map<String, Map<String, Long>> bucketTraceErrorGraphs(
            Map<String, Object> body, long from, long to, int interval) {
        Map<String, Map<String, Long>> errorCnts = new LinkedHashMap<>();
        List<String> serviceKeys = resolveGraphServiceKeys(body);
        java.util.Collection<String> services = serviceKeys.isEmpty() ? null : serviceKeys;

        try {
            String sql = MetricQueryBuilder.traceErrorTrendBucketsSql(
                    metricDatabase,
                    from,
                    to,
                    interval,
                    services,
                    resolveTraceGraphServiceInstance(body),
                    resolveTraceGraphResourceQuery(body));
            List<ComponentTrendBucketPoint> points = readRepository.queryComponentTrendBuckets(sql);
            for (ComponentTrendBucketPoint point : points) {
                if (point.errorCount() <= 0) {
                    continue;
                }
                String bucket = String.valueOf(point.bucketEpochSec() * 1000L);
                errorCnts.computeIfAbsent(bucket, key -> new LinkedHashMap<>());
                errorCnts.get(bucket).merge(point.service(), point.errorCount(), Long::sum);
            }
        } catch (Exception ignored) {
            return TimeSeriesFillUtil.fillStringKeyObjectMap(null, from, to, interval);
        }

        return TimeSeriesFillUtil.fillStringKeyObjectMap(errorCnts, from, to, interval);
    }

    private static String resolveTraceGraphServiceInstance(Map<String, Object> body) {
        String serviceInstance = ServicePortalService.stringValue(body.get("serviceInstance"), null);
        if (serviceInstance == null) {
            serviceInstance = ServicePortalService.stringValue(body.get("si"), null);
        }
        if (serviceInstance != null && !serviceInstance.isBlank()) {
            return serviceInstance;
        }
        List<String> serviceInstances = parseStringList(body.get("serviceInstances"));
        return serviceInstances.size() == 1 ? serviceInstances.get(0) : null;
    }

    private static String resolveTraceGraphResourceQuery(Map<String, Object> body) {
        String resourceQuery = decodeQueryValue(body.get("resourceQuery"));
        if (resourceQuery != null && !resourceQuery.isBlank()) {
            return resourceQuery;
        }
        resourceQuery = decodeQueryValue(body.get("rootResourceQuery"));
        if (resourceQuery != null && !resourceQuery.isBlank()) {
            return resourceQuery;
        }
        String fuzzyTraceName = ServicePortalService.stringValue(body.get("fuzzyTraceName"), null);
        if (fuzzyTraceName != null && !fuzzyTraceName.isBlank()) {
            return fuzzyTraceName;
        }
        String resource = decodeQueryValue(body.get("resource"));
        if (resource != null && !resource.isBlank()) {
            return resource;
        }
        List<String> resources = parseStringList(body.get("resources"));
        return resources.size() == 1 ? resources.get(0) : null;
    }

    private static List<String> resolveGraphServiceKeys(Map<String, Object> body) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        String service = resolveService(body);
        if (service != null && !service.isBlank()) {
            keys.add(service.trim());
        }
        for (String serviceId : parseStringList(body.get("serviceIds"))) {
            keys.add(serviceId);
        }
        return List.copyOf(keys);
    }

    private List<Map<String, Object>> loadPortalSpans(Map<String, Object> body) {
        int offset = ServicePortalService.intValue(body.get("offset"), 0);
        int size = ServicePortalService.intValue(body.get("size"), 50);
        List<SpanSummary> spans = traceQueryService.spanList(buildSpanListRequest(body, offset, size));
        return spans.stream().map(this::toPortalSpan).toList();
    }

    private List<Map<String, Object>> loadResourcePortalSpans(Map<String, Object> body) {
        int offset = ServicePortalService.intValue(body.get("offset"), 0);
        int size = ServicePortalService.intValue(body.get("size"), 50);
        List<SpanSummary> spans = traceQueryService.spanList(buildResourceSpanListRequest(body, offset, size));
        return spans.stream().map(this::toPortalSpan).toList();
    }

    private TraceQueryService.SpanListRequest buildResourceSpanListRequest(
            Map<String, Object> body, int offset, int size) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        int limit = Math.max(1, Math.min(size <= 0 ? 50 : size, 500));
        List<String> serviceKeys = resolveTraceServiceKeys(body);
        return new TraceQueryService.SpanListRequest(
                null,
                serviceKeys,
                from,
                to,
                limit,
                Math.max(0, offset),
                PortalTimeParser.rangeFromText(body),
                PortalTimeParser.rangeToText(body),
                null,
                null,
                ServicePortalService.stringValue(body.get("sortField"), "startTime"),
                ServicePortalService.stringValue(body.get("sortOrder"), "desc"),
                resolveInterfaceUrlFilter(body),
                parseSpanListMinDuration(body),
                parseSpanListError(body));
    }

    /**
     * HTTP interface queries filter by path-only {@code url}; other component types use {@code resource}.
     */
    private static String resolveInterfaceUrlFilter(Map<String, Object> body) {
        if ("service.http".equals(ServicePortalService.stringValue(body.get("componentType"), null))) {
            String url = ServicePortalService.decodeResourceValue(decodeQueryValue(body.get("url")));
            if (url != null && !url.isBlank()) {
                return url;
            }
        }
        return ServicePortalService.decodeResourceValue(decodeQueryValue(body.get("resource")));
    }

    private static void applyInterfaceFilterToQuery(Map<String, Object> query) {
        String filterKey = resolveInterfaceUrlFilter(query);
        if (filterKey == null || filterKey.isBlank()) {
            return;
        }
        if ("service.http".equals(ServicePortalService.stringValue(query.get("componentType"), null))) {
            query.put("url", filterKey);
            query.remove("resource");
        } else {
            query.put("resource", filterKey);
        }
    }

    private List<String> resolveTraceServiceKeys(Map<String, Object> body) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        String serviceId = ServicePortalService.stringValue(body.get("serviceId"), null);
        if (serviceId == null) {
            serviceId = ServicePortalService.stringValue(body.get("sid"), null);
        }
        if (serviceId != null && !serviceId.isBlank()) {
            keys.add(PortalServiceIdResolver.normalize(serviceId.trim()));
        }
        for (String id : parseStringList(body.get("serviceIds"))) {
            if (id != null && !id.isBlank()) {
                keys.add(PortalServiceIdResolver.normalize(id.trim()));
            }
        }
        return List.copyOf(keys);
    }

    private static Long parseSpanListMinDuration(Map<String, Object> body) {
        if (body.get("minDuration") == null || "".equals(String.valueOf(body.get("minDuration")).trim())) {
            return null;
        }
        return toLong(body.get("minDuration"));
    }

    private static Integer parseSpanListError(Map<String, Object> body) {
        if (body.get("error") == null || "".equals(String.valueOf(body.get("error")).trim())) {
            return null;
        }
        return ServicePortalService.intValue(body.get("error"), 0);
    }

    private TraceQueryService.SpanListRequest buildSpanListRequest(Map<String, Object> body, int offset, int size) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        SpanListScope scope = resolveSpanListScope(body);
        int limit = Math.max(1, Math.min(size <= 0 ? 50 : size, 1000));
        return new TraceQueryService.SpanListRequest(
                resolveService(body),
                parseStringList(body.get("serviceIds")),
                from,
                to,
                limit,
                Math.max(0, offset),
                PortalTimeParser.rangeFromText(body),
                PortalTimeParser.rangeToText(body),
                scope.isParent(),
                scope.parentId(),
                ServicePortalService.stringValue(body.get("sortField"), "start"),
                ServicePortalService.stringValue(body.get("sortOrder"), "desc"),
                ServicePortalService.decodeResourceValue(decodeQueryValue(body.get("resource"))),
                parseSpanListMinDuration(body),
                parseSpanListError(body));
    }

    /** Legacy portal: {@code parentId=0} means spans with blank/zero {@code parent_id} ({@code is_parent=1}). */
    private static SpanListScope resolveSpanListScope(Map<String, Object> body) {
        String parentId = ServicePortalService.stringValue(body.get("parentId"), "0");
        if ("0".equals(parentId)) {
            return new SpanListScope(1, null);
        }
        if (parentId == null || parentId.isBlank()) {
            return new SpanListScope(null, null);
        }
        return new SpanListScope(null, parentId);
    }

    private record SpanListScope(Integer isParent, String parentId) {
    }

    private List<Map<String, Object>> loadTraceDetailPortalSpans(String traceId) {
        List<SpanDetail> spans = traceQueryService.traceDetail(new TraceQueryService.TraceDetailRequest(traceId));
        if (spans.isEmpty()) {
            return List.of();
        }
        long minStartNs = spans.stream()
                .mapToLong(SpanDetail::start)
                .filter(startNs -> startNs > 0L)
                .min()
                .orElse(0L);
        Set<String> logSpanIds = loadLogSpanIds(traceId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SpanDetail span : spans) {
            Map<String, Object> row = toPortalTraceSpan(span, minStartNs);
            String spanId = span.spanId();
            row.put("hasLog", spanId != null && !spanId.isBlank() && logSpanIds.contains(spanId));
            rows.add(row);
        }
        return rows;
    }

    private Set<String> loadLogSpanIds(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return Set.of();
        }
        try {
            String sql = LogQueryBuilder.spanIdsWithLogsSql(traceDatabase, traceId);
            List<Map<String, Object>> rows = readRepository.queryRows(sql, 10_000);
            Set<String> spanIds = new HashSet<>();
            for (Map<String, Object> row : rows) {
                Object value = row.get("span_id");
                if (value == null) {
                    continue;
                }
                String spanId = String.valueOf(value).trim();
                if (!spanId.isEmpty()) {
                    spanIds.add(spanId);
                }
            }
            return spanIds;
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private Map<String, Object> toPortalSpan(SpanSummary span) {
        long startMs = parseStartMillis(span.startTime());
        long durationNs = spanDurationNs(span.duration());
        String serviceInstance = ServicePortalService.stringValue(span.serviceInstance(), "");
        String hostName = ServicePortalService.stringValue(span.hostName(), "");
        String instance = !serviceInstance.isBlank() ? serviceInstance : hostName;

        Map<String, Object> meta = new LinkedHashMap<>();
        if (span.metaErrorType() != null && !span.metaErrorType().isBlank()) {
            meta.put("error.type", span.metaErrorType());
        }
        if (span.metaHttpStatusCode() != null) {
            meta.put("http.status_code", span.metaHttpStatusCode());
        }
        if (span.metaHttpUrl() != null && !span.metaHttpUrl().isBlank()) {
            meta.put("http.url", span.metaHttpUrl());
        }
        String httpMethod = resolveHttpMethod(span.resource(), span.name());
        if (httpMethod != null) {
            meta.put("http.method", httpMethod);
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("trace_id", span.traceId());
        row.put("span_id", span.spanId());
        row.put("service", span.service());
        row.put("serviceId", PortalServiceIdResolver.resolve(span.serviceId(), span.service()));
        row.put("serviceInstance", instance);
        String displayResource = displayHttpResource(span.resource(), span.name(), null, span.metaHttpUrl());
        row.put("resource", displayResource);
        row.put("start", startMs);
        row.put("end", startMs + (long) Math.ceil(durationNs / 1_000_000.0));
        row.put("duration", durationNs);
        row.put("error", span.error());
        DcSpanUtil.PortalSpanDisplay portalDisplay = DcSpanUtil.resolvePortalSpanDisplay(toClassificationSpan(span, meta));
        row.put("service_type", portalDisplay.serviceType());
        row.put("type", portalDisplay.typeIcon());
        row.put("hostName", !hostName.isBlank() ? hostName : instance);
        String parentId = span.parentId();
        row.put("parent_id", parentId == null || parentId.isBlank() ? "0" : parentId);
        if (span.isParent() != null) {
            row.put("is_parent", span.isParent());
        }
        if (span.metaErrorType() != null) {
            row.put("metaErrorType", span.metaErrorType());
        }
        if (span.metaHttpStatusCode() != null) {
            row.put("metaHttpStatusCode", span.metaHttpStatusCode());
        }
        row.put("meta", meta);
        if (span.error() > 0) {
            row.put("errorType", resolvePortalErrorType(span));
        }
        return row;
    }

    private static String resolvePortalErrorType(SpanSummary span) {
        if (span.metaErrorType() != null && !span.metaErrorType().isBlank()) {
            return span.metaErrorType();
        }
        if (span.metaHttpStatusCode() != null && span.metaHttpStatusCode() >= 400) {
            return "HTTP " + span.metaHttpStatusCode();
        }
        String resource = span.resource() != null && !span.resource().isBlank() ? span.resource() : span.name();
        return resource == null || resource.isBlank() ? "Unknown Error" : resource;
    }

    private static String resolvePortalErrorType(Map<String, Object> row) {
        Object metaErrorType = row.get("metaErrorType");
        if (metaErrorType != null && !String.valueOf(metaErrorType).isBlank()) {
            return String.valueOf(metaErrorType);
        }
        Object metaObj = row.get("meta");
        if (metaObj instanceof Map<?, ?> meta) {
            Object errorType = meta.get("error.type");
            if (errorType != null && !String.valueOf(errorType).isBlank()) {
                return String.valueOf(errorType);
            }
        }
        Object status = row.get("metaHttpStatusCode");
        if (status instanceof Number number && number.intValue() >= 400) {
            return "HTTP " + number.intValue();
        }
        String resource = String.valueOf(row.getOrDefault("resource", ""));
        return resource.isBlank() ? "Unknown Error" : resource;
    }

    private Map<String, Object> toPortalTraceSpan(SpanDetail span, long traceStartNs) {
        long startNs = span.start() > 0L ? span.start() : parseStartMillis(span.startTime()) * 1_000_000L;
        long traceBaseNs = traceStartNs > 0L ? traceStartNs : startNs;
        long relativeTime = Math.max(0L, startNs - traceBaseNs);
        long startMs = startNs / 1_000_000L;
        long durationNs = spanDurationNs(span.duration());
        Map<String, String> meta = new LinkedHashMap<>(OtelAttributeMaps.parse(span.meta()));
        Map<String, String> metrics = new LinkedHashMap<>(OtelAttributeMaps.parse(span.metrics()));
        mergeNonBlank(meta, "http.method", span.metaHttpMethod());
        mergeNonBlank(meta, "http.url", span.metaHttpUrl());
        mergeNonBlank(meta, "error.type", span.metaErrorType());
        if (span.metaHttpStatusCode() != null) {
            meta.put("http.status_code", String.valueOf(span.metaHttpStatusCode()));
        }
        String resource = displayTraceResource(span, meta);
        String serviceInstance = !nullToEmpty(span.serviceInstance()).isBlank()
                ? span.serviceInstance()
                : nullToEmpty(span.hostName());

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("trace_id", span.traceId());
        row.put("span_id", span.spanId());
        row.put("parent_id", span.parentId() == null || span.parentId().isBlank() ? "0" : span.parentId());
        row.put("resource", resource);
        row.put("name", span.name());
        row.put("service", span.service());
        row.put("serviceId", PortalServiceIdResolver.resolve(span.serviceId(), span.service()));
        row.put("serviceInstance", serviceInstance);
        DcSpanUtil.PortalSpanDisplay portalDisplay = DcSpanUtil.resolvePortalSpanDisplay(toClassificationSpan(span, meta));
        row.put("service_type", portalDisplay.serviceType());
        row.put("type", portalDisplay.typeIcon());
        row.put("start", String.valueOf(startMs));
        row.put("startNs", startNs);
        long endMs = startMs + (long) Math.ceil(durationNs / 1_000_000.0);
        row.put("end", String.valueOf(endMs));
        row.put("relativeTime", relativeTime);
        row.put("duration", durationNs);
        row.put("exectime", durationNs);
        row.put("error", span.error());
        row.put("hostName", ServicePortalService.stringValue(span.hostName(), ""));
        row.put("isIn", span.isIn());
        row.put("isOut", span.isOut());
        row.put("hasInner", false);
        // Entry-span UI reads clientService / clientServiceInstance (not meta keys).
        row.put("clientService", OtelAttributeMaps.firstNonBlank(meta, "client.service"));
        row.put("clientServiceInstance", OtelAttributeMaps.firstNonBlank(meta, "client.ip"));
        row.put("meta", meta);
        row.put("metrics", metrics);
        row.put("_start", String.valueOf(startMs));
        row.put("startTime", startMs);
        row.put("endTime", endMs);
        if (span.metaHttpStatusCode() != null) {
            row.put("metaHttpStatusCode", span.metaHttpStatusCode());
        }
        if (span.metaErrorType() != null && !span.metaErrorType().isBlank()) {
            row.put("metaErrorType", span.metaErrorType());
        }
        if (span.error() > 0) {
            row.put("errorType", resolvePortalErrorType(row));
        }
        return row;
    }

    private static void mergeNonBlank(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.putIfAbsent(key, value.trim());
        }
    }

    private static DcSpan toClassificationSpan(SpanDetail span, Map<String, String> meta) {
        DcSpan dcSpan = new DcSpan();
        dcSpan.meta = span.meta() != null && !span.meta().isBlank()
                ? span.meta()
                : meta.isEmpty() ? null : OtelAttributeMaps.encode(meta);
        dcSpan.metaHttpMethod = span.metaHttpMethod();
        dcSpan.metaHttpUrl = span.metaHttpUrl();
        dcSpan.metaHttpStatusCode = span.metaHttpStatusCode();
        dcSpan.resource = span.resource();
        dcSpan.name = span.name();
        dcSpan.type = span.type();
        dcSpan.isIn = span.isIn();
        dcSpan.isOut = span.isOut();
        dcSpan.serviceId = span.serviceId();
        dcSpan.dstServiceId = span.serviceId();
        return dcSpan;
    }

    private static DcSpan toClassificationSpan(SpanSummary span, Map<String, Object> meta) {
        DcSpan dcSpan = new DcSpan();
        dcSpan.metaHttpUrl = span.metaHttpUrl();
        dcSpan.metaHttpStatusCode = span.metaHttpStatusCode();
        dcSpan.resource = span.resource();
        dcSpan.name = span.name();
        dcSpan.serviceId = span.serviceId();
        dcSpan.dstServiceId = span.serviceId();
        String httpMethod = resolveHttpMethod(span.resource(), span.name());
        if (httpMethod != null) {
            dcSpan.metaHttpMethod = httpMethod;
        }
        if (!meta.isEmpty()) {
            Map<String, String> stringMeta = new LinkedHashMap<>();
            meta.forEach((key, value) -> {
                if (value != null) {
                    stringMeta.put(key, String.valueOf(value));
                }
            });
            dcSpan.meta = OtelAttributeMaps.encode(stringMeta);
        }
        return dcSpan;
    }

    private static String displayTraceResource(SpanDetail span, Map<String, String> meta) {
        String method = OtelAttributeMaps.firstNonBlank(meta, "http.method", "method");
        if (method == null || method.isBlank()) {
            method = span.metaHttpMethod();
        }
        String url = OtelAttributeMaps.firstNonBlank(meta, "http.route", "http.url", "url.full", "url");
        if (url == null || url.isBlank()) {
            url = span.metaHttpUrl();
        }
        return displayHttpResource(span.resource(), span.name(), method, url);
    }

    private static String displayHttpResource(String resource, String name, String httpMethod, String httpUrl) {
        String resourceText = nullToEmpty(resource).trim();
        String nameText = nullToEmpty(name).trim();
        String url = httpUrl;
        if (url != null && !url.isBlank()) {
            url = DcSpanUtil.normalizeHttpUrl(url);
        }

        String base = !resourceText.isBlank() ? resourceText : nameText;
        if (httpMethod != null && !httpMethod.isBlank() && url != null && !url.isBlank()
                && (base.isBlank() || base.equalsIgnoreCase(httpMethod.trim()))) {
            return httpMethod.trim().toUpperCase(Locale.ROOT) + " " + url;
        }
        if (isHttpMethodOnly(base) && url != null && !url.isBlank()) {
            return base.toUpperCase(Locale.ROOT) + " " + url;
        }
        return base.isBlank() ? nameText : base;
    }

    private static String resolveHttpMethod(String resource, String name) {
        String resourceText = nullToEmpty(resource).trim();
        if (isHttpMethodOnly(resourceText)) {
            return resourceText.toUpperCase(Locale.ROOT);
        }
        String nameText = nullToEmpty(name).trim();
        if (isHttpMethodOnly(nameText)) {
            return nameText.toUpperCase(Locale.ROOT);
        }
        return null;
    }

    private static boolean isHttpMethodOnly(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE" -> true;
            default -> false;
        };
    }

    private boolean isVirtualTraceServiceFilter(Map<String, Object> body) {
        String serviceId = resolveExceptionListServiceId(body);
        if (serviceId == null || serviceId.isBlank()) {
            return false;
        }
        MetaServicePoint meta = loadMetaService(PortalServiceIdResolver.normalize(serviceId.trim()));
        return meta != null && Boolean.TRUE.equals(meta.virtualService());
    }

    private List<Map<String, Object>> filterPortalSpans(List<Map<String, Object>> list, Map<String, Object> body) {
        return filterPortalSpans(list, body, false);
    }

    private List<Map<String, Object>> filterPortalSpans(
            List<Map<String, Object>> list, Map<String, Object> body, boolean skipServiceFilter) {
        List<Map<String, Object>> rows = new ArrayList<>(list);

        if (!skipServiceFilter) {
            String serviceId = ServicePortalService.stringValue(body.get("serviceId"), null);
            if (serviceId != null) {
                rows.removeIf(row -> !PortalServiceIdResolver.matches(
                        serviceId, String.valueOf(row.get("serviceId"))));
            }

            List<String> serviceIds = parseStringList(body.get("serviceIds"));
            if (!serviceIds.isEmpty()) {
                rows.removeIf(row -> serviceIds.stream().noneMatch(id -> PortalServiceIdResolver.matches(
                        id, String.valueOf(row.get("serviceId")))));
            }
        }

        List<String> serviceInstances = parseStringList(body.get("serviceInstances"));
        if (!serviceInstances.isEmpty()) {
            rows.removeIf(row -> !serviceInstances.contains(String.valueOf(row.get("serviceInstance"))));
        }

        String serviceInstanceFilter = ServicePortalService.stringValue(body.get("serviceInstance"), null);
        if (serviceInstanceFilter == null) {
            serviceInstanceFilter = ServicePortalService.stringValue(body.get("si"), null);
        }
        if (serviceInstanceFilter != null && !serviceInstanceFilter.isBlank()) {
            final String serviceInstance = serviceInstanceFilter;
            rows.removeIf(row -> !serviceInstance.equals(String.valueOf(row.get("serviceInstance"))));
        }

        List<String> resources = parseStringList(body.get("resources"));
        if (!resources.isEmpty()) {
            rows.removeIf(row -> !resources.contains(String.valueOf(row.get("resource"))));
        }

        String endpointFilter = resolveInterfaceUrlFilter(body);
        if (endpointFilter != null && !endpointFilter.isBlank()) {
            final String endpoint = endpointFilter;
            if ("service.http".equals(ServicePortalService.stringValue(body.get("componentType"), null))) {
                rows.removeIf(row -> !matchesHttpSpanUrl(endpoint, row));
            } else {
                rows.removeIf(row -> !endpoint.equals(String.valueOf(row.get("resource"))));
            }
        }

        String fuzzyTraceName = ServicePortalService.stringValue(body.get("fuzzyTraceName"), null);
        if (fuzzyTraceName != null) {
            String keyword = fuzzyTraceName.toLowerCase(Locale.ROOT);
            rows.removeIf(row -> !String.valueOf(row.get("resource")).toLowerCase(Locale.ROOT).contains(keyword));
        }

        String resourceQuery = decodeQueryValue(body.get("resourceQuery"));
        if (resourceQuery != null && !resourceQuery.isBlank()) {
            String keyword = resourceQuery.toLowerCase(Locale.ROOT);
            rows.removeIf(row -> !String.valueOf(row.get("resource")).toLowerCase(Locale.ROOT).contains(keyword));
        }

        String rootResourceQuery = decodeQueryValue(body.get("rootResourceQuery"));
        if (rootResourceQuery != null && !rootResourceQuery.isBlank()) {
            String keyword = rootResourceQuery.toLowerCase(Locale.ROOT);
            rows.removeIf(row -> !String.valueOf(row.get("resource")).toLowerCase(Locale.ROOT).contains(keyword));
        }

        String exception = decodeQueryValue(body.get("exception"));
        if (exception != null && !exception.isBlank()) {
            String keyword = exception.toLowerCase(Locale.ROOT);
            rows.removeIf(row -> !resolvePortalErrorType(row).toLowerCase(Locale.ROOT).contains(keyword));
        }

        List<String> traceIds = parseStringList(body.get("traceIds"));
        if (!traceIds.isEmpty()) {
            rows.removeIf(row -> !traceIds.contains(String.valueOf(row.get("trace_id"))));
        }

        String traceId = decodeTraceId(body.get("traceId"));
        if (traceId != null && !traceId.isBlank()) {
            rows.removeIf(row -> !traceId.equals(String.valueOf(row.get("trace_id"))));
        }

        String spanId = ServicePortalService.stringValue(body.get("spanId"), null);
        if (spanId != null) {
            rows.removeIf(row -> !spanId.equals(String.valueOf(row.get("span_id"))));
        }

        if (body.get("error") != null && !"".equals(String.valueOf(body.get("error")).trim())) {
            int error = ServicePortalService.intValue(body.get("error"), 0);
            rows.removeIf(row -> ServicePortalService.intValue(row.get("error"), 0) != error);
        }

        if (body.get("minDuration") != null && !"".equals(String.valueOf(body.get("minDuration")).trim())) {
            long minDuration = toLong(body.get("minDuration"));
            rows.removeIf(row -> toLong(row.get("duration")) < minDuration);
        }

        if (body.get("maxDuration") != null && !"".equals(String.valueOf(body.get("maxDuration")).trim())) {
            long maxDuration = toLong(body.get("maxDuration"));
            rows.removeIf(row -> toLong(row.get("duration")) > maxDuration);
        }

        String sortField = ServicePortalService.stringValue(body.get("sortField"), "start");
        String sortOrder = ServicePortalService.stringValue(body.get("sortOrder"), "desc");
        Comparator<Map<String, Object>> comparator = Comparator.comparing(
                row -> row.get(sortField),
                (left, right) -> compareValues(left, right, sortOrder));
        rows.sort(comparator);
        return rows;
    }

    private static int compareValues(Object left, Object right, String sortOrder) {
        if (Objects.equals(left, right)) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        int cmp;
        if (left instanceof Number ln && right instanceof Number rn) {
            cmp = Double.compare(ln.doubleValue(), rn.doubleValue());
        } else {
            cmp = String.valueOf(left).compareTo(String.valueOf(right));
        }
        return "asc".equalsIgnoreCase(sortOrder) ? cmp : -cmp;
    }

    private Map<String, Object> buildGraphResult(
            Map<String, Long> callCnts,
            Map<String, Map<String, Long>> errorCnts,
            Map<String, Double> sumDurationNs,
            long from,
            long to,
            int interval) {
        Map<String, Number> avgLatencys = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : callCnts.entrySet()) {
            long requestCount = entry.getValue();
            double sumDuration = sumDurationNs.getOrDefault(entry.getKey(), 0.0);
            if (requestCount > 0 && sumDuration > 0) {
                avgLatencys.put(entry.getKey(), sumDuration / requestCount);
            }
        }

        Map<String, Number> callCntSeries = new LinkedHashMap<>();
        callCnts.forEach((key, value) -> callCntSeries.put(key, value));

        Map<String, Object> graphs = new LinkedHashMap<>();
        graphs.put("callCnts", TimeSeriesFillUtil.fillStringKeyMap(callCntSeries, from, to, interval));
        graphs.put("errorCnts", TimeSeriesFillUtil.fillStringKeyObjectMap(errorCnts, from, to, interval));
        graphs.put("avgLatencys", TimeSeriesFillUtil.fillStringKeyMap(avgLatencys, from, to, interval));
        return graphs;
    }

    private static long percentile(List<Long> values, double ratio) {
        if (values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int index = Math.min(sorted.size() - 1, (int) Math.floor((sorted.size() - 1) * ratio));
        return sorted.get(index);
    }

    private static Map<String, Object> emptyGraphs(long from, long to, int interval) {
        Map<String, Object> graphs = new LinkedHashMap<>();
        graphs.put("callCnts", TimeSeriesFillUtil.fillStringKeyMap(null, from, to, interval));
        graphs.put("errorCnts", TimeSeriesFillUtil.fillStringKeyObjectMap(null, from, to, interval));
        graphs.put("avgLatencys", TimeSeriesFillUtil.fillStringKeyMap(null, from, to, interval));
        return graphs;
    }

    private static String resolveService(Map<String, Object> body) {
        String service = ServicePortalService.stringValue(body.get("serviceId"), null);
        if (service == null) {
            service = ServicePortalService.stringValue(body.get("serviceName"), null);
        }
        return service;
    }

    private static List<Map<String, Object>> paginate(List<Map<String, Object>> rows, int offset, int size) {
        if (rows.isEmpty()) {
            return List.of();
        }
        int safeSize = Math.max(1, size);
        int from = Math.min(Math.max(0, offset), rows.size());
        int to = Math.min(from + safeSize, rows.size());
        return rows.subList(from, to);
    }

    private static long parseStartMillis(String startTime) {
        if (startTime == null || startTime.isBlank()) {
            return 0L;
        }
        String text = startTime.trim();
        if (text.chars().allMatch(Character::isDigit)) {
            long n = Long.parseLong(text);
            return n < 1_000_000_000_000L ? n * 1000L : n;
        }
        try {
            return ApmTimeZones.wallClockToEpochMilli(text);
        } catch (DateTimeParseException ignored) {
            return Instant.parse(text).toEpochMilli();
        }
    }

    /** {@code trace_dc_span.duration} is stored in nanoseconds (see {@link MetricQueryBuilder}). */
    private static long spanDurationNs(long duration) {
        return Math.max(0L, duration);
    }

    private static long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String decodeTraceId(Object value) {
        String traceId = ServicePortalService.stringValue(value, null);
        if (traceId == null) {
            return null;
        }
        try {
            return URLDecoder.decode(traceId, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return traceId;
        }
    }

    private static boolean matchesHttpSpanUrl(String url, Map<String, Object> row) {
        if (url == null || url.isBlank()) {
            return true;
        }
        Object metaObj = row.get("meta");
        if (metaObj instanceof Map<?, ?> meta) {
            Object httpUrl = meta.get("http.url");
            if (httpUrl != null && !String.valueOf(httpUrl).isBlank()) {
                return url.equals(DcSpanUtil.normalizeHttpUrl(String.valueOf(httpUrl)));
            }
        }
        return String.valueOf(row.get("resource")).contains(url);
    }

    private static String decodeQueryValue(Object value) {
        String text = ServicePortalService.stringValue(value, null);
        if (text == null) {
            return null;
        }
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return text;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static Map<String, Object> emptyCallSpansEnvelope() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", List.of());
        response.put("status", 200);
        response.put("message", "SUCCESS");
        response.put("total", 0);
        return response;
    }

    private record CallSpanQuery(
            long from,
            long to,
            String fromTimeText,
            String toTimeText,
            String componentType,
            String serviceId,
            String serviceInstance,
            String srcServiceId,
            String srcServiceInstance,
            String dstServiceId,
            String dstServiceInstance,
            String resource,
            String httpMethod,
            String rootResourceQuery,
            boolean inbound,
            int offset,
            int size,
            String sortField,
            String sortOrder) {
    }

    private record CallSpanPage(List<Map<String, Object>> rows, long total) {
    }

    private static boolean isAllWebCall(
            MetaServicePoint srcMeta,
            MetaServicePoint dstMeta,
            String srcServiceId,
            String serviceId) {
        if (serviceId == null || serviceId.isBlank()) {
            return false;
        }
        if (dstMeta != null && Boolean.TRUE.equals(dstMeta.virtualService())) {
            return false;
        }
        if (srcServiceId == null || srcServiceId.isBlank()) {
            return true;
        }
        if (srcMeta == null) {
            return true;
        }
        return !Boolean.TRUE.equals(srcMeta.virtualService());
    }

    private MetaServicePoint loadMetaService(String serviceId) {
        if (serviceId == null || serviceId.isBlank()) {
            return null;
        }
        try {
            List<MetaServicePoint> rows = readRepository.queryMetaServices(
                    MetricQueryBuilder.metaServiceByIdSql(metricDatabase, serviceId));
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> queryVirtualCallSpans(
            Map<String, Object> response,
            long from,
            long to,
            String fromTimeText,
            String toTimeText,
            String componentType,
            String serviceId,
            String srcServiceId,
            String serviceInstance,
            String srcServiceInstance,
            String resource,
            String httpMethod,
            String rootResourceQuery,
            int offset,
            int size,
            String sortField,
            String sortOrder,
            boolean includeTotal) {
        try {
            // 组件调用（DB/Redis/MQ 等）：请求里的 serviceId 表示下游目标，对应 span.dstServiceId。
            List<CallSpanRow> spans = readRepository.queryCallSpans(MetricQueryBuilder.callSpanListSql(
                    traceDatabase, from, to, fromTimeText, toTimeText, null, null, srcServiceId, srcServiceInstance,
                    serviceId, serviceInstance, resource, httpMethod, rootResourceQuery, false, componentType,
                    sortField, sortOrder, size, offset));
            List<Map<String, Object>> rows = spans.stream()
                    .map(span -> toVirtualCallSpanRow(span, componentType))
                    .toList();
            long total = includeTotal
                    ? readRepository.queryCallSpanCount(MetricQueryBuilder.callSpanCountSql(
                            traceDatabase, from, to, fromTimeText, toTimeText, null, null,
                            srcServiceId, srcServiceInstance, serviceId, serviceInstance,
                            resource, httpMethod, rootResourceQuery, false, componentType))
                    : offset + rows.size();
            response.put("data", rows);
            response.put("total", total);
        } catch (Exception ignored) {
            // keep empty envelope
        }
        return response;
    }

    private CallSpanPage queryWebCallSpans(
            CallSpanQuery query, String dstServiceId, boolean clientFirst, boolean includeTotal) {
        try {
            Boolean inbound = clientFirst ? false : true;
            List<CallSpanRow> spans = readRepository.queryCallSpans(MetricQueryBuilder.callSpanListSql(
                    traceDatabase,
                    query.from(),
                    query.to(),
                    query.fromTimeText(),
                    query.toTimeText(),
                    query.serviceId(),
                    query.serviceInstance(),
                    query.srcServiceId(),
                    query.srcServiceInstance(),
                    query.dstServiceId(),
                    query.dstServiceInstance(),
                    query.resource(),
                    query.httpMethod(),
                    query.rootResourceQuery(),
                    inbound,
                    query.componentType(),
                    query.sortField(),
                    query.sortOrder(),
                    query.size(),
                    query.offset()));
            List<Map<String, Object>> rows = clientFirst
                    ? buildClientFirstRows(spans, query, dstServiceId)
                    : buildServerFirstRows(spans, query.componentType());
            long total = includeTotal
                    ? readRepository.queryCallSpanCount(MetricQueryBuilder.callSpanCountSql(
                            traceDatabase,
                            query.from(),
                            query.to(),
                            query.fromTimeText(),
                            query.toTimeText(),
                            query.serviceId(),
                            query.serviceInstance(),
                            query.srcServiceId(),
                            query.srcServiceInstance(),
                            query.dstServiceId(),
                            query.dstServiceInstance(),
                            query.resource(),
                            query.httpMethod(),
                            query.rootResourceQuery(),
                            inbound,
                            query.componentType()))
                    : query.offset() + rows.size();
            return new CallSpanPage(rows, total);
        } catch (Exception e) {
            return new CallSpanPage(List.of(), 0);
        }
    }

    private List<Map<String, Object>> buildClientFirstRows(
            List<CallSpanRow> clientSpans, CallSpanQuery query, String dstServiceId) {
        if (clientSpans.isEmpty()) {
            return List.of();
        }
        List<String> parentIds = clientSpans.stream().map(CallSpanRow::spanId).toList();
        String serverService = query.dstServiceId() != null && !query.dstServiceId().isBlank()
                ? query.dstServiceId()
                : dstServiceId;
        List<CallSpanRow> serverSpans;
        try {
            serverSpans = readRepository.queryCallSpans(MetricQueryBuilder.callSpanChildrenSql(
                    traceDatabase, query.from(), query.to(), query.fromTimeText(), query.toTimeText(),
                    serverService, parentIds));
        } catch (Exception e) {
            serverSpans = List.of();
        }
        Map<String, CallSpanRow> serverByParent = serverSpans.stream()
                .collect(Collectors.toMap(CallSpanRow::parentId, Function.identity(), (left, right) -> left));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (CallSpanRow clientSpan : clientSpans) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("start", clientSpan.start());
            row.put("end", clientSpan.end());
            row.put("resource", displayResource(clientSpan));
            row.put("trace_id", clientSpan.traceId());
            row.put("componentType", query.componentType());
            row.put("client", toCallSide(clientSpan, query.componentType()));
            CallSpanRow serverSpan = serverByParent.get(clientSpan.spanId());
            if (serverSpan != null) {
                row.put("server", toCallSide(serverSpan, query.componentType()));
            }
            applyComponentMeta(query.componentType(), clientSpan, row);
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> buildServerFirstRows(List<CallSpanRow> serverSpans, String componentType) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CallSpanRow serverSpan : serverSpans) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("start", serverSpan.start());
            row.put("end", serverSpan.end());
            row.put("resource", displayResource(serverSpan));
            row.put("trace_id", serverSpan.traceId());
            row.put("componentType", componentType);
            row.put("server", toCallSide(serverSpan, componentType));
            applyComponentMeta(componentType, serverSpan, row);
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> toVirtualCallSpanRow(CallSpanRow span, String componentType) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("start", span.start());
        row.put("end", span.end());
        row.put("resource", displayResource(span));
        row.put("trace_id", span.traceId());
        row.put("componentType", componentType);
        Map<String, Object> side = toCallSide(span, componentType);
        row.put("client", side);
        row.put("server", new LinkedHashMap<>(side));
        applyComponentMeta(componentType, span, row);
        return row;
    }

    private static CallSpanQuery webCallClientQuery(
            long from,
            long to,
            String fromTimeText,
            String toTimeText,
            String componentType,
            String serviceId,
            String srcServiceId,
            String serviceInstance,
            String srcServiceInstance,
            String resource,
            String httpMethod,
            String rootResourceQuery,
            int offset,
            int size,
            String sortField,
            String sortOrder) {
        return new CallSpanQuery(
                from,
                to,
                fromTimeText,
                toTimeText,
                componentType,
                srcServiceId,
                srcServiceInstance,
                null,
                null,
                serviceId,
                serviceInstance,
                resource,
                httpMethod,
                rootResourceQuery,
                false,
                offset,
                size,
                sortField,
                sortOrder);
    }

    private static CallSpanQuery webCallServerQuery(
            long from,
            long to,
            String fromTimeText,
            String toTimeText,
            String componentType,
            String serviceId,
            String srcServiceId,
            String serviceInstance,
            String srcServiceInstance,
            String resource,
            String httpMethod,
            String rootResourceQuery,
            int offset,
            int size,
            String sortField,
            String sortOrder) {
        return new CallSpanQuery(
                from,
                to,
                fromTimeText,
                toTimeText,
                componentType,
                serviceId,
                serviceInstance,
                srcServiceId,
                srcServiceInstance,
                null,
                null,
                resource,
                httpMethod,
                rootResourceQuery,
                true,
                offset,
                size,
                sortField,
                sortOrder);
    }

    private Map<String, Object> toCallSide(CallSpanRow span, String componentType) {
        Map<String, Object> side = new LinkedHashMap<>();
        side.put("duration", span.duration());
        side.put("error", resolveCallError(span));
        side.put("span_id", span.spanId());
        side.put("parent_id", span.parentId());
        Map<String, String> meta = OtelAttributeMaps.parse(span.meta());
        if ("service.http".equals(componentType)) {
            Integer statusCode = span.metaHttpStatusCode();
            if (statusCode != null) {
                side.put("statusCode", statusCode);
            }
        }
        if ("service.rpc".equals(componentType)) {
            String threadName = OtelAttributeMaps.firstNonBlank(meta, "thread.name");
            if (threadName != null) {
                side.put("threadName", threadName);
            }
        }
        return side;
    }

    private static int resolveCallError(CallSpanRow span) {
        if (span.error() > 0) {
            return 1;
        }
        if (span.slow() > 0) {
            return 2;
        }
        return 0;
    }

    private static String displayResource(CallSpanRow span) {
        if (span.resource() != null && !span.resource().isBlank()) {
            return span.resource();
        }
        return span.name();
    }

    @SuppressWarnings("unchecked")
    private void applyComponentMeta(String componentType, CallSpanRow span, Map<String, Object> row) {
        Map<String, String> meta = OtelAttributeMaps.parse(span.meta());
        Map<String, String> metrics = OtelAttributeMaps.parse(span.metrics());
        Map<String, Object> server = (Map<String, Object>) row.get("server");
        Map<String, Object> client = (Map<String, Object>) row.get("client");

        switch (componentType) {
            case "service.mq" -> {
                row.put("topic", nullToEmpty(OtelAttributeMaps.firstNonBlank(
                        meta, "messaging.destination.name", "messaging.kafka.destination")));
                row.put("broker", nullToEmpty(OtelAttributeMaps.firstNonBlank(
                        meta, "net.peer.name", "server.address", "messaging.kafka.broker")));
                row.put("group", nullToEmpty(OtelAttributeMaps.firstNonBlank(
                        meta, "messaging.kafka.consumer.group", "messaging.consumer.group")));
                row.put("type", nullToEmpty(OtelAttributeMaps.firstNonBlank(meta, "messaging.system", "component")));
                row.put("partition", nullToEmpty(OtelAttributeMaps.firstNonBlank(meta, "messaging.kafka.partition")));
                row.put("delay", metrics.getOrDefault("record.e2e.duration.ns", metrics.getOrDefault("delay", "")));
                row.put("bodyLength", metrics.getOrDefault("messaging.message.payload.size_bytes", ""));
            }
            case "service.db" -> {
                row.put("dbType", nullToEmpty(OtelAttributeMaps.firstNonBlank(meta, "db.system", "db.type")));
                String dbPort = OtelAttributeMaps.firstNonBlank(meta, "net.peer.port", "db.port");
                if (dbPort != null) {
                    row.put("dbPort", dbPort);
                }
                row.put("sqlDatabase", nullToEmpty(OtelAttributeMaps.firstNonBlank(
                        meta, "db.name", "db.elasticsearch.index", "elasticsearch.index")));
                String sqlOperation = OtelAttributeMaps.firstNonBlank(meta, "db.operation", "http.method");
                if (sqlOperation != null) {
                    row.put("sqlOperation", DcSpanUtil.normalizeSqlOperation(sqlOperation));
                }
                row.put("updateRows", metrics.getOrDefault("db.response.returned_rows", metrics.getOrDefault("db.update.rows", "")));
                row.put("returnRows", metrics.getOrDefault("db.response.returned_rows", metrics.getOrDefault("db.select.return.rows", "")));
            }
            case "service.http" -> {
                String httpMethod = span.metaHttpMethod();
                if (httpMethod == null || httpMethod.isBlank()) {
                    httpMethod = OtelAttributeMaps.firstNonBlank(meta, "http.method");
                }
                row.put("httpMethod", nullToEmpty(httpMethod));
                String url = span.metaHttpUrl();
                if (url == null || url.isBlank()) {
                    url = OtelAttributeMaps.firstNonBlank(meta, "http.route", "http.url", "url.full");
                }
                if (url == null || url.isBlank()) {
                    String resource = String.valueOf(row.getOrDefault("resource", ""));
                    if (httpMethod != null && !httpMethod.isBlank() && resource.startsWith(httpMethod + " ")) {
                        url = resource.substring(httpMethod.length() + 1);
                    } else if (resource.contains(" ")) {
                        url = resource.substring(resource.indexOf(' ') + 1);
                    } else {
                        url = resource;
                    }
                } else {
                    url = DcSpanUtil.normalizeHttpUrl(url);
                }
                row.put("url", nullToEmpty(url));
                if (span.metaHttpStatusCode() != null) {
                    if (server != null) {
                        server.put("statusCode", span.metaHttpStatusCode());
                    }
                    if (client != null) {
                        client.put("statusCode", span.metaHttpStatusCode());
                    }
                }
            }
            case "service.rpc" -> {
                String threadName = OtelAttributeMaps.firstNonBlank(meta, "thread.name");
                if (threadName != null) {
                    if (server != null) {
                        server.put("threadName", threadName);
                    }
                    if (client != null) {
                        client.put("threadName", threadName);
                    }
                }
            }
            default -> {
            }
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
