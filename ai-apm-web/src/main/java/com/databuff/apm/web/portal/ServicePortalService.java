package com.databuff.apm.web.portal;

import com.databuff.apm.common.util.PortalServiceIdResolver;
import com.databuff.apm.common.query.ApmQueryModels.ComponentCallStatsPoint;
import com.databuff.apm.common.query.ApmQueryModels.ComponentEndpointPoint;
import com.databuff.apm.common.query.ApmQueryModels.ComponentResourceRelationPoint;
import com.databuff.apm.common.query.ApmQueryModels.ExceptionDistPoint;
import com.databuff.apm.common.query.ApmQueryModels.ServiceInstanceSummaryPoint;
import com.databuff.apm.common.query.ApmQueryModels.MetaServicePoint;
import com.databuff.apm.common.query.ApmQueryModels.DbDownstreamPoint;
import com.databuff.apm.common.query.ApmQueryModels.DbEndpointPoint;
import com.databuff.apm.common.query.ApmQueryModels.DbSlowSqlTopPoint;
import com.databuff.apm.common.query.ApmQueryModels.HttpEndpointPoint;
import com.databuff.apm.common.query.ApmQueryModels.HttpLatencyBucketPoint;
import com.databuff.apm.common.query.ApmQueryModels.MetricSeriesPoint;
import com.databuff.apm.common.query.ApmQueryModels.DbServiceSummaryPoint;
import com.databuff.apm.common.query.ApmQueryModels.ServiceSummaryPoint;
import com.databuff.apm.common.query.ApmQueryModels.ComponentTrendBucketPoint;
import com.databuff.apm.common.query.ApmQueryModels.ServiceTrendBucketPoint;
import com.databuff.apm.common.query.ApmQueryModels.TopologyEdge;
import com.databuff.apm.common.query.TimeSeriesFillUtil;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.common.storage.DorisTableNames;
import com.databuff.apm.common.storage.MetricIdentifierParser;
import com.databuff.apm.common.storage.MetricQueryBuilder;
import com.databuff.apm.web.config.ApmStorageProperties;
import com.databuff.apm.web.config.common.CommonResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ServicePortalService {

    private static final Pattern DB_PATTERN = Pattern.compile(
            "mysql|mariadb|postgres|oracle|mongo|sqlserver|clickhouse|doris|jdbc|database|elasticsearch|elastic|\\bdb\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MQ_PATTERN = Pattern.compile(
            "kafka|rabbit|rocket|pulsar|activemq|ons|\\bmq\\b|nats|topic",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CACHE_PATTERN = Pattern.compile(
            "redis|memcached|cache|ehcache|caffeine|hazelcast",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REMOTE_PATTERN = Pattern.compile(
            "gateway|external|third|remote|openapi|feign|dubbo",
            Pattern.CASE_INSENSITIVE);
    /** Portal 调用分析 RequestTypeMapping 可下钻的组件；不含 trace/flow/jvm 等。 */
    private static final List<String> SERVICE_COMPONENT_TYPES = List.of(
            "service.http",
            "service.db",
            "service.rpc",
            "service.mq",
            "service.redis",
            "service.config",
            "service.remote");

    private final ApmReadRepository readRepository;
    private final String metricDatabase;
    private final GlobalTopologyQueryService globalTopologyQueryService;

    public ServicePortalService(
            ApmReadRepository readRepository,
            ApmStorageProperties storageProperties,
            GlobalTopologyQueryService globalTopologyQueryService) {
        this.readRepository = readRepository;
        this.metricDatabase = storageProperties.metricDatabase();
        this.globalTopologyQueryService = globalTopologyQueryService;
    }

    public List<Map<String, Object>> serviceListTrendChart(Map<String, Object> body) {
        return buildServiceSeries(body, null, null, intValue(body.get("limit"), 10), true);
    }

    public List<Map<String, Object>> serviceDetailTrendChart(Map<String, Object> body) {
        String serviceId = resolveServiceId(body);
        if (serviceId == null) {
            return List.of();
        }
        String serviceInstance = stringValue(body.get("serviceInstance"), null);
        return buildServiceSeries(body, serviceId, serviceInstance, 1, false);
    }

    public Map<String, Object> graphStats(Map<String, Object> body) {
        String componentType = stringValue(body.get("componentType"), "service.http");
        ComponentPeerSpec spec = findComponentSpec(componentType);
        if (spec == null) {
            return Map.of();
        }
        if ("service.http".equals(componentType)) {
            long now = System.currentTimeMillis();
            long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
            long to = PortalTimeParser.rangeTo(body, now);
            int interval = intValue(body.get("interval"), 60);
            String serviceId = resolveServiceId(body);
            String serviceInstance = stringValue(body.get("serviceInstance"), null);
            String url = decodeUrl(body);
            boolean exactUrl = url != null && !url.isBlank();
            String resourceQuery = exactUrl ? url : stringValue(body.get("resourceQuery"), null);
            if (resourceQuery == null) {
                resourceQuery = decodeResource(body);
            }
            Integer isIn = parseOptionalFlag(body.get("isIn"));
            Integer isOut = parseOptionalFlag(body.get("isOut"));
            String srcServiceId = stringValue(body.get("srcServiceId"), null);
            return buildHttpGraphStats(
                    from, to, interval, serviceId, serviceInstance, resourceQuery, exactUrl, isIn, isOut, srcServiceId);
        }
        if (spec.webPeer()) {
            return componentGraphStats(body, spec.tableName());
        }
        return componentGraphStats(normalizeOutboundGraphStatsBody(body), spec.tableName());
    }

    private Map<String, Object> buildHttpGraphStats(
            long from,
            long to,
            int interval,
            String serviceId,
            String serviceInstance,
            String resourceQuery,
            boolean exactUrlMatch,
            Integer isIn,
            Integer isOut,
            String srcServiceId) {
        Set<String> serviceKeys = metricServiceIdKeys(serviceId);
        Set<String> srcKeys = metricServiceIdKeys(srcServiceId);

        List<ServiceTrendBucketPoint> buckets;
        try {
            String sql = MetricQueryBuilder.httpTrendBucketsSql(
                    metricDatabase, from, to, interval, serviceKeys, serviceInstance, resourceQuery,
                    isIn, isOut, srcKeys, exactUrlMatch);
            buckets = readRepository.queryServiceTrendBuckets(sql);
        } catch (Exception e) {
            return Map.of();
        }

        Map<String, Number> callCnts = new LinkedHashMap<>();
        Map<String, Number> errorCnts = new LinkedHashMap<>();
        Map<String, Number> avgLatencys = new LinkedHashMap<>();
        Map<String, Number> errorRates = new LinkedHashMap<>();

        for (ServiceTrendBucketPoint bucket : buckets) {
            String key = String.valueOf(bucket.bucketEpochSec() * 1000L);
            long req = bucket.requestCount();
            long err = bucket.errorCount();
            callCnts.merge(key, req, (a, b) -> a.longValue() + b.longValue());
            errorCnts.merge(key, err, (a, b) -> a.longValue() + b.longValue());
            if (req > 0) {
                double avgNs = bucket.sumDurationNs() / req;
                avgLatencys.put(key, Math.max(avgLatencys.getOrDefault(key, 0).doubleValue(), avgNs));
            }
        }

        callCnts.forEach((key, total) -> {
            long totalLong = total.longValue();
            long err = errorCnts.getOrDefault(key, 0).longValue();
            errorRates.put(key, totalLong > 0 ? 100.0 * err / totalLong : 0);
        });

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("avgLatencys", TimeSeriesFillUtil.fillStringKeyMap(avgLatencys, from, to, interval));
        data.put("callCnts", TimeSeriesFillUtil.fillStringKeyMap(callCnts, from, to, interval));
        data.put("errorCnts", TimeSeriesFillUtil.fillStringKeyMap(errorCnts, from, to, interval));
        data.put("errorRates", TimeSeriesFillUtil.fillStringKeyMap(errorRates, from, to, interval));
        data.put("details", Map.of());
        return data;
    }

    /**
     * Service detail SQL tab passes {@code serviceId=app} + {@code isIn=1}; after service.db tag semantics
     * changed to {@code service=db, srcService=app}, translate that to a caller-scoped query.
     * Database detail passes {@code dbTarget=1} to keep {@code serviceId} as the downstream DB target.
     */
    private static Map<String, Object> normalizeDbGraphStatsBody(Map<String, Object> body) {
        Map<String, Object> params = new LinkedHashMap<>(body);
        if (isDbTargetFilter(body)) {
            return params;
        }
        String serviceId = resolveServiceId(body);
        String srcServiceId = stringValue(body.get("srcServiceId"), null);
        Integer isIn = parseOptionalFlag(body.get("isIn"));
        Integer isOut = parseOptionalFlag(body.get("isOut"));
        if ((srcServiceId == null || srcServiceId.isBlank())
                && serviceId != null
                && !serviceId.isBlank()
                && isIn != null
                && isIn == 1
                && (isOut == null || isOut == 0)) {
            params.put("srcServiceId", serviceId);
            params.put("serviceId", "");
            params.put("sid", "");
            params.remove("isIn");
            params.put("isOut", 1);
        }
        return params;
    }

    private Map<String, Object> componentGraphStats(Map<String, Object> body, String table) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        int interval = intValue(body.get("interval"), 60);
        String serviceId = resolveServiceId(body);
        String srcServiceId = stringValue(body.get("srcServiceId"), null);
        String serviceInstance = stringValue(body.get("serviceInstance"), null);
        String resourceQuery = decodeResource(body);
        if (resourceQuery == null) {
            resourceQuery = stringValue(body.get("resourceQuery"), null);
        }
        Integer isIn = parseOptionalFlag(body.get("isIn"));
        Integer isOut = parseOptionalFlag(body.get("isOut"));
        Integer isSlow = parseOptionalFlag(body.get("isSlow"));

        Set<String> serviceKeys = metricServiceIdKeys(serviceId);
        Set<String> srcKeys = metricServiceIdKeys(srcServiceId);
        if (DorisTableNames.METRIC_SERVICE_DB.equals(table)
                && isOut != null
                && isOut == 1
                && (isIn == null || isIn == 0)
                && srcKeys.isEmpty()
                && !serviceKeys.isEmpty()) {
            srcKeys.addAll(serviceKeys);
        }

        List<ComponentTrendBucketPoint> buckets;
        try {
            String sql = MetricQueryBuilder.componentTrendBucketsSql(
                    metricDatabase,
                    table,
                    from,
                    to,
                    interval,
                    serviceKeys,
                    serviceInstance,
                    resourceQuery,
                    isIn,
                    isOut,
                    isSlow,
                    srcKeys);
            buckets = readRepository.queryComponentTrendBuckets(sql);
        } catch (Exception e) {
            return Map.of();
        }

        Map<String, Number> callCnts = new LinkedHashMap<>();
        Map<String, Number> errorCnts = new LinkedHashMap<>();
        Map<String, Number> avgLatencys = new LinkedHashMap<>();
        Map<String, Number> maxLatencys = new LinkedHashMap<>();
        Map<String, Number> minLatencys = new LinkedHashMap<>();
        Map<String, Number> errorRates = new LinkedHashMap<>();
        Map<String, Number> avgReadRows = new LinkedHashMap<>();
        Map<String, Number> avgUpdateRows = new LinkedHashMap<>();

        for (ComponentTrendBucketPoint bucket : buckets) {
            String key = String.valueOf(bucket.bucketEpochSec() * 1000L);
            long req = bucket.requestCount();
            long err = bucket.errorCount();
            callCnts.merge(key, req, (a, b) -> a.longValue() + b.longValue());
            errorCnts.merge(key, err, (a, b) -> a.longValue() + b.longValue());
            if (req > 0) {
                avgLatencys.put(key, bucket.sumDurationNs() / req);
                avgReadRows.put(key, bucket.sumReadRows() / req);
                avgUpdateRows.put(key, bucket.sumUpdateRows() / req);
            }
            if (bucket.maxDurationNs() > 0) {
                maxLatencys.merge(key, bucket.maxDurationNs(), (a, b) ->
                        Math.max(a.doubleValue(), b.doubleValue()));
            }
            if (bucket.minDurationNs() > 0) {
                minLatencys.merge(key, bucket.minDurationNs(), (a, b) ->
                        Math.min(a.doubleValue(), b.doubleValue()));
            }
        }

        callCnts.forEach((key, total) -> {
            long totalLong = total.longValue();
            long err = errorCnts.getOrDefault(key, 0).longValue();
            errorRates.put(key, totalLong > 0 ? 100.0 * err / totalLong : 0);
        });

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("avgLatencys", TimeSeriesFillUtil.fillStringKeyMap(avgLatencys, from, to, interval));
        data.put("maxLatencys", TimeSeriesFillUtil.fillStringKeyMap(maxLatencys, from, to, interval));
        data.put("minLatencys", TimeSeriesFillUtil.fillStringKeyMap(minLatencys, from, to, interval));
        data.put("callCnts", TimeSeriesFillUtil.fillStringKeyMap(callCnts, from, to, interval));
        data.put("errorCnts", TimeSeriesFillUtil.fillStringKeyMap(errorCnts, from, to, interval));
        data.put("errorRates", TimeSeriesFillUtil.fillStringKeyMap(errorRates, from, to, interval));
        data.put("avgReadRows", TimeSeriesFillUtil.fillStringKeyMap(avgReadRows, from, to, interval));
        data.put("avgUpdateRows", TimeSeriesFillUtil.fillStringKeyMap(avgUpdateRows, from, to, interval));
        data.put("details", Map.of());
        return data;
    }

    private static boolean isDbTargetFilter(Map<String, Object> body) {
        Object flag = body.get("dbTarget");
        if (flag == null) {
            return false;
        }
        if (flag instanceof Boolean enabled) {
            return enabled;
        }
        String text = String.valueOf(flag).trim();
        return "1".equals(text) || "true".equalsIgnoreCase(text);
    }

    private static Integer parseOptionalFlag(Object value) {
        if (value == null || "".equals(String.valueOf(value).trim())) {
            return null;
        }
        return intValue(value, 0) > 0 ? 1 : 0;
    }

    public Map<String, Object> callGraphStats(Map<String, Object> body) {
        String srcServiceId = stringValue(body.get("srcServiceId"), null);
        String serviceId = resolveServiceId(body);
        String url = decodeUrl(body);

        Map<String, Object> base = new LinkedHashMap<>(body);
        base.put("componentType", stringValue(body.get("componentType"), "service.http"));
        if (url != null && !url.isBlank()) {
            base.put("url", url);
        }

        Map<String, Object> inParams = new LinkedHashMap<>(base);
        if (serviceId != null) {
            inParams.put("serviceId", serviceId);
            inParams.put("sid", serviceId);
        }
        if (srcServiceId != null && !srcServiceId.isBlank()) {
            inParams.put("srcServiceId", srcServiceId);
        }
        inParams.put("isIn", 1);

        boolean requestOut = intValue(body.get("isOut"), 0) == 1;
        boolean requestIn = intValue(body.get("isIn"), 0) == 1;
        if (requestOut && !requestIn) {
            return outboundGraphStats(base, srcServiceId, serviceId);
        }
        if (requestIn && !requestOut) {
            return graphStats(inParams);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("isIn", graphStats(inParams));
        result.put("isOut", outboundGraphStats(base, srcServiceId, serviceId));
        return result;
    }

    private static Map<String, Object> normalizeOutboundGraphStatsBody(Map<String, Object> body) {
        return normalizeDbGraphStatsBody(body);
    }

    private Map<String, Object> outboundGraphStats(
            Map<String, Object> base, String srcServiceId, String dstServiceId) {
        if (srcServiceId == null || srcServiceId.isBlank()) {
            return Map.of();
        }
        String componentType = stringValue(base.get("componentType"), "service.http");
        ComponentPeerSpec spec = findComponentSpec(componentType);
        if (spec == null) {
            return Map.of();
        }
        if (spec.webPeer()) {
            return webPeerOutboundGraphStats(base, dstServiceId, srcServiceId);
        }
        Map<String, Object> params = new LinkedHashMap<>(base);
        if (spec.outboundIsOut() != null) {
            params.put("isOut", spec.outboundIsOut());
        } else {
            params.put("isOut", 1);
        }
        if (spec.outboundIsIn() != null) {
            params.put("isIn", spec.outboundIsIn());
        }
        params.put("srcServiceId", srcServiceId);
        if (dstServiceId != null && !dstServiceId.isBlank()) {
            params.put("serviceId", dstServiceId);
            params.put("sid", dstServiceId);
        }
        return componentGraphStats(normalizeOutboundGraphStatsBody(params), spec.tableName());
    }

    private static Map<String, Object> withServiceId(Map<String, Object> base, String serviceId) {
        Map<String, Object> params = new LinkedHashMap<>(base);
        params.put("serviceId", serviceId);
        params.put("sid", serviceId);
        return params;
    }

    public Map<String, Object> middlewareList(Map<String, Object> body) {
        String kind = stringValue(body.get("kind"), "db");
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        int size = intValue(body.get("size"), intValue(body.get("pageSize"), 50));
        int offset = intValue(body.get("offset"), 0);
        int pageNum = intValue(body.get("pageNum"), 0);
        if (pageNum > 0) {
            offset = (pageNum - 1) * size;
        }

        List<Map<String, Object>> rows = filterMiddlewareRows(body, kind, from, to);
        String serviceName = stringValue(body.get("serviceName"), null);
        if (serviceName != null && !serviceName.isBlank()) {
            String keyword = serviceName.toLowerCase(Locale.ROOT);
            rows = rows.stream()
                    .filter(row -> String.valueOf(row.get("name")).toLowerCase(Locale.ROOT).contains(keyword))
                    .toList();
        }

        int total = rows.size();
        int end = Math.min(offset + size, total);
        List<Map<String, Object>> page = offset >= total ? List.of() : rows.subList(offset, end);
        return listEnvelope(page, total, offset, page.size());
    }

    /**
     * Database list for portal {@code POST /service/dbList}.
     * Response shape matches legacy portal API ({@code ServiceV2ServiceImpl.dbList}).
     */
    public Map<String, Object> dbList(Map<String, Object> body) {
        return virtualServiceList(body, VirtualServiceKind.DB);
    }

    /** MQ list for portal {@code POST /service/mqList}. */
    public Map<String, Object> mqList(Map<String, Object> body) {
        return virtualServiceList(body, VirtualServiceKind.MQ);
    }

    /** Cache list for portal {@code POST /service/cacheList}. */
    public Map<String, Object> cacheList(Map<String, Object> body) {
        return virtualServiceList(body, VirtualServiceKind.CACHE);
    }

    /** External service list for portal {@code POST /service/remoteCallList}. */
    public Map<String, Object> remoteCallList(Map<String, Object> body) {
        return virtualServiceList(body, VirtualServiceKind.REMOTE);
    }

    private enum VirtualServiceKind {
        DB("db", "db", DorisTableNames.METRIC_SERVICE_DB, "dbType", "mysql", false),
        MQ("mq", "mq", DorisTableNames.METRIC_SERVICE_MQ, "type", "kafka", true),
        CACHE("cache", "cache", DorisTableNames.METRIC_SERVICE_REDIS, "command", "redis", false),
        REMOTE("remote", "custom", DorisTableNames.METRIC_SERVICE_REMOTE, "remoteType", "custom", false);

        private final String catalogServiceType;
        private final String rowServiceType;
        private final String metricTable;
        private final String typeColumn;
        private final String defaultTypeIcon;
        private final boolean mqDualSide;

        VirtualServiceKind(
                String catalogServiceType,
                String rowServiceType,
                String metricTable,
                String typeColumn,
                String defaultTypeIcon,
                boolean mqDualSide) {
            this.catalogServiceType = catalogServiceType;
            this.rowServiceType = rowServiceType;
            this.metricTable = metricTable;
            this.typeColumn = typeColumn;
            this.defaultTypeIcon = defaultTypeIcon;
            this.mqDualSide = mqDualSide;
        }
    }

    private Map<String, Object> virtualServiceList(Map<String, Object> body, VirtualServiceKind kind) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        int offset = intValue(body.get("offset"), 0);
        int size = intValue(body.get("size"), intValue(body.get("pageSize"), 50));
        int pageNum = intValue(body.get("pageNum"), 0);
        if (pageNum > 0) {
            offset = (pageNum - 1) * size;
        }
        String sortField = stringValue(body.get("sortField"), defaultSortField(kind));
        String sortOrder = stringValue(body.get("sortOrder"), "desc");
        String serviceName = stringValue(body.get("serviceName"), null);
        List<String> serviceIds = parseStringList(body.get("serviceIds"));
        Integer statusType = parseStatusType(body.get("statusType"));

        Map<String, DbServiceSummaryPoint> metricsByService = loadVirtualServiceMetrics(kind, from, to);
        Map<String, DbServiceSummaryPoint> mqConsumerMetrics = kind.mqDualSide
                ? loadMqConsumerMetrics(from, to)
                : Map.of();
        List<Map<String, Object>> catalogRows = loadVirtualServiceCatalogRows(body, kind, from, to);
        double durationSec = Math.max(1.0, (to - from) / 1000.0);

        LinkedHashMap<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> catalog : catalogRows) {
            String name = stringValue(catalog.get("name"), stringValue(catalog.get("service"), ""));
            if (name.isBlank()) {
                continue;
            }
            String serviceId = stringValue(catalog.get("id"), stringValue(catalog.get("serviceId"), name));
            DbServiceSummaryPoint metrics = resolveVirtualServiceMetrics(metricsByService, name, serviceId);
            DbServiceSummaryPoint consumer = resolveVirtualServiceMetrics(mqConsumerMetrics, name, serviceId);
            Map<String, Object> row = toVirtualServiceListRow(kind, catalog, metrics, consumer, durationSec);
            merged.put(virtualServiceRowKey(row), row);
        }
        for (DbServiceSummaryPoint metrics : uniqueVirtualServiceMetrics(metricsByService).values()) {
            String key = virtualServiceRowKey(metrics.service(), metrics.serviceId());
            if (merged.containsKey(key)) {
                continue;
            }
            DbServiceSummaryPoint consumer = resolveVirtualServiceMetrics(
                    mqConsumerMetrics, metrics.service(), metrics.serviceId());
            merged.put(key, toVirtualServiceListRowFromMetrics(kind, metrics, consumer, durationSec));
        }

        List<Map<String, Object>> rows = merged.values().stream()
                .filter(row -> matchesVirtualServiceListFilters(row, serviceName, serviceIds, statusType))
                .filter(row -> matchesVirtualServiceKind(kind, row))
                .collect(Collectors.toCollection(ArrayList::new));
        sortEndpointRows(rows, sortField, sortOrder);

        int total = rows.size();
        int end = Math.min(offset + size, total);
        List<Map<String, Object>> page = offset >= total ? List.of() : rows.subList(offset, end);
        return listEnvelope(page, total, offset, page.size());
    }

    private static String defaultSortField(VirtualServiceKind kind) {
        return kind == VirtualServiceKind.MQ ? "reqInCallCnt" : "callCnt";
    }

    public Map<String, Object> relationList(Map<String, Object> body) {
        String serviceId = resolveServiceId(body);
        if (serviceId == null || serviceId.isBlank()) {
            return Map.of("list", List.of(), "total", 0);
        }
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        String direction = stringValue(body.get("direction"), "next");
        boolean prev = "prev".equalsIgnoreCase(direction);

        List<Map<String, Object>> list = loadTopologyEdges(from, to, 500).stream()
                .filter(edge -> prev
                        ? PortalServiceIdResolver.matches(serviceId, edge.dstService())
                        : PortalServiceIdResolver.matches(serviceId, edge.srcService()))
                .map(edge -> {
                    String peer = prev ? edge.srcService() : edge.dstService();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("service", peer);
                    row.put("serviceId", PortalServiceIdResolver.normalize(peer));
                    row.put("callCnt", edge.callCount());
                    row.put("errCnt", edge.errorCount());
                    row.put("errRate", edge.callCount() > 0 ? (double) edge.errorCount() / edge.callCount() : 0);
                    row.put("avgLatency", 0);
                    row.put("type", "default");
                    row.put("typeIcon", "default");
                    row.put("hostName", "-");
                    row.put("hostLimit", false);
                    row.put("hasData", false);
                    row.put("overallScore", null);
                    return row;
                })
                .toList();
        return Map.of("list", list, "total", list.size());
    }

    public Map<String, Object> endpoints(Map<String, Object> body) {
        String componentType = stringValue(body.get("componentType"), "service.http");
        if ("service.http".equals(componentType)) {
            return httpEndpoints(body);
        }
        if ("service.db".equals(componentType)) {
            return dbEndpoints(body);
        }
        ComponentPeerSpec spec = findComponentSpec(componentType);
        if (spec == null) {
            return emptyEndpointsEnvelope();
        }
        return componentEndpoints(body, spec);
    }

    private Map<String, Object> httpEndpoints(Map<String, Object> body) {
        Map<String, Object> envelope = emptyEndpointsEnvelope();

        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        int offset = intValue(body.get("offset"), 0);
        int size = intValue(body.get("size"), 50);
        String sortField = stringValue(body.get("sortField"), "callCnt");
        String sortOrder = stringValue(body.get("sortOrder"), "desc");
        String serviceId = resolveServiceId(body);
        String srcServiceId = stringValue(body.get("srcServiceId"), null);
        String resourceQuery = resolveResourceQuery(body);
        Integer isIn = parseOptionalFlag(body.get("isIn"));
        Integer isOut = parseOptionalFlag(body.get("isOut"));

        List<HttpEndpointPoint> points = loadHttpEndpoints(
                serviceId, from, to, resourceQuery, 500, isIn, isOut, srcServiceId);
        if (points.isEmpty() && isIn != null && isOut == null) {
            points = loadHttpEndpoints(serviceId, from, to, resourceQuery, 500, null, null, srcServiceId);
        }

        double durationSec = Math.max(1.0, (to - from) / 1000.0);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (HttpEndpointPoint point : points) {
            rows.add(toEndpointRow(point, durationSec));
        }

        sortEndpointRows(rows, sortField, sortOrder);
        int total = rows.size();
        List<Map<String, Object>> page = rows.subList(
                Math.min(offset, total),
                Math.min(offset + Math.max(1, size), total));

        envelope.put("total", total);
        envelope.put("size", page.size());
        envelope.put("offset", offset + page.size());
        envelope.put("data", page);
        return envelope;
    }

    private Map<String, Object> componentEndpoints(Map<String, Object> body, ComponentPeerSpec spec) {
        Map<String, Object> envelope = emptyEndpointsEnvelope();
        Map<String, Object> params = spec.webPeer() ? body : normalizeOutboundGraphStatsBody(body);

        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(params, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(params, now);
        int offset = intValue(params.get("offset"), 0);
        int size = intValue(params.get("size"), 50);
        String sortField = stringValue(params.get("sortField"), "callCnt");
        String sortOrder = stringValue(params.get("sortOrder"), "desc");
        String serviceId = resolveServiceId(params);
        String srcServiceId = stringValue(params.get("srcServiceId"), null);
        String resourceQuery = resolveResourceQuery(params);
        Integer isIn = parseOptionalFlag(params.get("isIn"));
        Integer isOut = parseOptionalFlag(params.get("isOut"));
        if (!spec.webPeer() && isOut == null && isIn == null) {
            isOut = spec.outboundIsOut();
            isIn = spec.outboundIsIn();
        }

        List<ComponentEndpointPoint> points = loadComponentEndpoints(
                spec.tableName(), serviceId, from, to, resourceQuery, 500, isIn, isOut, srcServiceId);

        double durationSec = Math.max(1.0, (to - from) / 1000.0);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ComponentEndpointPoint point : points) {
            rows.add(toComponentEndpointRow(point, durationSec));
        }

        sortEndpointRows(rows, sortField, sortOrder);
        int total = rows.size();
        List<Map<String, Object>> page = rows.subList(
                Math.min(offset, total),
                Math.min(offset + Math.max(1, size), total));

        envelope.put("total", total);
        envelope.put("size", page.size());
        envelope.put("offset", offset + page.size());
        envelope.put("data", page);
        return envelope;
    }

    private Map<String, Object> dbEndpoints(Map<String, Object> body) {
        Map<String, Object> envelope = emptyEndpointsEnvelope();
        Map<String, Object> params = normalizeDbGraphStatsBody(body);

        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(params, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(params, now);
        int offset = intValue(params.get("offset"), 0);
        int size = intValue(params.get("size"), 50);
        String sortField = stringValue(params.get("sortField"), "callCnt");
        String sortOrder = stringValue(params.get("sortOrder"), "desc");
        String serviceId = resolveServiceId(params);
        String srcServiceId = stringValue(params.get("srcServiceId"), null);
        String resourceQuery = resolveDbResourceQuery(params);
        String sqlOperation = stringValue(params.get("sqlOperationQuery"), null);
        String sqlDatabase = stringValue(params.get("sqlDatabaseQuery"), null);
        Integer isIn = parseOptionalFlag(params.get("isIn"));
        Integer isOut = parseOptionalFlag(params.get("isOut"));

        List<DbEndpointPoint> points = loadDbEndpoints(
                serviceId, from, to, resourceQuery, sqlOperation, sqlDatabase,
                500, isIn, isOut, srcServiceId);

        double durationSec = Math.max(1.0, (to - from) / 1000.0);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DbEndpointPoint point : points) {
            rows.add(toDbEndpointRow(point, durationSec));
        }

        sortEndpointRows(rows, sortField, sortOrder);
        int total = rows.size();
        List<Map<String, Object>> page = rows.subList(
                Math.min(offset, total),
                Math.min(offset + Math.max(1, size), total));

        envelope.put("total", total);
        envelope.put("size", page.size());
        envelope.put("offset", offset + page.size());
        envelope.put("data", page);
        return envelope;
    }

    public Map<String, Object> distributionStats(Map<String, Object> body) {
        String componentType = stringValue(body.get("componentType"), "service.http");
        if (!"service.http".equals(componentType)) {
            return Map.of();
        }

        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        String serviceId = resolveServiceId(body);
        String serviceInstance = stringValue(body.get("serviceInstance"), null);
        String resource = stringValue(body.get("resource"), null);

        List<HttpLatencyBucketPoint> buckets;
        try {
            String sql = MetricQueryBuilder.httpLatencyDistributionSql(
                    metricDatabase,
                    serviceId,
                    from,
                    to,
                    null,
                    null,
                    resource);
            buckets = readRepository.queryHttpLatencyBuckets(sql);
        } catch (Exception e) {
            return Map.of();
        }

        Map<String, Object> stats = PortalLatencyStats.fromBuckets(buckets);
        if (stats.isEmpty()) {
            return Map.of();
        }
        if (serviceId != null) {
            stats.put("serviceId", PortalServiceIdResolver.normalize(serviceId));
        }
        if (serviceInstance != null) {
            stats.put("serviceInstance", serviceInstance);
        }
        if (resource != null) {
            stats.put("resource", resource);
        }
        stats.put("numBuckets", intValue(body.get("numBuckets"), 100));
        return stats;
    }

    public List<Map<String, Object>> reqTop(Map<String, Object> body) {
        String componentType = stringValue(body.get("componentType"), "service.http");
        if (!"service.http".equals(componentType)) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        int size = intValue(body.get("size"), 5);
        String serviceId = resolveServiceId(body);

        List<HttpEndpointPoint> points = loadHttpEndpoints(serviceId, from, to, null, 500);
        return points.stream()
                .map(point -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("resource", point.url());
                    row.put("componentType", componentType);
                    row.put("serviceId", PortalServiceIdResolver.resolve(point.serviceId(), point.service()));
                    row.put("serviceName", point.service());
                    long callCnt = point.requestCount();
                    long errCnt = point.errorCount();
                    row.put("callCnt", callCnt);
                    row.put("errCnt", errCnt);
                    row.put("errRate", callCnt > 0 ? (double) errCnt / callCnt : 0);
                    row.put("avgLatency", avgDurationMsToNs(point.avgDuration()));
                    return row;
                })
                .sorted(Comparator.comparingDouble(row -> -((Number) row.get("avgLatency")).doubleValue()))
                .limit(Math.max(1, size))
                .toList();
    }

    public Map<String, Object> slowSqlTopList(Map<String, Object> body) {
        Map<String, Object> params = normalizeDbGraphStatsBody(body);
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(params, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(params, now);
        int offset = intValue(params.get("offset"), 0);
        int size = intValue(params.get("size"), 50);
        String sortField = stringValue(params.get("sortField"), "avgLatency");
        String sortOrder = stringValue(params.get("sortOrder"), "desc");
        String serviceId = resolveServiceId(params);
        String srcServiceId = stringValue(params.get("srcServiceId"), null);
        String serviceInstance = stringValue(params.get("serviceInstance"), null);
        String resourceQuery = resolveDbResourceQuery(params);
        Integer isIn = parseOptionalFlag(params.get("isIn"));
        Integer isOut = parseOptionalFlag(params.get("isOut"));
        Integer isSlow = parseOptionalFlag(params.get("isSlow"));
        if (isSlow == null) {
            isSlow = 1;
        }

        List<DbSlowSqlTopPoint> points = loadDbSlowSqlTop(
                serviceId, from, to, resourceQuery, serviceInstance, 500, isIn, isOut, isSlow, srcServiceId);

        double durationSec = Math.max(1.0, (to - from) / 1000.0);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DbSlowSqlTopPoint point : points) {
            rows.add(toSlowSqlTopRow(point, durationSec));
        }

        sortEndpointRows(rows, sortField, sortOrder);
        int total = rows.size();
        List<Map<String, Object>> page = rows.subList(
                Math.min(offset, total),
                Math.min(offset + Math.max(1, size), total));

        Map<String, Object> envelope = emptyEndpointsEnvelope();
        envelope.put("total", total);
        envelope.put("size", page.size());
        envelope.put("offset", offset + page.size());
        envelope.put("data", page);
        return envelope;
    }

    public Map<String, Object> exceptionDistMap(Map<String, Object> body) {
        Map<String, Object> envelope = emptyEndpointsEnvelope();
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        String groupBy = stringValue(body.get("groupBy"), "exceptionName");
        String serviceId = resolveServiceId(body);
        String serviceInstance = stringValue(body.get("serviceInstance"), null);
        String resourceQuery = stringValue(body.get("resourceQuery"), null);
        String exception = stringValue(body.get("exception"), null);
        String rootResourceQuery = stringValue(body.get("rootResourceQuery"), null);
        int offset = intValue(body.get("offset"), 0);
        int size = intValue(body.get("size"), 50);
        String sortField = stringValue(body.get("sortField"), "errCnt");
        String sortOrder = stringValue(body.get("sortOrder"), "desc");

        List<ExceptionDistPoint> points = loadExceptionDistPoints(
                groupBy, from, to, serviceId, serviceInstance, resourceQuery, exception, rootResourceQuery);

        Map<String, MetaServicePoint> metaById = "serviceId".equals(groupBy) ? loadMetaServiceIndex() : Map.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ExceptionDistPoint point : points) {
            Map<String, Object> row = new LinkedHashMap<>();
            if (point.exceptionName() != null) {
                row.put("exceptionName", point.exceptionName());
            }
            if (point.serviceId() != null) {
                String metricKey = point.serviceId();
                MetaServicePoint meta = resolveMetaPoint(metricKey, metaById);
                if (meta == null) {
                    meta = resolveMetaPoint(PortalServiceIdResolver.normalize(metricKey), metaById);
                }
                String resolvedId = meta != null && !isBlank(meta.id())
                        ? PortalServiceIdResolver.normalize(meta.id())
                        : PortalServiceIdResolver.resolve(null, metricKey);
                String displayName = resolveExceptionDistServiceLabel(metricKey, resolvedId, meta);
                row.put("serviceId", resolvedId);
                row.put("service", displayName);
                row.put("serviceName", displayName);
            }
            if (point.serviceInstance() != null) {
                row.put("serviceInstance", point.serviceInstance());
            }
            if (point.resource() != null) {
                row.put("resource", point.resource());
            }
            if (point.rootResource() != null && "rootResource".equals(groupBy)) {
                row.put("rootResource", point.rootResource());
            }
            row.put("errCnt", point.errorCount());
            rows.add(row);
        }

        long totalError = rows.stream().mapToLong(row -> ((Number) row.get("errCnt")).longValue()).sum();
        for (Map<String, Object> row : rows) {
            long errCnt = ((Number) row.get("errCnt")).longValue();
            row.put("percentage", totalError > 0 ? errCnt * 100.0 / totalError : 0);
        }

        sortEndpointRows(rows, sortField, sortOrder);
        int total = rows.size();
        List<Map<String, Object>> page = rows.subList(
                Math.min(offset, total),
                Math.min(offset + Math.max(1, size), total));

        envelope.put("total", total);
        envelope.put("totalError", totalError);
        envelope.put("size", page.size());
        envelope.put("offset", offset + page.size());
        envelope.put("data", page);
        return envelope;
    }

    public Map<String, Object> callEndpoints(Map<String, Object> body) {
        String componentType = stringValue(body.get("componentType"), "service.http");
        if ("service.http".equals(componentType)) {
            return httpCallEndpoints(body);
        }
        if ("service.db".equals(componentType)) {
            return dbCallEndpoints(body);
        }
        ComponentPeerSpec spec = findComponentSpec(componentType);
        if (spec == null) {
            return emptyEndpointsEnvelope();
        }
        if (spec.webPeer()) {
            return webPeerCallEndpoints(body, spec.tableName());
        }
        return componentCallEndpoints(body, spec);
    }

    public Map<String, Object> callInfo(Map<String, Object> body) {
        String componentType = stringValue(body.get("componentType"), "service.http");
        if ("service.http".equals(componentType)) {
            return httpCallInfo(body);
        }
        if ("service.db".equals(componentType)) {
            return dbCallInfo(body);
        }
        ComponentPeerSpec spec = findComponentSpec(componentType);
        if (spec == null) {
            return Map.of("componentType", componentType);
        }
        if (spec.webPeer()) {
            return webPeerCallInfo(body, componentType, spec.tableName());
        }
        return componentCallInfo(body, componentType, spec);
    }

    public Map<String, Object> reqContributorService(Map<String, Object> body) {
        String componentType = stringValue(body.get("componentType"), "service.http");
        List<ResourceRelationTableSpec> tableSpecs = resolveResourceRelationTables(componentType);
        if (tableSpecs.isEmpty()) {
            return Map.of("list", List.of(), "total", 0);
        }

        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        int offset = intValue(body.get("offset"), 0);
        int size = intValue(body.get("size"), 50);
        String serviceId = resolveServiceId(body);
        String srcServiceId = stringValue(body.get("srcServiceId"), null);
        String resourceQuery = resolveResourceQuery(body);
        Integer isIn = parseOptionalFlag(body.get("isIn"));
        if (isIn == null) {
            isIn = 1;
        }

        ResourceRelationTableSpec tableSpec = tableSpecs.get(0);
        Set<String> serviceKeys = metricServiceIdKeys(serviceId);
        Set<String> srcKeys = metricServiceIdKeys(srcServiceId);

        List<ComponentResourceRelationPoint> points = queryComponentResourceRelation(
                tableSpec.table(),
                from,
                to,
                serviceKeys.isEmpty() ? null : serviceKeys,
                srcKeys.isEmpty() ? null : srcKeys,
                resourceQuery,
                null,
                isIn,
                null,
                List.of("srcService", "srcServiceId"),
                500);

        List<Map<String, Object>> list = points.stream()
                .filter(point -> point.srcService() != null && !point.srcService().isBlank())
                .map(point -> toContributorServiceRow(point, resourceQuery))
                .toList();

        int total = list.size();
        List<Map<String, Object>> page = list.subList(
                Math.min(offset, total),
                Math.min(offset + Math.max(1, size), total));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", page);
        data.put("total", total);
        return data;
    }

    private static Map<String, Object> toContributorServiceRow(
            ComponentResourceRelationPoint point, String resourceQuery) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("resource", resourceQuery != null ? resourceQuery : nullToEmpty(point.resource()));
        String srcName = point.srcService();
        String srcId = PortalServiceIdResolver.resolve(point.srcServiceId(), srcName);
        row.put("serviceName", srcName);
        row.put("serviceId", srcId);
        row.put("srcService", srcName);
        row.put("srcServiceId", srcId);
        long callCnt = point.allCnt();
        long errCnt = point.errCnt();
        long slowCnt = point.slowCnt();
        row.put("callCnt", callCnt);
        row.put("errCnt", errCnt);
        row.put("slowCnt", slowCnt);
        row.put("normalCnt", Math.max(0, callCnt - slowCnt - errCnt));
        row.put("reqRate", callCnt);
        row.put("errRate", callCnt > 0 ? (double) errCnt / callCnt : 0);
        row.put("avgLatency", Math.round(point.avgTimeNs()));
        row.put("datasource", "OTLP");
        return row;
    }

    /** Metric lookback used when meta_service is empty or the requested window has no traffic. */
    /** Max page size for portal {@link #list}. */
    private static final int SERVICE_LIST_MAX_PAGE_SIZE = 500;

    /**
     * Service id/name catalog for portal dropdowns.
     * <p>Sources (in order):
     * <ul>
     *   <li>{@code ignoreTime=1}: {@code meta_service}</li>
     *   <li>otherwise: {@code metric_service} in the requested window, then {@code meta_service}</li>
     * </ul>
     * Aligns with {@link #list} which already read {@code metric_service}.
     */
    public List<Map<String, Object>> basicServices(Map<String, Object> body) {
        boolean ignoreTime = intValue(body.get("ignoreTime"), 0) == 1;
        if (ignoreTime) {
            return loadBasicServiceRows(body);
        }

        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        List<Map<String, Object>> rows = loadBasicServicesFromMetrics(body, from, to);
        if (!rows.isEmpty()) {
            return rows;
        }
        return loadBasicServiceRows(body);
    }

    /** Full service catalog from Doris {@code meta_service} (no auth-group filter). */
    public List<Map<String, Object>> basicAllServices(Map<String, Object> body) {
        return loadBasicServiceRows(body);
    }

    private List<Map<String, Object>> loadBasicServicesFromMetrics(
            Map<String, Object> body, long from, long to) {
        Map<String, MetaServicePoint> metaById = loadMetaServiceIndex();
        return listDistinctServices(from, to).stream()
                .map(serviceKey -> {
                    MetaServicePoint meta = resolveMetaPoint(serviceKey, metaById);
                    return meta != null ? toBasicServiceRow(meta) : toBasicServiceRowFromId(serviceKey);
                })
                .filter(row -> matchesBasicServiceRowFilters(row, body))
                .toList();
    }

    private List<Map<String, Object>> loadBasicServiceRows(Map<String, Object> body) {
        String serviceName = normalizeServiceNameFilter(stringValue(body.get("serviceName"), null));
        try {
            String sql = MetricQueryBuilder.metaServicesSql(metricDatabase, serviceName);
            return readRepository.queryMetaServices(sql).stream()
                    .filter(point -> matchesBasicServiceMetaFilters(point, body))
                    .map(this::toBasicServiceRow)
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, MetaServicePoint> loadMetaServiceIndex() {
        try {
            String sql = MetricQueryBuilder.metaServicesSql(metricDatabase, null);
            Map<String, MetaServicePoint> index = new LinkedHashMap<>();
            for (MetaServicePoint point : readRepository.queryMetaServices(sql)) {
                if (point.id() != null && !point.id().isBlank()) {
                    index.putIfAbsent(point.id(), point);
                }
            }
            return index;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private MetaServicePoint resolveMetaPoint(String serviceKey, Map<String, MetaServicePoint> metaById) {
        MetaServicePoint direct = metaById.get(serviceKey);
        if (direct != null) {
            return direct;
        }
        for (MetaServicePoint point : metaById.values()) {
            if (serviceKey.equals(point.name()) || serviceKey.equals(point.service())) {
                return point;
            }
        }
        return null;
    }

    private Map<String, Object> toBasicServiceRowFromId(String serviceId) {
        String serviceType = inferServiceCategory(serviceId);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", serviceId);
        row.put("name", serviceId);
        row.put("service", serviceId);
        row.put("service_type", serviceType);
        row.put("type", inferServiceTypeIcon(serviceId, serviceType));
        return row;
    }

    private boolean matchesBasicServiceMetaFilters(MetaServicePoint point, Map<String, Object> body) {
        String serviceType = stringValue(body.get("serviceType"), null);
        List<String> serviceTypes = parseStringList(body.get("serviceTypes"));
        List<String> datasources = parseStringList(body.get("datasources"));
        int virtualService = intValue(body.get("virtualService"), -1);
        String pointType = firstNonBlank(point.serviceType(), inferServiceCategory(point.id()));

        if (serviceType != null && !serviceType.equalsIgnoreCase(pointType)) {
            return false;
        }
        if (!serviceTypes.isEmpty()
                && serviceTypes.stream().noneMatch(type -> type.equalsIgnoreCase(pointType))) {
            return false;
        }
        if (!datasources.isEmpty()) {
            String datasource = firstNonBlank(point.datasource(), "OTLP");
            if (datasources.stream().noneMatch(ds -> ds.equalsIgnoreCase(datasource))) {
                return false;
            }
        }
        if (virtualService >= 0) {
            boolean isVirtual = Boolean.TRUE.equals(point.virtualService());
            if (virtualService == 0 && isVirtual) {
                return false;
            }
            if (virtualService == 1 && !isVirtual) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesBasicServiceRowFilters(Map<String, Object> row, Map<String, Object> body) {
        String serviceName = normalizeServiceNameFilter(stringValue(body.get("serviceName"), null));
        if (serviceName != null && !matchesServiceNameKeyword(row, serviceName)) {
            return false;
        }
        String serviceType = stringValue(body.get("serviceType"), null);
        List<String> serviceTypes = parseStringList(body.get("serviceTypes"));
        String rowType = stringValue(row.get("service_type"), "web");
        if (serviceType != null && !serviceType.equalsIgnoreCase(rowType)) {
            return false;
        }
        return serviceTypes.isEmpty()
                || serviceTypes.stream().anyMatch(type -> type.equalsIgnoreCase(rowType));
    }

    private static String normalizeServiceNameFilter(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return null;
        }
        String trimmed = serviceName.trim();
        if ("null".equalsIgnoreCase(trimmed) || "undefined".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private static boolean matchesServiceNameKeyword(Map<String, Object> row, String serviceName) {
        String keyword = serviceName.toLowerCase(Locale.ROOT);
        return stringValue(row.get("name"), "").toLowerCase(Locale.ROOT).contains(keyword)
                || stringValue(row.get("service"), "").toLowerCase(Locale.ROOT).contains(keyword)
                || stringValue(row.get("id"), "").toLowerCase(Locale.ROOT).contains(keyword);
    }

    private Map<String, Object> toBasicServiceRow(MetaServicePoint point) {
        String id = point.id();
        String name = point.name();
        if (name == null || name.isBlank()) {
            name = id;
        }
        String service = point.service();
        if (service == null || service.isBlank()) {
            service = name;
        }
        String serviceType = firstNonBlank(point.serviceType(), inferServiceCategory(id));
        String type = firstNonBlank(point.type(), inferServiceTypeIcon(id, serviceType));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("service", service);
        row.put("service_type", serviceType);
        row.put("type", type);
        row.put("language", nullToEmpty(point.language()));
        row.put("virtual_service", Boolean.TRUE.equals(point.virtualService()));
        return row;
    }

    public List<String> listDistinctServices(long from, long to) {
        try {
            return readRepository.queryDistinctTags(
                    MetricQueryBuilder.distinctServicesSql(metricDatabase, from, to));
        } catch (Exception e) {
            return List.of();
        }
    }

    public Map<String, Object> getBasicServiceInstance(Map<String, Object> body) {
        String serviceId = resolveServiceId(body);
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);

        List<String> instances = List.of();
        if (serviceId != null && !serviceId.isBlank()) {
            try {
                String sql = MetricQueryBuilder.serviceInstanceDistinctSql(
                        metricDatabase, serviceId, from, to, 200);
                instances = readRepository.queryTopGroups(sql);
            } catch (Exception ignored) {
                instances = List.of();
            }
        }

        List<Map<String, String>> rows = instances.stream()
                .map(instance -> Map.of("serviceInstance", instance))
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serviceInstances", rows);
        data.put("componentTypes", List.of("service.http"));
        return data;
    }

    /**
     * Service instances for portal {@code GET /service/getServiceInstance}.
     * Time range accepts epoch-ms {@code start}/{@code end} or legacy {@code fromTime}/{@code toTime}.
     */
    public List<Map<String, Object>> getServiceInstance(Map<String, Object> body) {
        String serviceId = resolveServiceId(body);
        if (serviceId == null || serviceId.isBlank()) {
            return List.of();
        }

        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        String instanceFilter = stringValue(body.get("serviceInstance"), null);
        if (instanceFilter == null) {
            instanceFilter = stringValue(body.get("si"), null);
        }

        Map<String, Object> traceServiceEntity = serviceInfo(body);
        if (traceServiceEntity == null) {
            traceServiceEntity = minimalTraceServiceEntity(serviceId);
        }

        List<ServiceInstanceSummaryPoint> summaries;
        try {
            String sql = MetricQueryBuilder.serviceInstanceSummarySql(
                    metricDatabase, serviceId, from, to, instanceFilter, 200);
            summaries = readRepository.queryServiceInstanceSummaries(sql);
        } catch (Exception e) {
            return List.of();
        }

        final Map<String, Object> entity = traceServiceEntity;
        return summaries.stream()
                .map(point -> toServiceInstanceRow(point, entity))
                .toList();
    }

    private Map<String, Object> toServiceInstanceRow(
            ServiceInstanceSummaryPoint point, Map<String, Object> traceServiceEntity) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("serviceInstance", point.serviceInstance());
        row.put("hostName", unknownIfBlank(point.hostName()));
        row.put("hostIp", unknownIfBlank(firstNonBlank(point.hostId(), point.hostName())));
        row.put("alarmCount", 0L);
        row.put("serviceCall", point.callCount());
        putIfPresent(row, "k8sNamespace", point.k8sNamespace());
        putIfPresent(row, "k8sPodName", point.k8sPodName());
        putIfPresent(row, "k8sClusterId", point.k8sClusterId());
        putIfPresent(row, "containerId", point.containerId());
        putIfPresent(row, "pname", point.processName());
        row.put("traceServiceEntity", traceServiceEntity);
        return row;
    }

    private static Map<String, Object> minimalTraceServiceEntity(String serviceId) {
        Map<String, Object> entity = new LinkedHashMap<>();
        String resolvedId = PortalServiceIdResolver.normalize(serviceId);
        entity.put("serviceId", resolvedId);
        entity.put("name", serviceId);
        entity.put("service", serviceId);
        entity.put("service_type", "web");
        entity.put("type", "web");
        entity.put("datasource", "OTLP");
        entity.put("technology", "");
        entity.put("alarmCount", 0L);
        return entity;
    }

    private static String unknownIfBlank(String value) {
        return isBlank(value) ? "unknown" : value;
    }

    private static void putIfPresent(Map<String, Object> row, String key, String value) {
        if (!isBlank(value)) {
            row.put(key, value);
        }
    }

    /**
     * Service relation graph for portal {@code GET /service/getServiceInstanceRelations}.
     * Shape matches legacy databuff {@code ServiceV2ServiceImpl.getServiceInstanceRelations}.
     */
    public Map<String, Object> getServiceInstanceRelations(Map<String, Object> body) {
        String serviceId = resolveServiceInfoId(body);
        if (serviceId == null || serviceId.isBlank()) {
            return emptyServiceInstanceRelations();
        }

        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);

        List<Map<String, Object>> upflowServiceStats = new ArrayList<>();
        List<Map<String, Object>> downflowServiceStats = new ArrayList<>();
        Map<String, String> peerDisplayNames = new LinkedHashMap<>();

        String resolvedServiceId = PortalServiceIdResolver.normalize(serviceId);

        appendComponentPeerRelations(
                serviceId, from, to, upflowServiceStats, downflowServiceStats, peerDisplayNames);

        if (upflowServiceStats.isEmpty() && downflowServiceStats.isEmpty()) {
            appendTopologyPeerRelations(serviceId, from, to, upflowServiceStats, downflowServiceStats, peerDisplayNames);
        }

        peerDisplayNames.putIfAbsent(resolvedServiceId, serviceId);
        List<Map<String, Object>> serviceId2Name = peerDisplayNames.entrySet().stream()
                .map(entry -> toPeerId2NameRow(entry.getKey(), entry.getValue()))
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reqCnt", loadServiceRequestRate(serviceId, from, to));
        data.put("upflowServiceStats", upflowServiceStats);
        data.put("downflowServiceStats", downflowServiceStats);
        data.put("serviceId2Name", serviceId2Name);
        return data;
    }

    public List<String> k8sNamespaceList(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        try {
            return readRepository.queryTopGroups(
                    MetricQueryBuilder.k8sNamespaceDistinctSql(metricDatabase, from, to, 200));
        } catch (Exception e) {
            return List.of();
        }
    }

    public Map<String, List<String>> poolGetNames(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        String serviceId = resolveServiceId(body);
        String filterClause = buildResourceFilterClause(serviceId, null);

        Map<String, List<String>> result = new LinkedHashMap<>();
        putPoolNames(result, "service.object.pool.get", "service_object_pool", "objectPoolName", from, to, filterClause);
        putPoolNames(result, "service.http.connection.pool.get", "service_http_connection_pool",
                "httpConnectionPoolName", from, to, filterClause);
        putPoolNames(result, "service.db.connection.pool.get", "service_db_connection_pool",
                "connectionPoolName", from, to, filterClause);
        return result;
    }

    private void putPoolNames(
            Map<String, List<String>> result,
            String key,
            String table,
            String tagColumn,
            long from,
            long to,
            String filterClause) {
        try {
            String sql = MetricQueryBuilder.metricTagDistinctSql(
                    metricDatabase, table, tagColumn, from, to, filterClause);
            List<String> names = readRepository.queryDistinctTags(sql);
            if (!names.isEmpty()) {
                result.put(key, names);
            }
        } catch (Exception ignored) {
            // optional pool table
        }
    }

    public Map<String, Object> resourceRelation(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        String serviceId = resolveServiceId(body);
        String url = decodeUrl(body);
        String componentType = stringValue(body.get("componentType"), "service.http");
        double durationSec = Math.max(1.0, (to - from) / 1000.0);

        Map<String, Object> detail = resourceInfo(withUrl(body, url));
        Map<String, List<Map<String, Object>>> currentResources = loadCurrentResourceRelations(
                serviceId, url, componentType, from, to, durationSec, detail);
        Map<String, List<Map<String, Object>>> upFlowResources = loadUpstreamResourceRelations(
                serviceId, url, componentType, from, to, durationSec);
        Map<String, List<Map<String, Object>>> downFlowResources = loadDownstreamResourceRelations(
                serviceId, url, from, to, durationSec);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reqCnt", toLong(detail.get("callCnt")));
        data.put("currentResources", currentResources);
        data.put("upFlowResources", upFlowResources);
        data.put("downFlowResources", downFlowResources);
        return data;
    }

    public Map<String, Object> resourceInfo(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        String serviceId = resolveServiceId(body);
        String url = decodeUrl(body);

        List<HttpEndpointPoint> points = loadHttpEndpoints(serviceId, from, to, url, 500, null, null, null, true);
        HttpEndpointPoint match = points.stream()
                .filter(point -> url == null || url.equals(point.url()))
                .findFirst()
                .orElse(points.isEmpty() ? null : points.get(0));
        if (match == null) {
            return Map.of();
        }

        long callCnt = match.requestCount();
        long errCnt = match.errorCount();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("resource", url != null ? url : match.url());
        data.put("serviceId", PortalServiceIdResolver.resolve(match.serviceId(), match.service(), serviceId));
        data.put("service", match.service());
        data.put("service_type", "web");
        data.put("httpMethod", match.httpMethod());
        data.put("callCnt", callCnt);
        data.put("errCnt", errCnt);
        data.put("avgLatency", avgDurationMsToNs(match.avgDuration()));
        data.put("errRate", callCnt > 0 ? (double) errCnt / callCnt : 0);
        return data;
    }

    public Map<String, Object> allCntForSingleResource(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        int interval = intValue(body.get("interval"), 60);

        Map<String, Long> calls = new LinkedHashMap<>();
        Map<String, Long> slows = new LinkedHashMap<>();
        Map<String, Long> errors = new LinkedHashMap<>();
        for (ComponentTrendBucketPoint bucket : loadResourceTrendMetricBuckets(body)) {
            String key = String.valueOf(bucket.bucketEpochSec() * 1000L);
            calls.merge(key, bucket.requestCount(), Long::sum);
            slows.merge(key, bucket.slowCount(), Long::sum);
            errors.merge(key, bucket.errorCount(), Long::sum);
        }
        calls = fillLongSeries(calls, from, to, interval);
        slows = fillLongSeries(slows, from, to, interval);
        errors = fillLongSeries(errors, from, to, interval);

        Map<String, Object> result = new LinkedHashMap<>();
        java.util.TreeSet<String> timestamps = new java.util.TreeSet<>();
        timestamps.addAll(calls.keySet());
        timestamps.addAll(slows.keySet());
        timestamps.addAll(errors.keySet());
        for (String timestamp : timestamps) {
            Map<String, Long> group = new LinkedHashMap<>();
            group.put("call", calls.getOrDefault(timestamp, 0L));
            group.put("slow", slows.getOrDefault(timestamp, 0L));
            group.put("error", errors.getOrDefault(timestamp, 0L));
            result.put(timestamp, group);
        }
        return result;
    }

    public Map<String, Long> slowCntForSingleResource(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        int interval = intValue(body.get("interval"), 60);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ComponentTrendBucketPoint bucket : loadResourceTrendMetricBuckets(body)) {
            String key = String.valueOf(bucket.bucketEpochSec() * 1000L);
            counts.merge(key, bucket.slowCount(), Long::sum);
        }
        return fillLongSeries(counts, from, to, interval);
    }

    public Map<String, Long> errorCntForSingleResource(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        int interval = intValue(body.get("interval"), 60);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ComponentTrendBucketPoint bucket : loadResourceTrendMetricBuckets(body)) {
            String key = String.valueOf(bucket.bucketEpochSec() * 1000L);
            counts.merge(key, bucket.errorCount(), Long::sum);
        }
        return fillLongSeries(counts, from, to, interval);
    }

    private List<ComponentTrendBucketPoint> loadResourceTrendMetricBuckets(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        int interval = intValue(body.get("interval"), 60);
        String serviceId = resolveServiceId(body);
        String url = decodeResourceValue(stringValue(body.get("url"), null));
        String resource = decodeResource(body);
        String componentType = stringValue(body.get("componentType"), "service.http");
        Integer isIn = parseOptionalFlag(body.get("isIn"));
        Integer isOut = parseOptionalFlag(body.get("isOut"));
        String table = resolveComponentTable(componentType);
        try {
            String sql = MetricQueryBuilder.componentResourceTrendBucketsSql(
                    metricDatabase,
                    table,
                    from,
                    to,
                    interval,
                    serviceId,
                    stringValue(body.get("serviceInstance"), null),
                    url,
                    resource,
                    isIn,
                    isOut);
            return readRepository.queryComponentTrendBuckets(sql);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Map<String, Long> fillLongSeries(
            Map<String, Long> values, long from, long to, int interval) {
        Map<String, Long> filled = TimeSeriesFillUtil.fillStringKeyObjectMap(values, from, to, interval);
        Map<String, Long> result = new LinkedHashMap<>();
        filled.forEach((key, value) -> result.put(key, value == null ? 0L : value));
        return result;
    }

    private static String resolveComponentTable(String componentType) {
        return RESOURCE_RELATION_TABLES.stream()
                .filter(spec -> spec.componentType().equals(componentType))
                .map(ResourceRelationTableSpec::table)
                .findFirst()
                .orElse(DorisTableNames.METRIC_SERVICE_HTTP);
    }

    public Map<String, List<String>> resources(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        String field = stringValue(body.get("field"), "resource");
        String componentType = stringValue(body.get("componentType"), null);
        String serviceId = resolveServiceId(body);
        String serviceInstance = stringValue(body.get("serviceInstance"), null);
        String filterClause = buildResourceFilterClause(serviceId, serviceInstance);

        Map<String, List<String>> result = new LinkedHashMap<>();
        if ("exceptionName".equals(field) || "service.exception".equals(componentType)) {
            putDistinctValues(result, "service.exception", "service_exception", "errorType", from, to, filterClause);
            return result;
        }

        List<ResourceComponentSpec> specs = resolveResourceComponentSpecs(componentType);
        for (ResourceComponentSpec spec : specs) {
            String column = "resource".equals(field) ? spec.resourceColumn() : field;
            putDistinctValues(result, spec.componentType(), spec.table(), column, from, to, filterClause);
        }
        return result;
    }

    public Map<String, List<Map<String, String>>> resourcesGroupBy(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        String field = stringValue(body.get("field"), "rootResource");
        String serviceId = resolveServiceId(body);
        Map<String, List<Map<String, String>>> result = new LinkedHashMap<>();
        if (field.contains("srcService")) {
            return srcServicesGroupBy(body, from, to);
        }
        if (!"rootResource".equals(field) || serviceId == null) {
            return result;
        }
        try {
            String sql = MetricQueryBuilder.serviceFlowSrcServicesSql(metricDatabase, serviceId, from, to, 200);
            List<String> srcServices = readRepository.queryDistinctTags(sql);
            if (srcServices.isEmpty()) {
                return result;
            }
            List<Map<String, String>> rows = srcServices.stream()
                    .map(src -> Map.of("srcServiceId", src, "rootResource", ""))
                    .toList();
            result.put("service.http", rows);
        } catch (Exception ignored) {
            // optional enrichment
        }
        return result;
    }

    private Map<String, List<Map<String, String>>> srcServicesGroupBy(
            Map<String, Object> body, long from, long to) {
        String componentType = stringValue(body.get("componentType"), null);
        String serviceId = resolveServiceId(body);
        String serviceInstance = stringValue(body.get("serviceInstance"), null);
        Integer isIn = parseOptionalFlag(body.get("isIn"));
        StringBuilder filterClause = new StringBuilder(buildResourceFilterClause(serviceId, serviceInstance));
        if (isIn != null) {
            filterClause.append(" AND `isIn` = '").append(isIn).append("' ");
        }

        Map<String, List<Map<String, String>>> result = new LinkedHashMap<>();
        for (ResourceRelationTableSpec spec : resolveResourceRelationTables(componentType)) {
            putSrcServiceGroups(result, spec.componentType(), spec.table(), from, to, filterClause.toString());
        }
        return result;
    }

    private void putSrcServiceGroups(
            Map<String, List<Map<String, String>>> result,
            String componentType,
            String table,
            long from,
            long to,
            String filterClause) {
        try {
            String sql = MetricQueryBuilder.distinctSrcServicesSql(
                    metricDatabase, table, from, to, filterClause, 1000);
            List<Map<String, String>> rows = readRepository.queryDistinctSrcServices(sql);
            if (!rows.isEmpty()) {
                result.put(componentType, rows);
            }
        } catch (Exception ignored) {
            // optional table
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> metricStats(Map<String, Object> body) {
        String metric = stringValue(body.get("metric"), "");
        if (metric.isBlank()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        List<String> fields = parseStringList(body.get("fields"));
        List<String> groupBys = parseStringList(body.get("groupBys"));
        Map<String, Object> filters = body.get("filters") instanceof Map<?, ?> filterMap
                ? (Map<String, Object>) filterMap
                : Map.of();
        String fieldExpr = fields.isEmpty() ? "sum(error)" : fields.get(0);
        MetricTarget target = resolveMetricTarget(metric, filters);
        String filterClause = buildMetricFilterClause(filters, target);
        String fieldColumn = target.fieldColumn();

        if (groupBys.isEmpty()) {
            return buildMetricSeriesList(
                    Map.of(), target, fieldColumn, fieldExpr, filterClause, from, to);
        }

        String groupBy = groupBys.get(0);
        String groupColumn = portalGroupColumn(groupBy, target.table());
        List<String> groups;
        try {
            String topSql = MetricQueryBuilder.metricTopGroupsSql(
                    metricDatabase, target.table(), fieldColumn, groupColumn, from, to, filterClause, 20);
            groups = readRepository.queryTopGroups(topSql);
        } catch (Exception e) {
            return List.of();
        }

        List<Map<String, Object>> series = new ArrayList<>();
        for (String groupValue : groups) {
            Map<String, String> tags = new LinkedHashMap<>();
            tags.put(groupBy, groupValue);
            series.addAll(buildMetricSeriesList(tags, target, fieldColumn, fieldExpr, filterClause, from, to, groupColumn, groupValue));
        }
        return series;
    }

    public List<Map<String, Object>> resourceStats(Map<String, Object> body) {
        String componentType = stringValue(body.get("componentType"), "service.http");
        if (!"service.http".equals(componentType)) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        int interval = intValue(body.get("interval"), 60);
        String serviceId = resolveServiceId(body);
        String serviceInstance = stringValue(body.get("serviceInstance"), null);
        String url = decodeUrl(body);

        if (serviceId == null) {
            return List.of();
        }

        List<ServiceTrendBucketPoint> buckets;
        try {
            Set<String> serviceKeys = metricServiceIdKeys(serviceId);
            String sql = MetricQueryBuilder.httpTrendBucketsSql(
                    metricDatabase, from, to, interval, serviceKeys, serviceInstance, url,
                    null, null, Set.of(), true);
            buckets = readRepository.queryServiceTrendBuckets(sql);
        } catch (Exception e) {
            return List.of();
        }

        List<MetricSeriesPoint> points = buckets.stream()
                .map(bucket -> new MetricSeriesPoint(
                        bucket.bucketEpochSec(),
                        bucket.requestCount() > 0 ? bucket.sumDurationNs() / bucket.requestCount() : 0))
                .toList();
        Map<String, String> tags = Map.of("service", serviceId);
        return List.of(PortalMetricSeriesBuilder.series(tags, points, "avgLatency", from, to, interval));
    }

    public Map<String, Object> list(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        int offset = intValue(body.get("offset"), 0);
        int size = Math.min(
                intValue(body.get("size"), intValue(body.get("pageSize"), 20)),
                SERVICE_LIST_MAX_PAGE_SIZE);
        String sortField = stringValue(body.get("sortField"), "callCnt");
        String sortOrder = stringValue(body.get("sortOrder"), "desc");
        String serviceName = stringValue(body.get("serviceName"), null);
        List<String> serviceIds = parseStringList(body.get("serviceIds"));
        List<String> serviceTypes = parseStringList(body.get("serviceTypes"));
        Integer statusType = parseStatusType(body.get("statusType"));
        String listServiceCategory = resolveListServiceCategory(serviceTypes);

        List<ServiceSummaryPoint> summaries;
        long total;
        try {
            summaries = readRepository.queryServiceSummaries(
                    MetricQueryBuilder.serviceSummarySql(
                            metricDatabase,
                            from,
                            to,
                            sortField,
                            sortOrder,
                            offset,
                            size,
                            serviceName,
                            serviceIds,
                            statusType,
                            listServiceCategory));
            total = readRepository.queryDistinctCount(
                    MetricQueryBuilder.serviceSummaryCountSql(
                            metricDatabase, from, to, serviceName, serviceIds, statusType, listServiceCategory));
        } catch (Exception e) {
            return listEnvelope(List.of(), 0, offset, size);
        }

        long durationMs = Math.max(1L, to - from);
        double durationSec = durationMs / 1000.0;
        Map<String, MetaServicePoint> metaById = loadMetaServiceIndex();
        List<Map<String, Object>> rows = new ArrayList<>(summaries.size());
        for (ServiceSummaryPoint summary : summaries) {
            long callCnt = summary.requestCount();
            long errCnt = summary.errorCount();
            double errRate = callCnt > 0 ? (double) errCnt / callCnt : 0;
            double avgLatencyNs = callCnt > 0 ? summary.sumDurationNs() / callCnt : 0;
            String resolvedId = PortalServiceIdResolver.resolve(summary.serviceId(), summary.service());
            String displayName = summary.service();
            String serviceCategory = inferServiceCategory(displayName);
            MetaServicePoint meta = resolveMetaPoint(resolvedId, metaById);
            if (meta == null) {
                meta = resolveMetaPoint(displayName, metaById);
            }
            String language = meta != null ? nullToEmpty(meta.language()) : "";
            String type = firstNonBlank(
                    meta != null ? meta.type() : null,
                    inferServiceTypeIcon(displayName, serviceCategory));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("serviceId", resolvedId);
            row.put("name", displayName);
            row.put("service", displayName);
            row.put("callCnt", callCnt);
            row.put("errCnt", errCnt);
            row.put("errRate", errRate);
            row.put("avgLatency", avgLatencyNs);
            row.put("maxLatency", Math.round(summary.maxDurationNs()));
            row.put("reqRate", callCnt / durationSec);
            row.put("lastMinReqRate", (callCnt / durationSec) * 60);
            row.put("type", type);
            row.put("language", language);
            rows.add(row);
        }

        return listEnvelope(rows, total, offset, rows.size());
    }

    private static String resolveListServiceCategory(List<String> serviceTypes) {
        if (serviceTypes.size() != 1) {
            return null;
        }
        String category = serviceTypes.get(0);
        if ("web".equalsIgnoreCase(category) || "custom".equalsIgnoreCase(category)) {
            return category.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    /**
     * Single-service detail for portal {@code POST /service/serviceInfo}.
     * Response shape matches legacy portal API ({@code ServiceV2ServiceImpl.serviceInfo}).
     */
    public Map<String, Object> serviceInfo(Map<String, Object> body) {
        String serviceId = resolveServiceInfoId(body);
        if (serviceId == null || serviceId.isBlank()) {
            return null;
        }

        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);

        MetaServicePoint meta = loadMetaService(serviceId);
        String displayName = resolveServiceDisplayName(serviceId, meta);
        String serviceType = meta != null && !isBlank(meta.serviceType())
                ? meta.serviceType()
                : inferServiceCategory(serviceId);
        String typeIcon = meta != null && !isBlank(meta.type())
                ? meta.type()
                : inferServiceTypeIcon(serviceId, serviceType);
        String collectedService = meta != null && !isBlank(meta.service()) ? meta.service() : serviceId;

        String resolvedId = meta != null && !isBlank(meta.id())
                ? PortalServiceIdResolver.normalize(meta.id())
                : PortalServiceIdResolver.normalize(serviceId);
        Map<String, Object> service = new LinkedHashMap<>();
        service.put("serviceId", resolvedId);
        service.put("name", displayName);
        service.put("service", collectedService);
        service.put("service_type", serviceType);
        service.put("type", typeIcon);
        service.put("technology", resolveServiceTechnology(
                meta != null ? meta.technology() : null, serviceId, serviceType, from, to));
        service.put("language", meta != null ? nullToEmpty(meta.language()) : "");
        service.put("processRuntimeName", meta != null ? nullToEmpty(meta.processRuntimeName()) : "");
        service.put("processRuntimeVersion", meta != null ? nullToEmpty(meta.processRuntimeVersion()) : "");
        service.put("datasource", meta != null && !isBlank(meta.datasource()) ? meta.datasource() : "OTLP");
        service.put("source", meta != null ? nullToEmpty(meta.source()) : "");
        service.put("fqdn", meta != null ? nullToEmpty(meta.fqdn()) : "");
        service.put("container_service", meta != null ? nullToEmpty(meta.containerService()) : "");
        service.put("virtual_service", meta != null && meta.virtualService() != null
                ? meta.virtualService()
                : false);
        service.put("describe", meta != null ? nullToEmpty(meta.describe()) : "");
        service.put("alarmCount", 0L);
        service.put("tags", Map.of("custom", List.of()));
        service.put("componentTypes", loadServiceComponentTypes(serviceId, from, to));
        service.put("bizEvents", List.of());
        service.put("domainManager", Map.of());
        service.put("businessLineName", "");
        service.put("businessLineInfo", List.of());
        service.put("k8sNamespace", "");

        enrichServiceInfoMetrics(service, serviceId, displayName, serviceType, from, to);
        return service;
    }

    private List<Map<String, Object>> buildServiceSeries(
            Map<String, Object> body,
            String serviceId,
            String serviceInstance,
            int limit,
            boolean rankTopServices) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        int interval = intValue(body.get("interval"), 60);
        String metric = stringValue(body.get("metric"), "reqCount");
        boolean asc = "asc".equalsIgnoreCase(stringValue(body.get("sortOrder"), "desc"));
        List<String> serviceTypes = parseStringList(body.get("serviceTypes"));
        Map<String, MetaServicePoint> metaById = serviceTypes.isEmpty() ? Map.of() : loadMetaServiceIndex();

        List<ServiceTrendBucketPoint> buckets = loadServiceBuckets(from, to, interval, serviceId, serviceInstance);
        if (!serviceTypes.isEmpty()) {
            buckets = buckets.stream()
                    .filter(bucket -> matchesListServiceTypes(bucket.service(), serviceTypes, metaById))
                    .toList();
        }

        List<String> targetServices;
        if (rankTopServices) {
            Map<String, Double> totals = new HashMap<>();
            for (ServiceTrendBucketPoint bucket : buckets) {
                double value = trendMetricValue(metric, bucket, interval);
                totals.merge(bucket.service(), value, Double::sum);
            }
            targetServices = totals.entrySet().stream()
                    .sorted(asc
                            ? Map.Entry.comparingByValue()
                            : Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(Math.max(1, limit))
                    .map(Map.Entry::getKey)
                    .toList();
        } else if (serviceId != null) {
            targetServices = List.of(serviceId);
        } else if (buckets.isEmpty()) {
            return List.of();
        } else {
            targetServices = buckets.stream().map(ServiceTrendBucketPoint::service).distinct().toList();
        }

        Set<String> topSet = new LinkedHashSet<>(targetServices);
        Map<String, List<ServiceTrendBucketPoint>> byService;
        if (serviceId != null) {
            byService = Map.of(
                    serviceId,
                    buckets.stream()
                            .filter(row -> PortalServiceIdResolver.matches(serviceId, row.service()))
                            .toList());
        } else {
            byService = buckets.stream()
                    .filter(row -> topSet.contains(row.service()))
                    .collect(Collectors.groupingBy(ServiceTrendBucketPoint::service));
        }

        List<Map<String, Object>> series = new ArrayList<>();
        for (String service : targetServices) {
            Map<Long, ServiceTrendBucketPoint> rowByBucket = byService.getOrDefault(service, List.of()).stream()
                    .collect(Collectors.toMap(
                            ServiceTrendBucketPoint::bucketEpochSec,
                            row -> row,
                            ServicePortalService::mergeTrendBuckets));
            List<List<Object>> values = TimeSeriesFillUtil.fillEpochMsValues(
                    rowByBucket,
                    from,
                    to,
                    interval,
                    row -> trendMetricValue(metric, row, interval));
            Map<String, Object> item = new LinkedHashMap<>();
            if ("typeErrCount".equals(metric)) {
                item.put("tags", Map.of("errorType", "错误"));
            } else if (rankTopServices) {
                item.put("tags", Map.of("service", service));
                item.put("columns", List.of("time", metric));
            }
            item.put("values", values);
            series.add(item);
        }
        return series;
    }

    private List<ServiceTrendBucketPoint> loadServiceBuckets(
            long from,
            long to,
            int interval,
            String serviceId,
            String serviceInstance) {
        try {
            List<String> services = null;
            if (serviceId != null && !serviceId.isBlank()) {
                services = new ArrayList<>(metricServiceIdKeys(serviceId));
            }
            String sql = MetricQueryBuilder.serviceTrendBucketsSql(
                    metricDatabase, from, to, interval, services, serviceInstance);
            return readRepository.queryServiceTrendBuckets(sql);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static ServiceTrendBucketPoint mergeTrendBuckets(
            ServiceTrendBucketPoint left, ServiceTrendBucketPoint right) {
        return new ServiceTrendBucketPoint(
                left.bucketEpochSec(),
                left.service(),
                left.requestCount() + right.requestCount(),
                left.errorCount() + right.errorCount(),
                left.sumDurationNs() + right.sumDurationNs());
    }

    private String resolveServiceInfoId(Map<String, Object> body) {
        String serviceId = resolveServiceId(body);
        if (serviceId != null && !serviceId.isBlank()) {
            return serviceId;
        }
        String serviceName = stringValue(body.get("serviceName"), null);
        if (serviceName != null && !serviceName.isBlank()) {
            return serviceName;
        }
        List<String> serviceNames = parseStringList(body.get("serviceNames"));
        if (!serviceNames.isEmpty()) {
            return serviceNames.get(0);
        }
        return null;
    }

    private MetaServicePoint loadMetaService(String serviceId) {
        try {
            List<MetaServicePoint> rows = readRepository.queryMetaServices(
                    MetricQueryBuilder.metaServiceByIdSql(metricDatabase, serviceId));
            if (rows.isEmpty()) {
                return null;
            }
            MetaServicePoint point = rows.get(0);
            if (point.id() == null || !PortalServiceIdResolver.matches(serviceId, point.id())) {
                return null;
            }
            return point;
        } catch (Exception e) {
            return null;
        }
    }

    private static String resolveServiceDisplayName(String serviceId, MetaServicePoint meta) {
        if (meta != null && meta.name() != null && !meta.name().isBlank()) {
            return meta.name();
        }
        return serviceId;
    }

    private static String resolveExceptionDistServiceLabel(
            String metricKey, String resolvedId, MetaServicePoint meta) {
        String fromMeta = resolveServiceDisplayName(resolvedId, meta);
        if (!isBlank(fromMeta) && !fromMeta.equals(resolvedId)) {
            return fromMeta;
        }
        if (!isBlank(metricKey) && !PortalServiceIdResolver.normalize(metricKey).equals(metricKey)) {
            return metricKey;
        }
        return firstNonBlank(firstNonBlank(fromMeta, metricKey), resolvedId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return isBlank(preferred) ? fallback : preferred;
    }

    /** Prefer Doris {@code service_id} from metrics; request param may carry a display name. */
    private static String resolveMetricServiceId(
            String metricServiceId, String metricServiceName, String requestServiceId) {
        return PortalServiceIdResolver.resolve(metricServiceId, metricServiceName, requestServiceId);
    }

    private static String resolveCallSrcServiceId(
            HttpEndpointPoint inPoint, HttpEndpointPoint outPoint, String requestSrcServiceId) {
        if (outPoint == null) {
            return requestSrcServiceId;
        }
        String inServiceId = resolveMetricServiceId(inPoint.serviceId(), inPoint.service(), null);
        String outServiceId = resolveMetricServiceId(outPoint.serviceId(), outPoint.service(), null);
        if (!outServiceId.equals(inServiceId)) {
            return resolveMetricServiceId(outPoint.serviceId(), outPoint.service(), requestSrcServiceId);
        }
        return requestSrcServiceId;
    }

    private static String resolveCallSrcServiceId(
            DbEndpointPoint inPoint, DbEndpointPoint outPoint, String requestSrcServiceId) {
        if (outPoint == null) {
            return requestSrcServiceId;
        }
        String inServiceId = resolveMetricServiceId(inPoint.serviceId(), inPoint.service(), null);
        String outServiceId = resolveMetricServiceId(outPoint.serviceId(), outPoint.service(), null);
        if (!outServiceId.equals(inServiceId)) {
            return resolveMetricServiceId(outPoint.serviceId(), outPoint.service(), requestSrcServiceId);
        }
        return requestSrcServiceId;
    }

    private static String resolveCallSrcServiceId(
            ComponentEndpointPoint inPoint, ComponentEndpointPoint outPoint, String requestSrcServiceId) {
        if (outPoint == null) {
            return requestSrcServiceId;
        }
        String inServiceId = resolveMetricServiceId(inPoint.serviceId(), inPoint.service(), null);
        String outServiceId = resolveMetricServiceId(outPoint.serviceId(), outPoint.service(), null);
        if (!outServiceId.equals(inServiceId)) {
            return resolveMetricServiceId(outPoint.serviceId(), outPoint.service(), requestSrcServiceId);
        }
        return requestSrcServiceId;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String inferServiceCategory(String serviceId) {
        if (DB_PATTERN.matcher(serviceId).find()) {
            return "db";
        }
        if (MQ_PATTERN.matcher(serviceId).find()) {
            return "mq";
        }
        if (CACHE_PATTERN.matcher(serviceId).find()) {
            return "cache";
        }
        if (REMOTE_PATTERN.matcher(serviceId).find()) {
            return "custom";
        }
        return "web";
    }

    private String resolveListServiceType(
            String serviceId, String serviceName, Map<String, MetaServicePoint> metaById) {
        MetaServicePoint meta = resolveMetaPoint(serviceId, metaById);
        if (meta == null) {
            meta = resolveMetaPoint(serviceName, metaById);
        }
        if (meta != null && Boolean.TRUE.equals(meta.virtualService())) {
            return firstNonBlank(meta.serviceType(), inferServiceCategory(serviceName));
        }
        if (meta != null && !isBlank(meta.serviceType())) {
            return meta.serviceType();
        }
        return inferServiceCategory(firstNonBlank(serviceName, serviceId));
    }

    private static boolean isVirtualServiceName(String serviceName) {
        return serviceName != null && serviceName.startsWith("[");
    }

    private boolean matchesListServiceTypes(
            String serviceName, List<String> serviceTypes, Map<String, MetaServicePoint> metaById) {
        if (serviceTypes.isEmpty()) {
            return true;
        }
        String resolvedId = PortalServiceIdResolver.resolve(null, serviceName);
        String serviceType = resolveListServiceType(resolvedId, serviceName, metaById);
        return serviceTypes.stream().anyMatch(type -> type.equalsIgnoreCase(serviceType));
    }

    private static String inferServiceTypeIcon(String serviceId, String serviceType) {
        return switch (serviceType) {
            case "db" -> inferDbTypeIcon(serviceId);
            case "mq" -> "kafka";
            case "cache" -> "redis";
            case "custom" -> "custom";
            default -> "web";
        };
    }

    private static String inferDbTypeIcon(String serviceId) {
        String component = bracketComponent(serviceId);
        if (component != null) {
            return normalizeDbTypeIcon(component);
        }
        String lower = serviceId.toLowerCase(Locale.ROOT);
        if (lower.contains("elasticsearch") || lower.contains("elastic")) {
            return "elasticsearch";
        }
        if (DB_PATTERN.matcher(serviceId).find()) {
            return "mysql";
        }
        return "db";
    }

    private static String bracketComponent(String serviceId) {
        if (serviceId == null || !serviceId.startsWith("[")) {
            return null;
        }
        int end = serviceId.indexOf(']');
        if (end <= 1) {
            return null;
        }
        return serviceId.substring(1, end);
    }

    private static String normalizeDbTypeIcon(String component) {
        String lower = component.toLowerCase(Locale.ROOT);
        if (lower.contains("elastic")) {
            return "elasticsearch";
        }
        if (lower.contains("mysql") || lower.contains("mariadb")) {
            return "mysql";
        }
        if (lower.contains("postgres")) {
            return "postgres";
        }
        if (lower.contains("mongo")) {
            return "mongo";
        }
        if (lower.contains("redis")) {
            return "redis";
        }
        return lower;
    }

    private String resolveServiceTechnology(
            String metaTechnology, String serviceId, String serviceType, long from, long to) {
        if (!"web".equals(serviceType)) {
            return !isBlank(metaTechnology) ? metaTechnology : inferServiceTypeIcon(serviceId, serviceType);
        }
        LinkedHashSet<String> technologies = new LinkedHashSet<>();
        if (!isBlank(metaTechnology)) {
            for (String part : metaTechnology.split(",")) {
                if (!part.isBlank()) {
                    technologies.add(part.trim());
                }
            }
        }
        if (hasServiceMetricData(serviceId, DorisTableNames.METRIC_JVM, from, to)) {
            technologies.add("jvm");
        }
        if (hasServiceMetricData(serviceId, DorisTableNames.METRIC_SERVICE_HTTP, from, to)) {
            technologies.add("http");
        }
        if (technologies.isEmpty()) {
            return inferServiceTechnology(serviceId, serviceType, from, to);
        }
        return String.join(",", technologies);
    }

    private String inferServiceTechnology(String serviceId, String serviceType, long from, long to) {
        if (!"web".equals(serviceType)) {
            return inferServiceTypeIcon(serviceId, serviceType);
        }
        List<String> technologies = new ArrayList<>();
        if (hasServiceMetricData(serviceId, DorisTableNames.METRIC_JVM, from, to)) {
            technologies.add("jvm");
        }
        if (hasServiceMetricData(serviceId, DorisTableNames.METRIC_SERVICE_HTTP, from, to)) {
            technologies.add("http");
        }
        return technologies.isEmpty() ? "" : String.join(",", technologies);
    }

    private List<String> loadServiceComponentTypes(String serviceId, long from, long to) {
        List<String> componentTypes = new ArrayList<>();
        for (String measurement : SERVICE_COMPONENT_TYPES) {
            String table = componentTypeTable(measurement);
            if (hasServiceMetricData(serviceId, table, from, to)) {
                componentTypes.add(measurement);
            }
        }
        return componentTypes;
    }

    private static String componentTypeTable(String measurement) {
        if ("service.jvm".equals(measurement)) {
            return DorisTableNames.METRIC_JVM;
        }
        return DorisTableNames.metricTable(measurement);
    }

    private boolean hasServiceMetricData(String serviceId, String tableName, long from, long to) {
        try {
            long total = readRepository.queryDistinctCount(
                    MetricQueryBuilder.serviceMetricHasDataSql(metricDatabase, tableName, serviceId, from, to));
            return total > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void enrichServiceInfoMetrics(
            Map<String, Object> service,
            String serviceId,
            String displayName,
            String serviceType,
            long from,
            long to) {
        boolean virtualService = Boolean.TRUE.equals(service.get("virtual_service"));
        if (virtualService) {
            enrichVirtualServiceInfoMetrics(service, serviceId, displayName, serviceType, from, to);
            return;
        }
        try {
            List<ServiceSummaryPoint> summaries = readRepository.queryServiceSummaries(
                    MetricQueryBuilder.serviceSummaryByServiceSql(metricDatabase, serviceId, from, to));
            if (summaries.isEmpty()) {
                return;
            }
            applyServiceSummaryMetrics(service, summaries.get(0), from, to);
        } catch (Exception ignored) {
            // metrics are optional
        }
    }

    private void enrichVirtualServiceInfoMetrics(
            Map<String, Object> service,
            String serviceId,
            String displayName,
            String serviceType,
            long from,
            long to) {
        VirtualServiceKind kind = virtualServiceKindForType(serviceType);
        if (kind == null) {
            return;
        }
        try {
            String sql = MetricQueryBuilder.componentInboundSummaryByServiceSql(
                    metricDatabase, kind.metricTable, serviceId, from, to, kind.typeColumn);
            List<DbServiceSummaryPoint> summaries = readRepository.queryDbServiceSummaries(sql);
            if (summaries.isEmpty()) {
                return;
            }
            DbServiceSummaryPoint summary = summaries.get(0);
            double durationSec = Math.max(1.0, (to - from) / 1000.0);
            long callCnt = summary.requestCount();
            long errCnt = summary.errorCount();
            service.put("callCnt", callCnt);
            service.put("errCnt", errCnt);
            service.put("errRate", callCnt > 0 ? (double) errCnt / callCnt : 0);
            service.put("avgLatency", callCnt > 0 ? summary.sumDurationNs() / callCnt : 0);
            service.put("reqRate", callCnt / durationSec);
            service.put("lastMinReqRate", (callCnt / durationSec) * 60);
            if (kind == VirtualServiceKind.DB && summary.dbType() != null && !summary.dbType().isBlank()) {
                service.put("type", summary.dbType());
            }
        } catch (Exception ignored) {
            // metrics are optional
        }
    }

    private static VirtualServiceKind virtualServiceKindForType(String serviceType) {
        if (serviceType == null || serviceType.isBlank()) {
            return null;
        }
        return switch (serviceType.toLowerCase(Locale.ROOT)) {
            case "db" -> VirtualServiceKind.DB;
            case "mq" -> VirtualServiceKind.MQ;
            case "cache" -> VirtualServiceKind.CACHE;
            case "custom", "remote" -> VirtualServiceKind.REMOTE;
            default -> null;
        };
    }

    private static void applyServiceSummaryMetrics(
            Map<String, Object> service, ServiceSummaryPoint summary, long from, long to) {
        long callCnt = summary.requestCount();
        long errCnt = summary.errorCount();
        double durationSec = Math.max(1.0, (to - from) / 1000.0);
        service.put("callCnt", callCnt);
        service.put("errCnt", errCnt);
        service.put("errRate", callCnt > 0 ? (double) errCnt / callCnt : 0);
        service.put("avgLatency", callCnt > 0 ? summary.sumDurationNs() / callCnt : 0);
        service.put("maxLatency", Math.round(summary.maxDurationNs()));
        service.put("reqRate", callCnt / durationSec);
        service.put("lastMinReqRate", (callCnt / durationSec) * 60);
    }

    private static String resolveServiceId(Map<String, Object> body) {
        String serviceId = stringValue(body.get("serviceId"), null);
        if (serviceId == null) {
            serviceId = stringValue(body.get("sid"), null);
        }
        return serviceId;
    }

    private static String normalizedMetricServiceId(String serviceId) {
        if (isBlank(serviceId)) {
            return null;
        }
        return PortalServiceIdResolver.normalize(serviceId.trim());
    }

    private static Set<String> metricServiceIdKeys(String serviceId) {
        String normalized = normalizedMetricServiceId(serviceId);
        if (normalized == null || normalized.isBlank()) {
            return Set.of();
        }
        return Set.of(normalized);
    }

    private static Map<String, Object> listEnvelope(
            List<Map<String, Object>> rows, long total, int offset, int size) {
        return CommonResponse.listPage(rows, total, offset, size);
    }

    private Map<String, Object> httpCallEndpoints(Map<String, Object> body) {
        Map<String, Object> envelope = emptyEndpointsEnvelope();
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        String serviceId = resolveServiceId(body);
        String srcServiceId = stringValue(body.get("srcServiceId"), null);
        String resourceQuery = resolveResourceQuery(body);
        int offset = intValue(body.get("offset"), 0);
        int size = intValue(body.get("size"), 50);
        String sortField = stringValue(body.get("sortField"), "reqOutCnt");
        String sortOrder = stringValue(body.get("sortOrder"), "desc");

        List<HttpEndpointPoint> inPoints = loadHttpEndpoints(
                serviceId, from, to, resourceQuery, 500, 1, null, srcServiceId);
        List<HttpEndpointPoint> outPoints = loadWebPeerHttpOutEndpoints(
                serviceId, srcServiceId, from, to, resourceQuery, 500);

        List<HttpEndpointPoint> primary = !inPoints.isEmpty() ? inPoints : outPoints;
        List<HttpEndpointPoint> secondary = !inPoints.isEmpty() ? outPoints : List.of();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (HttpEndpointPoint point : primary) {
            rows.add(toCallEndpointRow(point, secondary, serviceId, srcServiceId));
        }

        sortEndpointRows(rows, sortField, sortOrder);
        int total = rows.size();
        List<Map<String, Object>> page = rows.subList(
                Math.min(offset, total),
                Math.min(offset + Math.max(1, size), total));

        envelope.put("total", total);
        envelope.put("size", page.size());
        envelope.put("offset", offset + page.size());
        envelope.put("data", page);
        return envelope;
    }

    private Map<String, Object> httpCallInfo(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        String serviceId = resolveServiceId(body);
        String srcServiceId = stringValue(body.get("srcServiceId"), null);
        String resource = decodeResource(body);

        ComponentCallStats inStats = loadHttpCallStats(serviceId, srcServiceId, from, to, resource, 1, null);
        ComponentCallStats outStats = loadWebPeerOutCallStats(
                DorisTableNames.METRIC_SERVICE_HTTP, serviceId, srcServiceId, from, to, resource,
                (callee, caller, f, t, res) -> loadHttpCallStats(callee, caller, f, t, res, null, 1),
                (caller, f, t, res) -> loadHttpCallStats(caller, null, f, t, res, null, 1));
        return buildCallInfoEnvelope("service.http", inStats, outStats);
    }

    private Map<String, Object> webPeerCallEndpoints(Map<String, Object> body, String tableName) {
        Map<String, Object> envelope = emptyEndpointsEnvelope();
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        String serviceId = resolveServiceId(body);
        String srcServiceId = stringValue(body.get("srcServiceId"), null);
        String resourceQuery = resolveResourceQuery(body);
        int offset = intValue(body.get("offset"), 0);
        int size = intValue(body.get("size"), 50);
        String sortField = stringValue(body.get("sortField"), "reqOutCnt");
        String sortOrder = stringValue(body.get("sortOrder"), "desc");

        List<ComponentEndpointPoint> inPoints = loadComponentEndpoints(
                tableName, serviceId, from, to, resourceQuery, 500, 1, null, srcServiceId);
        List<ComponentEndpointPoint> outPoints = loadWebPeerOutEndpoints(
                tableName, serviceId, srcServiceId, from, to, resourceQuery, 500);

        List<ComponentEndpointPoint> primary = !inPoints.isEmpty() ? inPoints : outPoints;
        List<ComponentEndpointPoint> secondary = !inPoints.isEmpty() ? outPoints : List.of();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ComponentEndpointPoint point : primary) {
            rows.add(toComponentCallEndpointRow(point, secondary, serviceId, srcServiceId));
        }

        sortEndpointRows(rows, sortField, sortOrder);
        int total = rows.size();
        List<Map<String, Object>> page = rows.subList(
                Math.min(offset, total),
                Math.min(offset + Math.max(1, size), total));

        envelope.put("total", total);
        envelope.put("size", page.size());
        envelope.put("offset", offset + page.size());
        envelope.put("data", page);
        return envelope;
    }

    private Map<String, Object> webPeerCallInfo(Map<String, Object> body, String componentType, String tableName) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        String serviceId = resolveServiceId(body);
        String srcServiceId = stringValue(body.get("srcServiceId"), null);
        String resource = decodeResource(body);

        ComponentCallStats inStats = loadComponentCallStats(
                tableName, serviceId, srcServiceId, from, to, resource, 1, null);
        ComponentCallStats outStats = loadWebPeerOutCallStats(
                tableName, serviceId, srcServiceId, from, to, resource,
                (callee, caller, f, t, res) -> loadComponentCallStats(
                        tableName, callee, caller, f, t, res, null, 1),
                (caller, f, t, res) -> loadComponentCallStats(
                        tableName, caller, null, f, t, res, null, 1));
        return buildCallInfoEnvelope(componentType, inStats, outStats);
    }

    private Map<String, Object> componentCallEndpoints(Map<String, Object> body, ComponentPeerSpec spec) {
        Map<String, Object> params = normalizeOutboundGraphStatsBody(body);
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(params, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(params, now);
        String serviceId = resolveServiceId(params);
        String srcServiceId = stringValue(params.get("srcServiceId"), null);
        String resourceQuery = resolveResourceQuery(params);
        int offset = intValue(params.get("offset"), 0);
        int size = intValue(params.get("size"), 50);
        String sortField = stringValue(params.get("sortField"), "reqOutCnt");
        String sortOrder = stringValue(params.get("sortOrder"), "desc");

        List<ComponentEndpointPoint> inPoints = serviceId != null && !serviceId.isBlank()
                ? loadComponentEndpoints(
                        spec.tableName(), serviceId, from, to, resourceQuery, 500, 1, null, srcServiceId)
                : List.of();
        List<ComponentEndpointPoint> outPoints = srcServiceId != null && !srcServiceId.isBlank()
                ? loadComponentEndpoints(
                        spec.tableName(), serviceId, from, to, resourceQuery, 500,
                        spec.outboundIsIn(), spec.outboundIsOut(), srcServiceId)
                : List.of();

        List<ComponentEndpointPoint> primary = !inPoints.isEmpty() ? inPoints : outPoints;
        List<ComponentEndpointPoint> secondary = !inPoints.isEmpty() ? outPoints : List.of();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ComponentEndpointPoint point : primary) {
            rows.add(toComponentCallEndpointRow(point, secondary, serviceId, srcServiceId));
        }

        sortEndpointRows(rows, sortField, sortOrder);
        int total = rows.size();
        List<Map<String, Object>> page = rows.subList(
                Math.min(offset, total),
                Math.min(offset + Math.max(1, size), total));

        Map<String, Object> envelope = emptyEndpointsEnvelope();
        envelope.put("total", total);
        envelope.put("size", page.size());
        envelope.put("offset", offset + page.size());
        envelope.put("data", page);
        return envelope;
    }

    private Map<String, Object> componentCallInfo(
            Map<String, Object> body, String componentType, ComponentPeerSpec spec) {
        Map<String, Object> params = normalizeOutboundGraphStatsBody(body);
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(params, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(params, now);
        String serviceId = resolveServiceId(params);
        String srcServiceId = stringValue(params.get("srcServiceId"), null);
        String resource = decodeResource(params);

        ComponentCallStats inStats = loadComponentCallStats(
                spec.tableName(), serviceId, srcServiceId, from, to, resource, 1, null);
        ComponentCallStats outStats = loadComponentCallStats(
                spec.tableName(), serviceId, srcServiceId, from, to, resource,
                spec.outboundIsIn(), spec.outboundIsOut());
        return buildCallInfoEnvelope(componentType, inStats, outStats);
    }

    @FunctionalInterface
    private interface WebPeerOutStatsLoader {
        ComponentCallStats load(String serviceId, String srcServiceId, long from, long to, String resource);
    }

    @FunctionalInterface
    private interface WebPeerCallerOutStatsLoader {
        ComponentCallStats load(String callerServiceId, long from, long to, String resource);
    }

    /**
     * Legacy portal {@code upDownCallInfo}: outbound uses callee+src+caller with {@code isOut=1}; when client-side
     * metrics tag {@code service=caller}, fall back to caller-only {@code isOut=1}.
     */
    private ComponentCallStats loadWebPeerOutCallStats(
            String tableName,
            String calleeServiceId,
            String callerServiceId,
            long from,
            long to,
            String resource,
            WebPeerOutStatsLoader pairLoader,
            WebPeerCallerOutStatsLoader callerLoader) {
        if (callerServiceId == null || callerServiceId.isBlank()) {
            return ComponentCallStats.empty();
        }
        ComponentCallStats pairStats = pairLoader.load(calleeServiceId, callerServiceId, from, to, resource);
        if (pairStats.requestCount() > 0) {
            return pairStats;
        }
        return callerLoader.load(callerServiceId, from, to, resource);
    }

    private Map<String, Object> webPeerOutboundGraphStats(
            Map<String, Object> base, String calleeServiceId, String callerServiceId) {
        Map<String, Object> pairStats = graphStats(
                buildWebPeerOutboundGraphParams(base, calleeServiceId, callerServiceId));
        if (hasGraphStatsTraffic(pairStats)) {
            return pairStats;
        }
        if (callerServiceId == null || callerServiceId.isBlank()) {
            return pairStats;
        }
        return graphStats(buildWebPeerCallerOutboundGraphParams(base, callerServiceId));
    }

    private static Map<String, Object> buildWebPeerOutboundGraphParams(
            Map<String, Object> base, String calleeServiceId, String callerServiceId) {
        Map<String, Object> params = new LinkedHashMap<>(base);
        params.remove("isIn");
        params.put("isOut", 1);
        if (calleeServiceId != null && !calleeServiceId.isBlank()) {
            params.put("serviceId", calleeServiceId);
            params.put("sid", calleeServiceId);
        }
        if (callerServiceId != null && !callerServiceId.isBlank()) {
            params.put("srcServiceId", callerServiceId);
        }
        return params;
    }

    private static Map<String, Object> buildWebPeerCallerOutboundGraphParams(
            Map<String, Object> base, String callerServiceId) {
        Map<String, Object> params = new LinkedHashMap<>(base);
        params.remove("isIn");
        params.remove("srcServiceId");
        params.put("isOut", 1);
        params.put("serviceId", callerServiceId);
        params.put("sid", callerServiceId);
        return params;
    }

    private static boolean hasGraphStatsTraffic(Map<String, Object> stats) {
        if (stats == null || stats.isEmpty()) {
            return false;
        }
        Object callCnts = stats.get("callCnts");
        if (!(callCnts instanceof Map<?, ?> values)) {
            return false;
        }
        long total = 0;
        for (Object value : values.values()) {
            if (value instanceof Number number) {
                total += number.longValue();
            }
        }
        return total > 0;
    }

    private List<HttpEndpointPoint> loadWebPeerHttpOutEndpoints(
            String calleeServiceId,
            String callerServiceId,
            long from,
            long to,
            String resourceQuery,
            int limit) {
        if (callerServiceId == null || callerServiceId.isBlank()) {
            return List.of();
        }
        List<HttpEndpointPoint> pairPoints = loadHttpEndpoints(
                calleeServiceId, from, to, resourceQuery, limit, null, 1, callerServiceId);
        if (!pairPoints.isEmpty()) {
            return pairPoints;
        }
        return loadHttpEndpoints(callerServiceId, from, to, resourceQuery, limit, null, 1, null);
    }

    private List<ComponentEndpointPoint> loadWebPeerOutEndpoints(
            String tableName,
            String calleeServiceId,
            String callerServiceId,
            long from,
            long to,
            String resourceQuery,
            int limit) {
        if (callerServiceId == null || callerServiceId.isBlank()) {
            return List.of();
        }
        List<ComponentEndpointPoint> pairPoints = loadComponentEndpoints(
                tableName, calleeServiceId, from, to, resourceQuery, limit, null, 1, callerServiceId);
        if (!pairPoints.isEmpty()) {
            return pairPoints;
        }
        return loadComponentEndpoints(
                tableName, callerServiceId, from, to, resourceQuery, limit, null, 1, null);
    }

    private ComponentCallStats loadHttpCallStats(
            String serviceId,
            String srcServiceId,
            long from,
            long to,
            String resourceContains,
            Integer isIn,
            Integer isOut) {
        try {
            Set<String> serviceKeys = metricServiceIdKeys(serviceId);
            Set<String> srcKeys = metricServiceIdKeys(srcServiceId);
            String sql = MetricQueryBuilder.httpCallStatsSummarySql(
                    metricDatabase, serviceKeys, srcKeys, from, to, resourceContains, isIn, isOut);
            return toComponentCallStats(readRepository.queryComponentCallStats(sql));
        } catch (Exception e) {
            return ComponentCallStats.empty();
        }
    }

    private ComponentCallStats loadComponentCallStats(
            String tableName,
            String serviceId,
            String srcServiceId,
            long from,
            long to,
            String resourceContains,
            Integer isIn,
            Integer isOut) {
        try {
            Set<String> serviceKeys = metricServiceIdKeys(serviceId);
            Set<String> srcKeys = metricServiceIdKeys(srcServiceId);
            String sql = MetricQueryBuilder.componentCallStatsSummarySql(
                    metricDatabase, tableName, serviceKeys, srcKeys, from, to, resourceContains, isIn, isOut);
            return toComponentCallStats(readRepository.queryComponentCallStats(sql));
        } catch (Exception e) {
            return ComponentCallStats.empty();
        }
    }

    private static ComponentCallStats toComponentCallStats(ComponentCallStatsPoint point) {
        if (point == null) {
            return ComponentCallStats.empty();
        }
        return new ComponentCallStats(point.requestCount(), point.errorCount(), point.avgDurationMs());
    }

    private static String resolveResourceQuery(Map<String, Object> body) {
        String resourceQuery = stringValue(body.get("resourceQuery"), null);
        if (resourceQuery == null) {
            resourceQuery = stringValue(body.get("resource"), null);
        }
        return resourceQuery;
    }

    private static Map<String, Object> buildCallInfoEnvelope(
            String componentType, HttpCallStats inStats, HttpCallStats outStats) {
        long reqInCnt = inStats.requestCount();
        long reqInErrCnt = inStats.errorCount();
        long reqOutCnt = outStats.requestCount();
        long reqOutErrCnt = outStats.errorCount();
        double reqInTime = avgDurationMsToNs(inStats.avgDurationMs()) * reqInCnt;
        double reqOutTime = avgDurationMsToNs(outStats.avgDurationMs()) * reqOutCnt;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("componentType", componentType);
        data.put("reqInCnt", reqInCnt);
        data.put("reqInErrCnt", reqInErrCnt);
        data.put("reqInTime", reqInTime);
        data.put("reqOutCnt", reqOutCnt);
        data.put("reqOutErrCnt", reqOutErrCnt);
        data.put("reqOutTime", reqOutTime);
        return data;
    }

    private static Map<String, Object> buildCallInfoEnvelope(
            String componentType, ComponentCallStats inStats, ComponentCallStats outStats) {
        long reqInCnt = inStats.requestCount();
        long reqInErrCnt = inStats.errorCount();
        long reqOutCnt = outStats.requestCount();
        long reqOutErrCnt = outStats.errorCount();
        double reqInTime = avgDurationMsToNs(inStats.avgDurationMs()) * reqInCnt;
        double reqOutTime = avgDurationMsToNs(outStats.avgDurationMs()) * reqOutCnt;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("componentType", componentType);
        data.put("reqInCnt", reqInCnt);
        data.put("reqInErrCnt", reqInErrCnt);
        data.put("reqInTime", reqInTime);
        data.put("reqOutCnt", reqOutCnt);
        data.put("reqOutErrCnt", reqOutErrCnt);
        data.put("reqOutTime", reqOutTime);
        return data;
    }

    private List<HttpEndpointPoint> loadHttpEndpoints(
            String serviceId, long from, long to, String urlContains, int limit) {
        return loadHttpEndpoints(serviceId, from, to, urlContains, limit, null, null, null, false);
    }

    private List<HttpEndpointPoint> loadHttpEndpoints(
            String serviceId,
            long from,
            long to,
            String urlContains,
            int limit,
            Integer isIn,
            Integer isOut,
            String srcServiceId) {
        return loadHttpEndpoints(serviceId, from, to, urlContains, limit, isIn, isOut, srcServiceId, false);
    }

    private List<HttpEndpointPoint> loadHttpEndpoints(
            String serviceId,
            long from,
            long to,
            String urlContains,
            int limit,
            Integer isIn,
            Integer isOut,
            String srcServiceId,
            boolean exactUrlMatch) {
        try {
            Set<String> serviceKeys = metricServiceIdKeys(serviceId);
            Set<String> srcKeys = metricServiceIdKeys(srcServiceId);
            String sql = MetricQueryBuilder.httpEndpointSummarySql(
                    metricDatabase, serviceKeys, from, to, limit, null, null, urlContains,
                    isIn, isOut, srcKeys, exactUrlMatch);
            return readRepository.queryHttpEndpoints(sql);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<ComponentEndpointPoint> loadComponentEndpoints(
            String tableName,
            String serviceId,
            long from,
            long to,
            String resourceContains,
            int limit,
            Integer isIn,
            Integer isOut,
            String srcServiceId) {
        try {
            Set<String> serviceKeys = metricServiceIdKeys(serviceId);
            Set<String> srcKeys = metricServiceIdKeys(srcServiceId);
            String sql = MetricQueryBuilder.componentEndpointSummarySql(
                    metricDatabase, tableName, serviceKeys, from, to, limit,
                    resourceContains, isIn, isOut, srcKeys);
            return readRepository.queryComponentEndpoints(sql);
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> dbCallEndpoints(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        String serviceId = resolveServiceId(body);
        String srcServiceId = stringValue(body.get("srcServiceId"), null);
        String resourceQuery = resolveDbResourceQuery(body);
        String sqlOperation = stringValue(body.get("sqlOperationQuery"), null);
        String sqlDatabase = stringValue(body.get("sqlDatabaseQuery"), null);
        int offset = intValue(body.get("offset"), 0);
        int size = intValue(body.get("size"), 50);
        String sortField = stringValue(body.get("sortField"), "reqOutCnt");
        String sortOrder = stringValue(body.get("sortOrder"), "desc");

        List<DbEndpointPoint> inPoints = serviceId != null && !serviceId.isBlank()
                ? loadDbEndpoints(serviceId, from, to, resourceQuery, sqlOperation, sqlDatabase,
                500, 1, null, srcServiceId)
                : List.of();
        List<DbEndpointPoint> outPoints = srcServiceId != null && !srcServiceId.isBlank()
                ? loadDbEndpoints(serviceId, from, to, resourceQuery, sqlOperation, sqlDatabase,
                500, null, 1, srcServiceId)
                : List.of();

        List<DbEndpointPoint> primary = !inPoints.isEmpty() ? inPoints : outPoints;
        List<DbEndpointPoint> secondary = !inPoints.isEmpty() ? outPoints : List.of();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (DbEndpointPoint point : primary) {
            rows.add(toDbCallEndpointRow(point, secondary, serviceId, srcServiceId));
        }

        sortEndpointRows(rows, sortField, sortOrder);
        int total = rows.size();
        List<Map<String, Object>> page = rows.subList(
                Math.min(offset, total),
                Math.min(offset + Math.max(1, size), total));

        Map<String, Object> envelope = emptyEndpointsEnvelope();
        envelope.put("total", total);
        envelope.put("size", page.size());
        envelope.put("offset", offset + page.size());
        envelope.put("data", page);
        return envelope;
    }

    private Map<String, Object> dbCallInfo(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        String serviceId = resolveServiceId(body);
        String srcServiceId = stringValue(body.get("srcServiceId"), null);
        String resource = decodeResource(body);
        String sqlOperation = stringValue(body.get("sqlOperationQuery"), null);
        String sqlDatabase = stringValue(body.get("sqlDatabaseQuery"), null);

        List<DbEndpointPoint> inPoints = serviceId != null && !serviceId.isBlank()
                ? loadDbEndpoints(serviceId, from, to, resource, sqlOperation, sqlDatabase,
                500, 1, null, srcServiceId)
                : List.of();
        List<DbEndpointPoint> outPoints = srcServiceId != null && !srcServiceId.isBlank()
                ? loadDbEndpoints(serviceId, from, to, resource, sqlOperation, sqlDatabase,
                500, null, 1, srcServiceId)
                : List.of();

        DbCallStats inStats = summarizeDbEndpoints(inPoints, resource);
        DbCallStats outStats = summarizeDbEndpoints(outPoints, resource);

        long reqInCnt = inStats.requestCount();
        long reqInErrCnt = inStats.errorCount();
        long reqOutCnt = outStats.requestCount();
        long reqOutErrCnt = outStats.errorCount();
        double reqInTime = avgDurationMsToNs(inStats.avgDurationMs()) * reqInCnt;
        double reqOutTime = avgDurationMsToNs(outStats.avgDurationMs()) * reqOutCnt;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("componentType", "service.db");
        data.put("reqInCnt", reqInCnt);
        data.put("reqInErrCnt", reqInErrCnt);
        data.put("reqInTime", reqInTime);
        data.put("reqOutCnt", reqOutCnt);
        data.put("reqOutErrCnt", reqOutErrCnt);
        data.put("reqOutTime", reqOutTime);
        return data;
    }

    private static String resolveDbResourceQuery(Map<String, Object> body) {
        String resourceQuery = stringValue(body.get("resourceQuery"), null);
        if (resourceQuery == null) {
            resourceQuery = stringValue(body.get("resource"), null);
        }
        return resourceQuery;
    }

    private List<DbEndpointPoint> loadDbEndpoints(
            String serviceId,
            long from,
            long to,
            String resourceContains,
            String sqlOperation,
            String sqlDatabase,
            int limit,
            Integer isIn,
            Integer isOut,
            String srcServiceId) {
        try {
            Set<String> serviceKeys = metricServiceIdKeys(serviceId);
            Set<String> srcKeys = metricServiceIdKeys(srcServiceId);
            String sql = MetricQueryBuilder.dbEndpointSummarySql(
                    metricDatabase, serviceKeys, from, to, limit, resourceContains,
                    sqlOperation, sqlDatabase, isIn, isOut, srcKeys);
            return readRepository.queryDbEndpoints(sql);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<DbSlowSqlTopPoint> loadDbSlowSqlTop(
            String serviceId,
            long from,
            long to,
            String resourceContains,
            String serviceInstance,
            int limit,
            Integer isIn,
            Integer isOut,
            Integer isSlow,
            String srcServiceId) {
        try {
            Set<String> serviceKeys = metricServiceIdKeys(serviceId);
            Set<String> srcKeys = metricServiceIdKeys(srcServiceId);
            String sql = MetricQueryBuilder.dbSlowSqlTopSummarySql(
                    metricDatabase, serviceKeys, from, to, limit, resourceContains,
                    serviceInstance, isIn, isOut, isSlow, srcKeys);
            return readRepository.queryDbSlowSqlTop(sql);
        } catch (Exception e) {
            return List.of();
        }
    }

    private record HttpCallStats(long requestCount, long errorCount, double avgDurationMs) {
        static HttpCallStats empty() {
            return new HttpCallStats(0, 0, 0);
        }
    }

    private static HttpCallStats summarizeHttpEndpoints(List<HttpEndpointPoint> points, String resource) {
        if (points == null || points.isEmpty()) {
            return HttpCallStats.empty();
        }
        List<HttpEndpointPoint> scoped = points;
        if (resource != null && !resource.isBlank()) {
            scoped = points.stream()
                    .filter(point -> resource.equals(point.url()))
                    .toList();
        }
        if (scoped.isEmpty()) {
            return HttpCallStats.empty();
        }
        long requestCount = scoped.stream().mapToLong(HttpEndpointPoint::requestCount).sum();
        long errorCount = scoped.stream().mapToLong(HttpEndpointPoint::errorCount).sum();
        if (requestCount == 0) {
            return HttpCallStats.empty();
        }
        double avgDurationMs = scoped.stream()
                .mapToDouble(point -> point.avgDuration() * point.requestCount())
                .sum() / requestCount;
        return new HttpCallStats(requestCount, errorCount, avgDurationMs);
    }

    private record DbCallStats(long requestCount, long errorCount, double avgDurationMs) {
        static DbCallStats empty() {
            return new DbCallStats(0, 0, 0);
        }
    }

    private static DbCallStats summarizeDbEndpoints(List<DbEndpointPoint> points, String resource) {
        if (points == null || points.isEmpty()) {
            return DbCallStats.empty();
        }
        List<DbEndpointPoint> scoped = points;
        if (resource != null && !resource.isBlank()) {
            scoped = points.stream()
                    .filter(point -> resource.equals(point.resource()))
                    .toList();
        }
        if (scoped.isEmpty()) {
            return DbCallStats.empty();
        }
        long requestCount = scoped.stream().mapToLong(DbEndpointPoint::requestCount).sum();
        long errorCount = scoped.stream().mapToLong(DbEndpointPoint::errorCount).sum();
        if (requestCount == 0) {
            return DbCallStats.empty();
        }
        double avgDurationMs = scoped.stream()
                .mapToDouble(point -> point.avgDuration() * point.requestCount())
                .sum() / requestCount;
        return new DbCallStats(requestCount, errorCount, avgDurationMs);
    }

    private record ComponentCallStats(long requestCount, long errorCount, double avgDurationMs) {
        static ComponentCallStats empty() {
            return new ComponentCallStats(0, 0, 0);
        }
    }

    private static ComponentCallStats summarizeComponentEndpoints(
            List<ComponentEndpointPoint> points, String resource) {
        if (points == null || points.isEmpty()) {
            return ComponentCallStats.empty();
        }
        List<ComponentEndpointPoint> scoped = points;
        if (resource != null && !resource.isBlank()) {
            scoped = points.stream()
                    .filter(point -> resource.equals(point.resource()))
                    .toList();
        }
        if (scoped.isEmpty()) {
            return ComponentCallStats.empty();
        }
        long requestCount = scoped.stream().mapToLong(ComponentEndpointPoint::requestCount).sum();
        long errorCount = scoped.stream().mapToLong(ComponentEndpointPoint::errorCount).sum();
        if (requestCount == 0) {
            return ComponentCallStats.empty();
        }
        double avgDurationMs = scoped.stream()
                .mapToDouble(point -> point.avgDuration() * point.requestCount())
                .sum() / requestCount;
        return new ComponentCallStats(requestCount, errorCount, avgDurationMs);
    }

    private static Map<String, Object> toDbCallEndpointRow(
            DbEndpointPoint primary,
            List<DbEndpointPoint> secondary,
            String serviceId,
            String srcServiceId) {
        DbEndpointPoint inPoint = primary;
        DbEndpointPoint outPoint = secondary.stream()
                .filter(point -> point.resource().equals(primary.resource())
                        && (point.sqlOperation() == null
                        || primary.sqlOperation() == null
                        || point.sqlOperation().equals(primary.sqlOperation())))
                .findFirst()
                .orElse(null);
        if (outPoint == null && secondary.isEmpty()) {
            inPoint = primary;
            outPoint = primary;
        } else if (outPoint == null) {
            outPoint = primary;
        }

        long reqInCnt = inPoint.requestCount();
        long reqInErrCnt = inPoint.errorCount();
        long reqOutCnt = outPoint != null ? outPoint.requestCount() : 0;
        long reqOutErrCnt = outPoint != null ? outPoint.errorCount() : 0;
        double reqInAvg = avgDurationMsToNs(inPoint.avgDuration());
        double reqOutAvg = outPoint != null ? avgDurationMsToNs(outPoint.avgDuration()) : 0;
        double reqInSumReadRows = inPoint.sumReadRows();
        double reqInSumUpdateRows = inPoint.sumUpdateRows();
        double reqOutSumReadRows = outPoint != null ? outPoint.sumReadRows() : 0;
        double reqOutSumUpdateRows = outPoint != null ? outPoint.sumUpdateRows() : 0;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("serviceId", resolveMetricServiceId(inPoint.serviceId(), inPoint.service(), serviceId));
        row.put("serviceName", inPoint.service());
        String resolvedSrcServiceId = resolveCallSrcServiceId(inPoint, outPoint, srcServiceId);
        if (resolvedSrcServiceId != null && !resolvedSrcServiceId.isBlank()) {
            row.put("srcServiceId", PortalServiceIdResolver.normalize(resolvedSrcServiceId));
        }
        row.put("resource", inPoint.resource());
        row.put("sqlOperation", inPoint.sqlOperation());
        row.put("dbType", inPoint.dbType());
        row.put("sqlDatabase", inPoint.sqlDatabase());
        row.put("reqInCnt", reqInCnt);
        row.put("reqInErrCnt", reqInErrCnt);
        row.put("reqInAvgLatency", reqInAvg);
        row.put("reqInErrRate", reqInCnt > 0 ? (double) reqInErrCnt / reqInCnt : 0);
        row.put("reqOutCnt", reqOutCnt);
        row.put("reqOutErrCnt", reqOutErrCnt);
        row.put("reqOutAvgLatency", reqOutAvg);
        row.put("reqOutErrRate", reqOutCnt > 0 ? (double) reqOutErrCnt / reqOutCnt : 0);
        row.put("reqInSumReadRows", reqInSumReadRows);
        row.put("reqInAvgReadRows", reqInCnt > 0 ? reqInSumReadRows / reqInCnt : 0);
        row.put("reqInSumUpdateRows", reqInSumUpdateRows);
        row.put("reqInAvgUpdateRows", reqInCnt > 0 ? reqInSumUpdateRows / reqInCnt : 0);
        row.put("reqOutSumReadRows", reqOutSumReadRows);
        row.put("reqOutAvgReadRows", reqOutCnt > 0 ? reqOutSumReadRows / reqOutCnt : 0);
        row.put("reqOutSumUpdateRows", reqOutSumUpdateRows);
        row.put("reqOutAvgUpdateRows", reqOutCnt > 0 ? reqOutSumUpdateRows / reqOutCnt : 0);
        return row;
    }

    private List<ExceptionDistPoint> loadExceptionDistPoints(
            String groupBy,
            long from,
            long to,
            String serviceId,
            String serviceInstance,
            String resourceQuery,
            String exception,
            String rootResourceQuery) {
        String resourceFilter = resourceQuery != null ? resourceQuery : rootResourceQuery;
        try {
            if ("serviceId".equals(groupBy)) {
                String sql = MetricQueryBuilder.serviceErrorDistSql(metricDatabase, from, to, null);
                return readRepository.queryExceptionDist(sql, groupBy);
            }
            if ("resource".equals(groupBy)) {
                String sql = MetricQueryBuilder.exceptionDistFromMetricResourceSql(
                        metricDatabase, from, to, serviceId, serviceInstance, resourceFilter);
                return readRepository.queryExceptionDist(sql, groupBy);
            }
            if ("rootResource".equals(groupBy)) {
                String sql = MetricQueryBuilder.exceptionDistFromMetricRootResourceSql(
                        metricDatabase, from, to, serviceId, serviceInstance, resourceFilter);
                return readRepository.queryExceptionDist(sql, groupBy);
            }
            if ("exceptionName".equals(groupBy)) {
                String sql = MetricQueryBuilder.exceptionDistFromMetricSql(
                        metricDatabase, from, to, serviceId, serviceInstance, exception);
                return readRepository.queryExceptionDist(sql, groupBy);
            }
            if ("serviceId,serviceInstance".equals(groupBy)) {
                String sql = MetricQueryBuilder.exceptionDistFromMetricServiceInstanceSql(
                        metricDatabase, from, to, serviceId, serviceInstance);
                return readRepository.queryExceptionDist(sql, groupBy);
            }
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Map<String, Object> toCallEndpointRow(
            HttpEndpointPoint inPoint,
            List<HttpEndpointPoint> outPoints,
            String serviceId,
            String srcServiceId) {
        HttpEndpointPoint outPoint = outPoints.stream()
                .filter(point -> point.url().equals(inPoint.url())
                        && (point.httpMethod() == null
                        || inPoint.httpMethod() == null
                        || point.httpMethod().equals(inPoint.httpMethod())))
                .findFirst()
                .orElse(null);
        long reqInCnt = inPoint.requestCount();
        long reqInErrCnt = inPoint.errorCount();
        long reqOutCnt = outPoint != null ? outPoint.requestCount() : 0;
        long reqOutErrCnt = outPoint != null ? outPoint.errorCount() : 0;
        double reqInAvg = avgDurationMsToNs(inPoint.avgDuration());
        double reqOutAvg = outPoint != null ? avgDurationMsToNs(outPoint.avgDuration()) : 0;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("serviceId", resolveMetricServiceId(inPoint.serviceId(), inPoint.service(), serviceId));
        row.put("serviceName", inPoint.service());
        String resolvedSrcServiceId = resolveCallSrcServiceId(inPoint, outPoint, srcServiceId);
        if (resolvedSrcServiceId != null && !resolvedSrcServiceId.isBlank()) {
            row.put("srcServiceId", PortalServiceIdResolver.normalize(resolvedSrcServiceId));
        }
        row.put("resource", inPoint.url());
        row.put("httpMethod", inPoint.httpMethod());
        row.put("reqInCnt", reqInCnt);
        row.put("reqInErrCnt", reqInErrCnt);
        row.put("reqInAvgLatency", reqInAvg);
        row.put("reqInErrRate", reqInCnt > 0 ? (double) reqInErrCnt / reqInCnt : 0);
        row.put("reqOutCnt", reqOutCnt);
        row.put("reqOutErrCnt", reqOutErrCnt);
        row.put("reqOutAvgLatency", reqOutAvg);
        row.put("reqOutErrRate", reqOutCnt > 0 ? (double) reqOutErrCnt / reqOutCnt : 0);
        return row;
    }

    private static Map<String, Object> toComponentCallEndpointRow(
            ComponentEndpointPoint primary,
            List<ComponentEndpointPoint> secondary,
            String serviceId,
            String srcServiceId) {
        ComponentEndpointPoint inPoint = primary;
        ComponentEndpointPoint outPoint = secondary.stream()
                .filter(point -> componentEndpointKey(point).equals(componentEndpointKey(primary)))
                .findFirst()
                .orElse(null);
        if (outPoint == null && secondary.isEmpty()) {
            inPoint = primary;
            outPoint = primary;
        } else if (outPoint == null) {
            outPoint = primary;
        }

        long reqInCnt = inPoint.requestCount();
        long reqInErrCnt = inPoint.errorCount();
        long reqOutCnt = outPoint != null ? outPoint.requestCount() : 0;
        long reqOutErrCnt = outPoint != null ? outPoint.errorCount() : 0;
        double reqInAvg = avgDurationMsToNs(inPoint.avgDuration());
        double reqOutAvg = outPoint != null ? avgDurationMsToNs(outPoint.avgDuration()) : 0;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("serviceId", resolveMetricServiceId(inPoint.serviceId(), inPoint.service(), serviceId));
        row.put("serviceName", inPoint.service());
        String resolvedSrcServiceId = resolveCallSrcServiceId(inPoint, outPoint, srcServiceId);
        if (resolvedSrcServiceId != null && !resolvedSrcServiceId.isBlank()) {
            row.put("srcServiceId", PortalServiceIdResolver.normalize(resolvedSrcServiceId));
        }
        row.put("resource", inPoint.resource());
        applyComponentEndpointTags(row, inPoint.tags());
        row.put("reqInCnt", reqInCnt);
        row.put("reqInErrCnt", reqInErrCnt);
        row.put("reqInAvgLatency", reqInAvg);
        row.put("reqInErrRate", reqInCnt > 0 ? (double) reqInErrCnt / reqInCnt : 0);
        row.put("reqOutCnt", reqOutCnt);
        row.put("reqOutErrCnt", reqOutErrCnt);
        row.put("reqOutAvgLatency", reqOutAvg);
        row.put("reqOutErrRate", reqOutCnt > 0 ? (double) reqOutErrCnt / reqOutCnt : 0);

        double reqInSumReadRows = inPoint.sumReadRows();
        double reqInSumUpdateRows = inPoint.sumUpdateRows();
        double reqOutSumReadRows = outPoint != null ? outPoint.sumReadRows() : 0;
        double reqOutSumUpdateRows = outPoint != null ? outPoint.sumUpdateRows() : 0;
        row.put("reqInSumReadRows", reqInSumReadRows);
        row.put("reqInAvgReadRows", reqInCnt > 0 ? reqInSumReadRows / reqInCnt : 0);
        row.put("reqInSumUpdateRows", reqInSumUpdateRows);
        row.put("reqInAvgUpdateRows", reqInCnt > 0 ? reqInSumUpdateRows / reqInCnt : 0);
        row.put("reqOutSumReadRows", reqOutSumReadRows);
        row.put("reqOutAvgReadRows", reqOutCnt > 0 ? reqOutSumReadRows / reqOutCnt : 0);
        row.put("reqOutSumUpdateRows", reqOutSumUpdateRows);
        row.put("reqOutAvgUpdateRows", reqOutCnt > 0 ? reqOutSumUpdateRows / reqOutCnt : 0);

        double reqInSumReqBodyLength = inPoint.sumReqBodyLength();
        double reqInSumRespBodyLength = inPoint.sumRespBodyLength();
        double reqOutSumReqBodyLength = outPoint != null ? outPoint.sumReqBodyLength() : 0;
        double reqOutSumRespBodyLength = outPoint != null ? outPoint.sumRespBodyLength() : 0;
        row.put("reqInSumReqBodyLength", reqInSumReqBodyLength);
        row.put("reqInAvgReqBodyLength", reqInCnt > 0 ? reqInSumReqBodyLength / reqInCnt : 0);
        row.put("reqInSumRespBodyLength", reqInSumRespBodyLength);
        row.put("reqInAvgRespBodyLength", reqInCnt > 0 ? reqInSumRespBodyLength / reqInCnt : 0);
        row.put("reqOutSumReqBodyLength", reqOutSumReqBodyLength);
        row.put("reqOutAvgReqBodyLength", reqOutCnt > 0 ? reqOutSumReqBodyLength / reqOutCnt : 0);
        row.put("reqOutSumRespBodyLength", reqOutSumRespBodyLength);
        row.put("reqOutAvgRespBodyLength", reqOutCnt > 0 ? reqOutSumRespBodyLength / reqOutCnt : 0);

        double reqInSumDelay = inPoint.sumDelay();
        double reqInSumMqBodyLength = inPoint.sumMqBodyLength();
        double reqOutSumDelay = outPoint != null ? outPoint.sumDelay() : 0;
        double reqOutSumMqBodyLength = outPoint != null ? outPoint.sumMqBodyLength() : 0;
        row.put("reqInSumDelay", reqInSumDelay);
        row.put("reqInAvgDelay", reqInCnt > 0 ? reqInSumDelay / reqInCnt : 0);
        row.put("reqInSumMqBodyLength", reqInSumMqBodyLength);
        row.put("reqInAvgMqBodyLength", reqInCnt > 0 ? reqInSumMqBodyLength / reqInCnt : 0);
        row.put("reqOutSumDelay", reqOutSumDelay);
        row.put("reqOutAvgDelay", reqOutCnt > 0 ? reqOutSumDelay / reqOutCnt : 0);
        row.put("reqOutSumMqBodyLength", reqOutSumMqBodyLength);
        row.put("reqOutAvgMqBodyLength", reqOutCnt > 0 ? reqOutSumMqBodyLength / reqOutCnt : 0);
        return row;
    }

    private static String componentEndpointKey(ComponentEndpointPoint point) {
        StringBuilder key = new StringBuilder(nullToEmpty(point.resource()));
        if (point.tags() != null) {
            point.tags().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> key.append('\0').append(entry.getKey()).append('=').append(entry.getValue()));
        }
        return key.toString();
    }

    private static void applyComponentEndpointTags(Map<String, Object> row, Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        putIfPresent(row, "type", tags.get("type"));
        putIfPresent(row, "statusCode", tags.get("statusCode"));
        putIfPresent(row, "httpMethod", tags.get("httpMethod"));
        putIfPresent(row, "command", tags.get("command"));
        putIfPresent(row, "topic", tags.get("topic"));
        putIfPresent(row, "group", tags.get("group"));
        putIfPresent(row, "partition", tags.get("partition"));
        putIfPresent(row, "broker", tags.get("broker"));
        putIfPresent(row, "indices", tags.get("indices"));
        putIfPresent(row, "method", tags.get("method"));
        putIfPresent(row, "url", tags.get("url"));
        putIfPresent(row, "operation", tags.get("operation"));
        putIfPresent(row, "configType", tags.get("config_type"));
        putIfPresent(row, "remoteType", tags.get("remoteType"));
    }

    private static Map<String, Object> emptyEndpointsEnvelope() {
        return CommonResponse.listPage(List.of(), 0, 0, 0);
    }

    private static Map<String, Object> toEndpointRow(HttpEndpointPoint point, double durationSec) {
        long callCnt = point.requestCount();
        long errCnt = point.errorCount();
        long slowCnt = 0;
        long normalCnt = Math.max(0, callCnt - errCnt - slowCnt);
        double errRate = callCnt > 0 ? (double) errCnt / callCnt : 0;
        long avgLatencyNs = avgDurationMsToNs(point.avgDuration());
        long maxLatencyNs = Math.round(point.maxDurationNs());

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("resource", point.url());
        row.put("serviceName", point.service());
        row.put("serviceId", PortalServiceIdResolver.resolve(point.serviceId(), point.service()));
        row.put("alias", "");
        row.put("method", point.httpMethod());
        row.put("type", point.httpMethod());
        row.put("callCnt", callCnt);
        row.put("errCnt", errCnt);
        row.put("slowCnt", slowCnt);
        row.put("normalCnt", normalCnt);
        row.put("errRate", errRate);
        row.put("reqRate", callCnt / durationSec);
        row.put("avgLatency", avgLatencyNs);
        row.put("maxLatency", maxLatencyNs);
        row.put("datasource", "OTLP");
        row.put("progressValue", Map.of("callCnt", 0, "avgLatency", 0, "errRate", 0));
        return row;
    }

    private static Map<String, Object> toComponentEndpointRow(ComponentEndpointPoint point, double durationSec) {
        long callCnt = point.requestCount();
        long errCnt = point.errorCount();
        long slowCnt = 0;
        long normalCnt = Math.max(0, callCnt - errCnt - slowCnt);
        double errRate = callCnt > 0 ? (double) errCnt / callCnt : 0;
        long avgLatencyNs = avgDurationMsToNs(point.avgDuration());
        long maxLatencyNs = Math.round(point.maxDurationNs());
        String rpcType = point.tags() != null ? point.tags().get("type") : null;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("resource", point.resource());
        row.put("serviceName", point.service());
        row.put("serviceId", PortalServiceIdResolver.resolve(point.serviceId(), point.service()));
        row.put("alias", "");
        row.put("method", rpcType);
        row.put("type", rpcType);
        applyComponentEndpointTags(row, point.tags());
        row.put("callCnt", callCnt);
        row.put("errCnt", errCnt);
        row.put("slowCnt", slowCnt);
        row.put("normalCnt", normalCnt);
        row.put("errRate", errRate);
        row.put("reqRate", callCnt / durationSec);
        row.put("avgLatency", avgLatencyNs);
        row.put("maxLatency", maxLatencyNs);
        row.put("datasource", "OTLP");
        row.put("progressValue", Map.of("callCnt", 0, "avgLatency", 0, "errRate", 0));
        return row;
    }

    private static Map<String, Object> toDbEndpointRow(DbEndpointPoint point, double durationSec) {
        long callCnt = point.requestCount();
        long errCnt = point.errorCount();
        long slowCnt = 0;
        long normalCnt = Math.max(0, callCnt - errCnt - slowCnt);
        double errRate = callCnt > 0 ? (double) errCnt / callCnt : 0;
        long avgLatencyNs = avgDurationMsToNs(point.avgDuration());
        long maxLatencyNs = Math.round(point.maxDurationNs());

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("resource", point.resource());
        row.put("serviceName", point.service());
        row.put("serviceId", PortalServiceIdResolver.resolve(point.serviceId(), point.service()));
        row.put("alias", "");
        row.put("sqlOperation", point.sqlOperation());
        row.put("dbType", point.dbType());
        row.put("sqlDatabase", point.sqlDatabase());
        row.put("callCnt", callCnt);
        row.put("errCnt", errCnt);
        row.put("slowCnt", slowCnt);
        row.put("normalCnt", normalCnt);
        row.put("errRate", errRate);
        row.put("reqRate", callCnt / durationSec);
        row.put("avgLatency", avgLatencyNs);
        row.put("maxLatency", maxLatencyNs);
        row.put("datasource", "OTLP");
        row.put("progressValue", Map.of("callCnt", 0, "avgLatency", 0, "errRate", 0));
        return row;
    }

    private static Map<String, Object> toSlowSqlTopRow(DbSlowSqlTopPoint point, double durationSec) {
        long callCnt = point.requestCount();
        long errCnt = point.errorCount();
        double errRate = callCnt > 0 ? (double) errCnt / callCnt : 0;
        long avgLatencyNs = Math.round(point.avgTimeNs());
        long maxDurationNs = Math.round(point.maxDurationNs());
        long minDurationNs = Math.round(point.minDurationNs());

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("resource", point.resource());
        row.put("callCnt", callCnt);
        row.put("errCnt", errCnt);
        row.put("errRate", errRate);
        row.put("reqRate", callCnt / durationSec);
        row.put("avgLatency", avgLatencyNs);
        row.put("maxDuration", maxDurationNs);
        row.put("minDuration", minDurationNs);
        row.put("srcServiceCnt", point.srcServiceCnt());
        row.put("componentType", "service.db");
        return row;
    }

    private static long avgDurationMsToNs(double avgDurationMs) {
        return (long) (avgDurationMs * 1_000_000);
    }

    private static Map<String, DbServiceSummaryPoint> uniqueVirtualServiceMetrics(
            Map<String, DbServiceSummaryPoint> metricsByService) {
        LinkedHashMap<String, DbServiceSummaryPoint> unique = new LinkedHashMap<>();
        for (DbServiceSummaryPoint point : metricsByService.values()) {
            unique.putIfAbsent(virtualServiceRowKey(point.service(), point.serviceId()), point);
        }
        return unique;
    }

    private Map<String, DbServiceSummaryPoint> loadVirtualServiceMetrics(
            VirtualServiceKind kind, long from, long to) {
        try {
            String sql = switch (kind) {
                case DB -> MetricQueryBuilder.dbServiceSummarySql(metricDatabase, from, to);
                case MQ -> MetricQueryBuilder.mqProducerServiceSummarySql(metricDatabase, from, to);
                case CACHE -> MetricQueryBuilder.componentServiceSummarySql(
                        metricDatabase, kind.metricTable, from, to, "service", "service_id", kind.typeColumn);
                case REMOTE -> MetricQueryBuilder.componentServiceSummarySql(
                        metricDatabase, kind.metricTable, from, to, "service", "service_id", kind.typeColumn);
            };
            return indexComponentServiceMetrics(readRepository.queryDbServiceSummaries(sql));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, DbServiceSummaryPoint> loadMqConsumerMetrics(long from, long to) {
        try {
            String sql = MetricQueryBuilder.mqConsumerServiceSummarySql(metricDatabase, from, to);
            return indexComponentServiceMetrics(readRepository.queryDbServiceSummaries(sql));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Map<String, DbServiceSummaryPoint> indexComponentServiceMetrics(
            List<DbServiceSummaryPoint> points) {
        Map<String, DbServiceSummaryPoint> metrics = new LinkedHashMap<>();
        for (DbServiceSummaryPoint point : points) {
            metrics.put(point.service(), point);
            String resolvedId = PortalServiceIdResolver.resolve(point.serviceId(), point.service());
            metrics.putIfAbsent(resolvedId, point);
        }
        return metrics;
    }

    private List<Map<String, Object>> loadVirtualServiceCatalogRows(
            Map<String, Object> body, VirtualServiceKind kind, long from, long to) {
        Map<String, Object> catalogBody = new LinkedHashMap<>(body);
        catalogBody.put("serviceType", kind.catalogServiceType);
        catalogBody.put("virtualService", 1);
        List<Map<String, Object>> rows = loadBasicServiceRows(catalogBody);
        if (rows.isEmpty() && kind == VirtualServiceKind.REMOTE) {
            catalogBody.put("serviceType", "custom");
            rows = loadBasicServiceRows(catalogBody);
        }
        if (!rows.isEmpty()) {
            return rows;
        }
        return loadVirtualServicesFromMetrics(kind, from, to);
    }

    private List<Map<String, Object>> loadVirtualServicesFromMetrics(
            VirtualServiceKind kind, long from, long to) {
        try {
            return readRepository.queryDistinctTags(
                            MetricQueryBuilder.componentDistinctServicesSql(
                                    metricDatabase, kind.metricTable, from, to, "service")).stream()
                    .map(service -> toVirtualServiceCatalogRow(kind, service))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> toVirtualServiceCatalogRow(VirtualServiceKind kind, String service) {
        String resolvedId = PortalServiceIdResolver.resolve(null, service);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", resolvedId);
        row.put("serviceId", resolvedId);
        row.put("name", service);
        row.put("service", service);
        row.put("service_type", kind.rowServiceType);
        row.put("type", inferServiceTypeIcon(service, kind.rowServiceType));
        row.put("virtual_service", true);
        return row;
    }

    private static DbServiceSummaryPoint resolveVirtualServiceMetrics(
            Map<String, DbServiceSummaryPoint> metricsByService, String name, String serviceId) {
        DbServiceSummaryPoint metrics = metricsByService.get(name);
        if (metrics != null) {
            return metrics;
        }
        return metricsByService.get(serviceId);
    }

    private Map<String, Object> toVirtualServiceListRow(
            VirtualServiceKind kind,
            Map<String, Object> catalog,
            DbServiceSummaryPoint metrics,
            DbServiceSummaryPoint consumerMetrics,
            double durationSec) {
        String name = stringValue(catalog.get("name"), stringValue(catalog.get("service"), ""));
        String serviceId = stringValue(catalog.get("id"), stringValue(catalog.get("serviceId"), name));
        String type = stringValue(catalog.get("type"), inferServiceTypeIcon(name, kind.rowServiceType));
        if (metrics != null) {
            if (metrics.dbType() != null && !metrics.dbType().isBlank()) {
                type = metrics.dbType();
            }
            return buildVirtualServiceListRow(kind, name, serviceId, type, metrics, consumerMetrics, durationSec);
        }
        return emptyVirtualServiceListRow(kind, name, serviceId, type);
    }

    private Map<String, Object> toVirtualServiceListRowFromMetrics(
            VirtualServiceKind kind,
            DbServiceSummaryPoint metrics,
            DbServiceSummaryPoint consumerMetrics,
            double durationSec) {
        String serviceId = PortalServiceIdResolver.resolve(metrics.serviceId(), metrics.service());
        String type = metrics.dbType() != null && !metrics.dbType().isBlank()
                ? metrics.dbType()
                : inferServiceTypeIcon(metrics.service(), kind.rowServiceType);
        return buildVirtualServiceListRow(
                kind, metrics.service(), serviceId, type, metrics, consumerMetrics, durationSec);
    }

    private Map<String, Object> emptyVirtualServiceListRow(
            VirtualServiceKind kind, String name, String serviceId, String type) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("serviceId", serviceId);
        row.put("name", name);
        row.put("service", name);
        row.put("callCnt", 0L);
        row.put("errCnt", 0L);
        row.put("slowCnt", 0L);
        row.put("errRate", 0.0);
        row.put("slowPercent", 0.0);
        row.put("avgLatency", 0L);
        row.put("reqRate", 0.0);
        row.put("lastMinReqRate", 0.0);
        row.put("service_type", kind.rowServiceType);
        row.put("type", type);
        row.put("datasource", "OTLP");
        row.put("virtual_service", true);
        putVirtualServiceAlarmFields(kind, row);
        if (kind == VirtualServiceKind.MQ) {
            putMqSideMetrics(row, null, null);
        } else if (kind == VirtualServiceKind.REMOTE) {
            putRemoteLatencyFields(row, 0L);
        }
        return row;
    }

    private static Map<String, Object> buildVirtualServiceListRow(
            VirtualServiceKind kind,
            String name,
            String serviceId,
            String type,
            DbServiceSummaryPoint metrics,
            DbServiceSummaryPoint consumerMetrics,
            double durationSec) {
        long callCnt = metrics.requestCount();
        long errCnt = metrics.errorCount();
        long slowCnt = metrics.slowCount();
        double errRate = callCnt > 0 ? (double) errCnt / callCnt : 0;
        double slowPercent = callCnt > 0 ? (double) slowCnt / callCnt : 0;
        double avgLatencyNs = callCnt > 0 ? metrics.sumDurationNs() / callCnt : 0;
        double reqRate = callCnt / durationSec;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("serviceId", serviceId);
        row.put("name", name);
        row.put("service", name);
        row.put("callCnt", callCnt);
        row.put("errCnt", errCnt);
        row.put("slowCnt", slowCnt);
        row.put("errRate", errRate);
        row.put("slowPercent", slowPercent);
        row.put("avgLatency", avgLatencyNs);
        row.put("reqRate", reqRate);
        row.put("lastMinReqRate", reqRate * 60);
        row.put("service_type", kind.rowServiceType);
        row.put("type", type);
        row.put("datasource", "OTLP");
        row.put("virtual_service", true);
        putVirtualServiceAlarmFields(kind, row);
        if (kind == VirtualServiceKind.MQ) {
            putMqSideMetrics(row, metrics, consumerMetrics);
        } else if (kind == VirtualServiceKind.REMOTE) {
            putRemoteLatencyFields(row, metrics.maxDurationNs());
        }
        return row;
    }

    private static void putVirtualServiceAlarmFields(VirtualServiceKind kind, Map<String, Object> row) {
        if (kind == VirtualServiceKind.REMOTE) {
            return;
        }
        row.put("alarmCount", 0);
        row.put("alarmPendingCount", 0);
        row.put("alarmMetric", Map.of("total", 0));
    }

    private static void putMqSideMetrics(
            Map<String, Object> row,
            DbServiceSummaryPoint producer,
            DbServiceSummaryPoint consumer) {
        long reqInCallCnt = producer != null ? producer.requestCount() : 0;
        long reqInErrCnt = producer != null ? producer.errorCount() : 0;
        double reqInAvgLatency = reqInCallCnt > 0 && producer != null
                ? producer.sumDurationNs() / reqInCallCnt
                : 0;
        long reqOutCallCnt = consumer != null ? consumer.requestCount() : 0;
        long reqOutErrCnt = consumer != null ? consumer.errorCount() : 0;
        double reqOutAvgLatency = reqOutCallCnt > 0 && consumer != null
                ? consumer.sumDurationNs() / reqOutCallCnt
                : 0;
        row.put("reqInCallCnt", reqInCallCnt);
        row.put("reqInErrCnt", reqInErrCnt);
        row.put("reqInErrRate", reqInCallCnt > 0 ? (double) reqInErrCnt / reqInCallCnt : 0);
        row.put("reqInAvgLatency", reqInAvgLatency);
        row.put("reqOutCallCnt", reqOutCallCnt);
        row.put("reqOutErrCnt", reqOutErrCnt);
        row.put("reqOutErrRate", reqOutCallCnt > 0 ? (double) reqOutErrCnt / reqOutCallCnt : 0);
        row.put("reqOutAvgLatency", reqOutAvgLatency);
    }

    private static void putRemoteLatencyFields(Map<String, Object> row, double maxDurationNs) {
        row.put("p100Latency", Math.round(maxDurationNs));
    }

    private static String virtualServiceRowKey(Map<String, Object> row) {
        return virtualServiceRowKey(
                String.valueOf(row.getOrDefault("service", "")),
                String.valueOf(row.getOrDefault("serviceId", "")));
    }

    private static String virtualServiceRowKey(String service, String serviceId) {
        if (serviceId != null && !serviceId.isBlank()) {
            return serviceId;
        }
        return service;
    }

    private static boolean matchesVirtualServiceKind(VirtualServiceKind kind, Map<String, Object> row) {
        if (!isVirtualServiceRow(row)) {
            return false;
        }
        String serviceType = String.valueOf(row.getOrDefault("service_type", "")).toLowerCase(Locale.ROOT);
        String name = String.valueOf(row.getOrDefault("name", row.getOrDefault("service", "")));
        String inferred = inferServiceCategory(name);
        return switch (kind) {
            case DB -> "db".equals(serviceType) || "db".equals(inferred);
            case MQ -> "mq".equals(serviceType) || "mq".equals(inferred);
            case CACHE -> "cache".equals(serviceType) || "cache".equals(inferred);
            case REMOTE -> !"db".equals(inferred) && !"mq".equals(inferred) && !"cache".equals(inferred);
        };
    }

    private static boolean isVirtualServiceRow(Map<String, Object> row) {
        Object flag = row.get("virtual_service");
        if (flag instanceof Boolean bool) {
            return bool;
        }
        if (flag instanceof Number number) {
            return number.intValue() != 0;
        }
        if (flag == null) {
            return false;
        }
        String text = String.valueOf(flag).trim();
        return "1".equals(text) || "true".equalsIgnoreCase(text);
    }

    private static boolean matchesVirtualServiceListFilters(
            Map<String, Object> row,
            String serviceName,
            List<String> serviceIds,
            Integer statusType) {
        String name = String.valueOf(row.getOrDefault("name", ""));
        String serviceId = String.valueOf(row.getOrDefault("serviceId", ""));
        if (serviceName != null && !serviceName.isBlank()
                && !name.toLowerCase(Locale.ROOT).contains(serviceName.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (!serviceIds.isEmpty()
                && serviceIds.stream().noneMatch(id -> PortalServiceIdResolver.matches(id, serviceId))) {
            return false;
        }
        if (statusType != null) {
            double errRate = ((Number) row.getOrDefault("errRate", 0)).doubleValue();
            boolean healthy = errRate < 0.05;
            if ((statusType == 1) != healthy) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> filterMiddlewareRows(
            Map<String, Object> body, String kind, long from, long to) {
        Map<String, Object> listBody = new LinkedHashMap<>(body);
        listBody.put("offset", 0);
        listBody.put("size", 500);
        Map<String, Object> listResp = list(listBody);
        Object data = listResp.get("data");
        if (!(data instanceof List<?> rows)) {
            return List.of();
        }

        Pattern pattern = middlewarePattern(kind);
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> rowMap)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>((Map<String, Object>) rowMap);
            String name = String.valueOf(row.getOrDefault("name", ""));
            if (!matchesMiddlewareKind(kind, pattern, name, row, from, to)) {
                continue;
            }
            row.put("service_type", kind);
            row.put("type", middlewareTypeIcon(kind));
            filtered.add(row);
        }
        return filtered;
    }

    private boolean matchesMiddlewareKind(
            String kind,
            Pattern pattern,
            String name,
            Map<String, Object> row,
            long from,
            long to) {
        if ("remote".equals(kind)) {
            List<TopologyEdge> edges = loadTopologyEdges(from, to, 500);
            Set<String> srcServices = edges.stream().map(TopologyEdge::srcService).collect(Collectors.toSet());
            String serviceId = String.valueOf(row.getOrDefault("serviceId", ""));
            boolean isLeaf = edges.stream()
                    .anyMatch(edge -> serviceId.equals(edge.dstService()) && !srcServices.contains(serviceId));
            return pattern.matcher(name).find() || isLeaf;
        }
        return pattern.matcher(name).find();
    }

    private static Pattern middlewarePattern(String kind) {
        return switch (kind) {
            case "mq" -> MQ_PATTERN;
            case "cache" -> CACHE_PATTERN;
            case "remote" -> REMOTE_PATTERN;
            default -> DB_PATTERN;
        };
    }

    private static String middlewareTypeIcon(String kind) {
        return switch (kind) {
            case "mq" -> "kafka";
            case "cache" -> "redis";
            case "remote" -> "remote";
            default -> "mysql";
        };
    }

    private static Integer parseStatusType(Object value) {
        if (value == null || "".equals(String.valueOf(value).trim())) {
            return null;
        }
        return intValue(value, 0);
    }

    private List<TopologyEdge> loadTopologyEdges(long from, long to, int limit) {
        return globalTopologyQueryService.listTopologyEdges(from, to, limit);
    }

    private static final List<ComponentPeerSpec> COMPONENT_PEER_SPECS = List.of(
            ComponentPeerSpec.webPeer("service.http", DorisTableNames.METRIC_SERVICE_HTTP),
            ComponentPeerSpec.webPeer("service.rpc", DorisTableNames.METRIC_SERVICE_RPC),
            ComponentPeerSpec.outbound("service.db", DorisTableNames.METRIC_SERVICE_DB, null, 1),
            ComponentPeerSpec.outbound("service.redis", DorisTableNames.METRIC_SERVICE_REDIS, null, 1),
            ComponentPeerSpec.outbound("service.mq", DorisTableNames.METRIC_SERVICE_MQ, null, 1),
            ComponentPeerSpec.outboundVirtual("service.remote", DorisTableNames.METRIC_SERVICE_REMOTE, 1, 1),
            ComponentPeerSpec.outbound("service.config", DorisTableNames.METRIC_SERVICE_CONFIG, null, 1));

    private record ComponentPeerSpec(
            String componentType,
            String tableName,
            boolean webPeer,
            boolean virtualOnly,
            Integer outboundIsIn,
            Integer outboundIsOut) {

        static ComponentPeerSpec webPeer(String componentType, String tableName) {
            return new ComponentPeerSpec(componentType, tableName, true, false, null, null);
        }

        static ComponentPeerSpec outbound(String componentType, String tableName, Integer isIn, Integer isOut) {
            return new ComponentPeerSpec(componentType, tableName, false, false, isIn, isOut);
        }

        static ComponentPeerSpec outboundVirtual(String componentType, String tableName, Integer isIn, Integer isOut) {
            return new ComponentPeerSpec(componentType, tableName, false, true, isIn, isOut);
        }
    }

    private static ComponentPeerSpec findComponentSpec(String componentType) {
        if (componentType == null || componentType.isBlank()) {
            return null;
        }
        for (ComponentPeerSpec spec : COMPONENT_PEER_SPECS) {
            if (spec.componentType().equals(componentType)) {
                return spec;
            }
        }
        return null;
    }

    private void appendComponentPeerRelations(
            String serviceId,
            long from,
            long to,
            List<Map<String, Object>> upflowServiceStats,
            List<Map<String, Object>> downflowServiceStats,
            Map<String, String> peerDisplayNames) {
        Set<String> serviceKeys = metricServiceIdKeys(serviceId);
        for (ComponentPeerSpec spec : COMPONENT_PEER_SPECS) {
            appendCombinedPeerStats(
                    downflowServiceStats,
                    peerDisplayNames,
                    spec.componentType(),
                    queryComponentPeers(spec, serviceKeys, serviceKeys, true, true, from, to, 200),
                    queryComponentPeers(spec, serviceKeys, serviceKeys, true, false, from, to, 200),
                    serviceKeys);
            appendCombinedPeerStats(
                    upflowServiceStats,
                    peerDisplayNames,
                    spec.componentType(),
                    queryComponentPeers(spec, serviceKeys, serviceKeys, false, true, from, to, 200),
                    queryComponentPeers(spec, serviceKeys, serviceKeys, false, false, from, to, 200),
                    serviceKeys);
        }
    }

    private void appendTopologyPeerRelations(
            String serviceId,
            long from,
            long to,
            List<Map<String, Object>> upflowServiceStats,
            List<Map<String, Object>> downflowServiceStats,
            Map<String, String> peerDisplayNames) {
        for (TopologyEdge edge : loadTopologyEdges(from, to, 500)) {
            if (PortalServiceIdResolver.matches(serviceId, edge.dstService())) {
                upflowServiceStats.add(buildComponentServiceStat(
                        null, edge.srcService(),
                        edge.callCount(), edge.errorCount(), 0,
                        "service.http"));
                rememberPeer(peerDisplayNames, null, edge.srcService());
            }
            if (PortalServiceIdResolver.matches(serviceId, edge.srcService())) {
                downflowServiceStats.add(buildComponentServiceStat(
                        null, edge.dstService(),
                        edge.callCount(), edge.errorCount(), 0,
                        "service.http"));
                rememberPeer(peerDisplayNames, null, edge.dstService());
            }
        }
    }

    private void appendCombinedPeerStats(
            List<Map<String, Object>> target,
            Map<String, String> peerDisplayNames,
            String componentType,
            List<DbDownstreamPoint> inPeers,
            List<DbDownstreamPoint> outPeers) {
        appendCombinedPeerStats(target, peerDisplayNames, componentType, inPeers, outPeers, null);
    }

    private void appendCombinedPeerStats(
            List<Map<String, Object>> target,
            Map<String, String> peerDisplayNames,
            String componentType,
            List<DbDownstreamPoint> inPeers,
            List<DbDownstreamPoint> outPeers,
            Set<String> selfServiceKeys) {
        outPeers = remapSelfReferencedOutboundPeers(inPeers, outPeers, selfServiceKeys);
        Map<String, DbDownstreamPoint> outByKey = new LinkedHashMap<>();
        for (DbDownstreamPoint peer : outPeers) {
            if (peer.service() == null || peer.service().isBlank()) {
                continue;
            }
            outByKey.put(peerRelationKey(peer), peer);
        }
        for (DbDownstreamPoint inPeer : inPeers) {
            if (inPeer.service() == null || inPeer.service().isBlank()) {
                continue;
            }
            String key = peerRelationKey(inPeer);
            DbDownstreamPoint outPeer = outByKey.remove(key);
            target.add(buildPeerServiceStat(inPeer, outPeer, componentType));
            rememberPeer(peerDisplayNames, inPeer.serviceId(), inPeer.service());
        }
        for (DbDownstreamPoint outPeer : outByKey.values()) {
            target.add(buildPeerServiceStat(null, outPeer, componentType));
            rememberPeer(peerDisplayNames, outPeer.serviceId(), outPeer.service());
        }
    }

    private List<DbDownstreamPoint> queryComponentPeers(
            ComponentPeerSpec spec,
            Set<String> srcKeys,
            Set<String> serviceKeys,
            boolean downstream,
            boolean inbound,
            long from,
            long to,
            int limit) {
        try {
            String sql;
            if (spec.webPeer()) {
                sql = downstream
                        ? inbound
                                ? MetricQueryBuilder.componentWebDownstreamSummarySql(
                                        metricDatabase, spec.tableName(), srcKeys, from, to, limit)
                                : MetricQueryBuilder.componentWebDownstreamOutboundSummarySql(
                                        metricDatabase, spec.tableName(), srcKeys, from, to, limit)
                        : inbound
                                ? MetricQueryBuilder.componentWebUpstreamSummarySql(
                                        metricDatabase, spec.tableName(), serviceKeys, from, to, limit)
                                : MetricQueryBuilder.componentWebUpstreamOutboundSummarySql(
                                        metricDatabase, spec.tableName(), serviceKeys, from, to, limit);
            } else if (downstream) {
                sql = MetricQueryBuilder.componentOutboundDownstreamSummarySql(
                        metricDatabase,
                        spec.tableName(),
                        srcKeys,
                        inbound ? 1 : null,
                        inbound ? null : 1,
                        spec.virtualOnly(),
                        from,
                        to,
                        limit,
                        "");
            } else {
                sql = MetricQueryBuilder.componentOutboundUpstreamSummarySql(
                        metricDatabase,
                        spec.tableName(),
                        serviceKeys,
                        inbound ? 1 : null,
                        inbound ? null : 1,
                        from,
                        to,
                        limit,
                        "");
            }
            return readRepository.queryDbDownstream(sql).stream()
                    .filter(peer -> matchesComponentPeerSpec(spec, peer, downstream))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean matchesComponentPeerSpec(
            ComponentPeerSpec spec, DbDownstreamPoint peer, boolean downstream) {
        if (spec.webPeer()) {
            return !isVirtualServiceName(peer.service());
        }
        if (spec.virtualOnly() && downstream) {
            return isVirtualServiceName(peer.service());
        }
        return true;
    }

    private static Map<String, Object> emptyServiceInstanceRelations() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reqCnt", 0);
        data.put("upflowServiceStats", List.of());
        data.put("downflowServiceStats", List.of());
        data.put("serviceId2Name", List.of());
        return data;
    }

    private static String peerRelationKey(DbDownstreamPoint peer) {
        return PortalServiceIdResolver.resolve(peer.serviceId(), peer.service());
    }

    /**
     * Legacy HTTP/RPC outbound rows group by caller {@code service}; remap that self row onto the
     * single inbound peer so service relations show one callee instead of caller+callee duplicates.
     */
    private static List<DbDownstreamPoint> remapSelfReferencedOutboundPeers(
            List<DbDownstreamPoint> inPeers,
            List<DbDownstreamPoint> outPeers,
            Set<String> selfServiceKeys) {
        if (selfServiceKeys == null || selfServiceKeys.isEmpty() || outPeers.isEmpty()) {
            return outPeers;
        }
        List<DbDownstreamPoint> selfOutbound = new ArrayList<>();
        List<DbDownstreamPoint> peerOutbound = new ArrayList<>();
        for (DbDownstreamPoint peer : outPeers) {
            if (matchesAnyServiceKey(selfServiceKeys, peer)) {
                selfOutbound.add(peer);
            } else {
                peerOutbound.add(peer);
            }
        }
        if (selfOutbound.size() == 1 && inPeers.size() == 1 && peerOutbound.isEmpty()) {
            DbDownstreamPoint inPeer = inPeers.get(0);
            DbDownstreamPoint selfOut = selfOutbound.get(0);
            return List.of(new DbDownstreamPoint(
                    inPeer.serviceId(),
                    inPeer.service(),
                    selfOut.requestCount(),
                    selfOut.errorCount(),
                    selfOut.avgDuration()));
        }
        return peerOutbound;
    }

    private static boolean matchesAnyServiceKey(Set<String> keys, DbDownstreamPoint peer) {
        if (keys == null || keys.isEmpty() || peer == null) {
            return false;
        }
        for (String key : keys) {
            if (PortalServiceIdResolver.matches(key, peer.service())
                    || PortalServiceIdResolver.matches(key, peer.serviceId())) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> buildPeerServiceStat(
            DbDownstreamPoint inPeer,
            DbDownstreamPoint outPeer,
            String componentType) {
        DbDownstreamPoint primary = inPeer != null ? inPeer : outPeer;
        long reqInCnt = inPeer != null ? inPeer.requestCount() : 0;
        long reqInErrCnt = inPeer != null ? inPeer.errorCount() : 0;
        double reqInTime = inPeer != null ? avgDurationMsToNs(inPeer.avgDuration()) * reqInCnt : 0;
        long reqOutCnt = outPeer != null ? outPeer.requestCount() : 0;
        long reqOutErrCnt = outPeer != null ? outPeer.errorCount() : 0;
        double reqOutTime = outPeer != null ? avgDurationMsToNs(outPeer.avgDuration()) * reqOutCnt : 0;

        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("serviceId", PortalServiceIdResolver.resolve(primary.serviceId(), primary.service()));
        stat.put("reqInCnt", reqInCnt);
        stat.put("reqInErrCnt", reqInErrCnt);
        stat.put("reqInTime", reqInTime);
        stat.put("reqOutCnt", reqOutCnt);
        stat.put("reqOutErrCnt", reqOutErrCnt);
        stat.put("reqOutTime", reqOutTime);
        stat.put("componentType", componentType);
        return stat;
    }

    private static Map<String, Object> buildComponentServiceStat(
            String metricServiceId,
            String serviceName,
            long callCnt,
            long errCnt,
            double avgDurationMs,
            String componentType) {
        long totalTime = avgDurationMsToNs(avgDurationMs) * callCnt;
        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("serviceId", PortalServiceIdResolver.resolve(metricServiceId, serviceName));
        stat.put("reqOutCnt", callCnt);
        stat.put("reqOutErrCnt", errCnt);
        stat.put("reqOutTime", totalTime);
        stat.put("reqInCnt", callCnt);
        stat.put("reqInErrCnt", errCnt);
        stat.put("reqInTime", totalTime);
        stat.put("componentType", componentType);
        return stat;
    }

    private static void rememberPeer(Map<String, String> peerDisplayNames, String metricServiceId, String serviceName) {
        String resolvedId = PortalServiceIdResolver.resolve(metricServiceId, serviceName);
        if (resolvedId.isBlank()) {
            return;
        }
        peerDisplayNames.putIfAbsent(resolvedId, firstNonBlank(serviceName, resolvedId));
    }

    private Map<String, Object> toPeerId2NameRow(String resolvedServiceId, String metricServiceName) {
        MetaServicePoint meta = loadMetaService(resolvedServiceId);
        if (meta == null && !isBlank(metricServiceName)) {
            meta = loadMetaService(metricServiceName);
        }
        String displayName = resolveServiceDisplayName(resolvedServiceId, meta);
        if (meta == null && !isBlank(metricServiceName)) {
            displayName = metricServiceName;
        }
        String serviceType = meta != null && !isBlank(meta.serviceType())
                ? meta.serviceType()
                : inferServiceCategory(firstNonBlank(metricServiceName, resolvedServiceId));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("serviceId", resolvedServiceId);
        row.put("serviceName", displayName);
        row.put("serviceType", serviceType);
        row.put("alarmCount", 0);
        return row;
    }

    private double loadServiceRequestRate(String serviceId, long from, long to) {
        try {
            List<ServiceSummaryPoint> summaries = readRepository.queryServiceSummaries(
                    MetricQueryBuilder.serviceSummaryByServiceSql(metricDatabase, serviceId, from, to));
            if (summaries.isEmpty()) {
                return 0;
            }
            long callCnt = summaries.get(0).requestCount();
            double durationSec = Math.max(1.0, (to - from) / 1000.0);
            return callCnt / durationSec;
        } catch (Exception e) {
            return 0;
        }
    }

    private static final List<ResourceRelationTableSpec> RESOURCE_RELATION_TABLES = List.of(
            new ResourceRelationTableSpec("service.http", DorisTableNames.METRIC_SERVICE_HTTP, "url"),
            new ResourceRelationTableSpec("service.rpc", DorisTableNames.METRIC_SERVICE_RPC, "resource"),
            new ResourceRelationTableSpec("service.mq", DorisTableNames.METRIC_SERVICE_MQ, "resource"),
            new ResourceRelationTableSpec("service.db", DorisTableNames.METRIC_SERVICE_DB, "sqlContent"),
            new ResourceRelationTableSpec("service.redis", DorisTableNames.METRIC_SERVICE_REDIS, "resource"),
            new ResourceRelationTableSpec("service.remote", DorisTableNames.METRIC_SERVICE_REMOTE, "resource"),
            new ResourceRelationTableSpec("service.config", DorisTableNames.METRIC_SERVICE_CONFIG, "resource"));

    private Map<String, List<Map<String, Object>>> loadCurrentResourceRelations(
            String serviceId,
            String resource,
            String componentType,
            long from,
            long to,
            double durationSec,
            Map<String, Object> detail) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        List<ResourceRelationTableSpec> specs = resolveResourceRelationTables(componentType);
        for (ResourceRelationTableSpec spec : specs) {
            List<ComponentResourceRelationPoint> points = queryComponentResourceRelation(
                    spec.table(),
                    from,
                    to,
                    metricServiceIdKeys(serviceId),
                    null,
                    resource,
                    null,
                    1,
                    null,
                    List.of("service_id", "service", spec.resourceColumn()),
                    50);
            if (points.isEmpty() && detail.containsKey("resource") && spec.componentType().equals(componentType)) {
                grouped.put(spec.componentType(), List.of(toSlowInterfaceRow(
                        resource != null ? resource : stringValue(detail.get("resource"), ""),
                        serviceId,
                        stringValue(detail.get("service"), serviceId),
                        spec.componentType(),
                        toLong(detail.get("callCnt")),
                        0L,
                        toLong(detail.get("errCnt")),
                        toLong(detail.get("avgLatency")),
                        toLong(detail.get("avgLatency")),
                        durationSec)));
                continue;
            }
            List<Map<String, Object>> rows = points.stream()
                    .map(point -> toSlowInterfaceRow(
                            point.resource() != null ? point.resource() : resource,
                            point.serviceId() != null ? point.serviceId() : serviceId,
                            point.service() != null ? point.service() : serviceId,
                            spec.componentType(),
                            point.allCnt(),
                            point.slowCnt(),
                            point.errCnt(),
                            (long) point.avgTimeNs(),
                            (long) point.maxTimeNs(),
                            durationSec))
                    .toList();
            if (!rows.isEmpty()) {
                grouped.put(spec.componentType(), rows);
            }
        }
        return grouped;
    }

    private Map<String, List<Map<String, Object>>> loadUpstreamResourceRelations(
            String serviceId,
            String resource,
            String componentType,
            long from,
            long to,
            double durationSec) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        Set<String> serviceKeys = metricServiceIdKeys(serviceId);
        List<ResourceRelationTableSpec> specs = resolveResourceRelationTables(componentType);

        for (ResourceRelationTableSpec spec : specs) {
            List<ComponentResourceRelationPoint> upOut = queryComponentResourceRelation(
                    spec.table(),
                    from,
                    to,
                    serviceKeys,
                    null,
                    resource,
                    null,
                    null,
                    1,
                    List.of("srcServiceId", "srcService", "service_id", "rootResource", "rootComponentType"),
                    100);
            for (ComponentResourceRelationPoint point : upOut) {
                if (point.rootComponentType() == null || point.rootComponentType().isBlank()) {
                    continue;
                }
                String type = point.rootComponentType();
                String rowResource = point.rootResource() != null ? point.rootResource() : point.resource();
                grouped.computeIfAbsent(type, ignored -> new ArrayList<>())
                        .add(toSlowInterfaceRow(
                                rowResource,
                                point.srcServiceId(),
                                point.srcService(),
                                type,
                                point.allCnt(),
                                point.slowCnt(),
                                point.errCnt(),
                                (long) point.avgTimeNs(),
                                (long) point.maxTimeNs(),
                                durationSec));
            }
        }

        if ("service.http".equals(componentType)) {
            List<ComponentResourceRelationPoint> inboundCallers = queryComponentResourceRelation(
                    DorisTableNames.METRIC_SERVICE_HTTP,
                    from,
                    to,
                    serviceKeys,
                    null,
                    resource,
                    null,
                    1,
                    null,
                    List.of("srcServiceId", "srcService", "url"),
                    100);
            for (ComponentResourceRelationPoint point : inboundCallers) {
                if (point.srcServiceId() == null || point.srcServiceId().isBlank()) {
                    continue;
                }
                if (PortalServiceIdResolver.matches(serviceId, point.srcServiceId())) {
                    continue;
                }
                grouped.computeIfAbsent("service.http", ignored -> new ArrayList<>())
                        .add(toSlowInterfaceRow(
                                point.resource() != null ? point.resource() : resource,
                                point.srcServiceId(),
                                point.srcService(),
                                "service.http",
                                point.allCnt(),
                                point.slowCnt(),
                                point.errCnt(),
                                (long) point.avgTimeNs(),
                                (long) point.maxTimeNs(),
                                durationSec));
            }
        }
        return grouped;
    }

    private Map<String, List<Map<String, Object>>> loadDownstreamResourceRelations(
            String serviceId,
            String resource,
            long from,
            long to,
            double durationSec) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        Set<String> serviceKeys = metricServiceIdKeys(serviceId);
        for (ResourceRelationTableSpec spec : RESOURCE_RELATION_TABLES) {
            List<ComponentResourceRelationPoint> points = queryDownstreamResourcePoints(
                    spec, serviceKeys, serviceKeys, resource, from, to);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ComponentResourceRelationPoint point : points) {
                rows.add(toSlowInterfaceRow(
                        point.resource(),
                        point.serviceId(),
                        point.service(),
                        spec.componentType(),
                        point.allCnt(),
                        point.slowCnt(),
                        point.errCnt(),
                        (long) point.avgTimeNs(),
                        (long) point.maxTimeNs(),
                        durationSec));
            }
            if (!rows.isEmpty()) {
                grouped.put(spec.componentType(), rows);
            }
        }
        return grouped;
    }

    private List<ComponentResourceRelationPoint> queryDownstreamResourcePoints(
            ResourceRelationTableSpec spec,
            Set<String> srcKeys,
            Set<String> serviceKeys,
            String resource,
            long from,
            long to) {
        List<String> groupBy = List.of("service_id", "service", spec.resourceColumn());
        Set<String> effectiveSrcKeys = new LinkedHashSet<>(srcKeys);
        if (DorisTableNames.METRIC_SERVICE_DB.equals(spec.table()) && effectiveSrcKeys.isEmpty()) {
            effectiveSrcKeys.addAll(serviceKeys);
        }

        List<ComponentResourceRelationPoint> points = queryDownstreamResourcePoints(
                spec.table(), effectiveSrcKeys, resource, from, to, null, 1, groupBy);
        if (points.isEmpty() && isWebResourceRelationPeer(spec.componentType())) {
            points = mergeResourceRelationPoints(
                    points,
                    queryDownstreamResourcePoints(
                            spec.table(), effectiveSrcKeys, resource, from, to, 1, null, groupBy));
        }
        if (points.isEmpty() && !isWebResourceRelationPeer(spec.componentType())) {
            points = queryDownstreamResourcePoints(
                    spec.table(), effectiveSrcKeys, resource, from, to, 1, 1, groupBy);
            if (points.isEmpty()) {
                points = queryDownstreamResourcePoints(
                        spec.table(), effectiveSrcKeys, resource, from, to, 1, null, groupBy);
            }
        }
        return points;
    }

    private List<ComponentResourceRelationPoint> queryDownstreamResourcePoints(
            String table,
            Set<String> srcKeys,
            String resource,
            long from,
            long to,
            Integer isIn,
            Integer isOut,
            List<String> groupBy) {
        return queryComponentResourceRelation(
                table,
                from,
                to,
                null,
                srcKeys,
                null,
                resource,
                isIn,
                isOut,
                groupBy,
                100);
    }

    private static List<ComponentResourceRelationPoint> mergeResourceRelationPoints(
            List<ComponentResourceRelationPoint> left,
            List<ComponentResourceRelationPoint> right) {
        Map<String, ComponentResourceRelationPoint> merged = new LinkedHashMap<>();
        for (ComponentResourceRelationPoint point : left) {
            merged.merge(resourceRelationMergeKey(point), point, ServicePortalService::mergeResourceRelationPoint);
        }
        for (ComponentResourceRelationPoint point : right) {
            merged.merge(resourceRelationMergeKey(point), point, ServicePortalService::mergeResourceRelationPoint);
        }
        return new ArrayList<>(merged.values());
    }

    private static String resourceRelationMergeKey(ComponentResourceRelationPoint point) {
        return nullToEmpty(point.serviceId()) + "|" + nullToEmpty(point.resource());
    }

    private static ComponentResourceRelationPoint mergeResourceRelationPoint(
            ComponentResourceRelationPoint left,
            ComponentResourceRelationPoint right) {
        long allCnt = left.allCnt() + right.allCnt();
        long slowCnt = left.slowCnt() + right.slowCnt();
        long errCnt = left.errCnt() + right.errCnt();
        double avgTimeNs = allCnt > 0
                ? (left.avgTimeNs() * left.allCnt() + right.avgTimeNs() * right.allCnt()) / allCnt
                : 0;
        return new ComponentResourceRelationPoint(
                firstNonBlank(left.serviceId(), right.serviceId()),
                firstNonBlank(left.service(), right.service()),
                firstNonBlank(left.resource(), right.resource()),
                firstNonBlank(left.srcServiceId(), right.srcServiceId()),
                firstNonBlank(left.srcService(), right.srcService()),
                firstNonBlank(left.rootResource(), right.rootResource()),
                firstNonBlank(left.rootComponentType(), right.rootComponentType()),
                allCnt,
                slowCnt,
                errCnt,
                avgTimeNs,
                Math.max(left.maxTimeNs(), right.maxTimeNs()));
    }

    private List<ComponentResourceRelationPoint> queryComponentResourceRelation(
            String table,
            long from,
            long to,
            Set<String> serviceKeys,
            Set<String> srcServiceKeys,
            String resourcePath,
            String rootResourcePath,
            Integer isIn,
            Integer isOut,
            List<String> groupByColumns,
            int limit) {
        try {
            String sql = MetricQueryBuilder.componentResourceRelationSql(
                    metricDatabase,
                    table,
                    from,
                    to,
                    serviceKeys,
                    srcServiceKeys,
                    resourcePath,
                    rootResourcePath,
                    isIn,
                    isOut,
                    groupByColumns,
                    limit);
            return readRepository.queryComponentResourceRelations(sql, groupByColumns);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<ResourceRelationTableSpec> resolveResourceRelationTables(String componentType) {
        if (componentType != null && !componentType.isBlank()) {
            return RESOURCE_RELATION_TABLES.stream()
                    .filter(spec -> spec.componentType().equals(componentType))
                    .toList();
        }
        return RESOURCE_RELATION_TABLES;
    }

    private static Map<String, Object> toSlowInterfaceRow(
            String resource,
            String serviceId,
            String service,
            String componentType,
            long allCnt,
            long slowCnt,
            long errCnt,
            long avgTimeNs,
            long maxTimeNs,
            double durationSec) {
        double errRate = allCnt > 0 ? (double) errCnt / allCnt : 0;
        double slowRate = allCnt > 0 ? (double) slowCnt / allCnt : 0;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("resource", resource);
        row.put("serviceId", PortalServiceIdResolver.normalize(serviceId));
        row.put("service", service);
        row.put("serviceType", "web");
        row.put("componentType", componentType);
        row.put("allCnt", allCnt);
        row.put("slowCnt", slowCnt);
        row.put("errCnt", errCnt);
        row.put("normalCnt", Math.max(0, allCnt - errCnt - slowCnt));
        row.put("errRate", errRate);
        row.put("slowRate", slowRate);
        row.put("reqRate", allCnt / durationSec);
        row.put("avgTime", (double) avgTimeNs);
        row.put("avgLatency", avgTimeNs);
        row.put("maxTime", maxTimeNs);
        row.put("alias", "");
        row.put("typeIcon", "web");
        return row;
    }

    private static Map<String, Object> withDecodedResource(Map<String, Object> body, String resource) {
        if (resource == null) {
            return body;
        }
        Map<String, Object> copy = new LinkedHashMap<>(body);
        copy.put("resource", resource);
        return copy;
    }

    private static Map<String, Object> withUrl(Map<String, Object> body, String url) {
        Map<String, Object> copy = new LinkedHashMap<>(body);
        copy.put("url", url);
        return copy;
    }

    private static String decodeUrl(Map<String, Object> body) {
        return decodeResourceValue(stringValue(body.get("url"), null));
    }

    private static String decodeResource(Map<String, Object> body) {
        String resource = stringValue(body.get("resource"), null);
        return decodeResourceValue(resource);
    }

    static String decodeResourceValue(String resource) {
        if (resource == null) {
            return null;
        }
        String decoded = resource;
        for (int i = 0; i < 3; i++) {
            try {
                String next = java.net.URLDecoder.decode(decoded, java.nio.charset.StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            } catch (IllegalArgumentException e) {
                break;
            }
        }
        return decoded;
    }

    private record ResourceRelationTableSpec(String componentType, String table, String resourceColumn) {
    }

    private static boolean isWebResourceRelationPeer(String componentType) {
        return "service.http".equals(componentType) || "service.rpc".equals(componentType);
    }

    private List<Map<String, Object>> buildPeerRelationRows(
            List<TopologyEdge> edges,
            List<String> peerIds,
            String resource,
            long from,
            long to) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String peerId : peerIds) {
            List<HttpEndpointPoint> endpoints = loadHttpEndpoints(peerId, from, to, resource, 20);
            if (!endpoints.isEmpty()) {
                for (HttpEndpointPoint point : endpoints) {
                    rows.add(buildRelationRow(
                            point.url(),
                            peerId,
                            peerId,
                            "web",
                            point.requestCount(),
                            point.errorCount(),
                            avgDurationMsToNs(point.avgDuration()),
                            "service.http"));
                }
            } else {
                TopologyEdge edge = edges.stream()
                        .filter(item -> peerId.equals(item.srcService()) || peerId.equals(item.dstService()))
                        .findFirst()
                        .orElse(null);
                rows.add(buildRelationRow(
                        resource != null ? resource : peerId,
                        peerId,
                        peerId,
                        "web",
                        edge != null ? edge.callCount() : 0,
                        edge != null ? edge.errorCount() : 0,
                        0,
                        "service.http"));
            }
        }
        return rows;
    }

    private static Map<String, Object> buildRelationRow(
            String resource,
            String service,
            String serviceId,
            String serviceType,
            long allCnt,
            long errCnt,
            long avgLatency,
            String componentType) {
        long slowCnt = 0;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("resource", resource);
        row.put("service", service);
        row.put("serviceId", PortalServiceIdResolver.normalize(serviceId));
        row.put("serviceType", serviceType);
        row.put("allCnt", allCnt);
        row.put("errCnt", errCnt);
        row.put("slowCnt", slowCnt);
        row.put("normalCnt", Math.max(0, allCnt - errCnt - slowCnt));
        row.put("avgLatency", avgLatency);
        row.put("errRate", allCnt > 0 ? (double) errCnt / allCnt : 0);
        row.put("typeIcon", serviceType);
        row.put("componentType", componentType);
        return row;
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

    @SuppressWarnings("unchecked")
    private static void sortEndpointRows(List<Map<String, Object>> rows, String sortField, String sortOrder) {
        Comparator<Map<String, Object>> comparator = Comparator.comparing(row -> {
            Object value = row.get(sortField);
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            if (value instanceof String text) {
                try {
                    return Double.parseDouble(text);
                } catch (NumberFormatException ignored) {
                    return 0.0;
                }
            }
            return 0.0;
        });
        if (!"asc".equalsIgnoreCase(sortOrder)) {
            comparator = comparator.reversed();
        }
        rows.sort(comparator);
    }

    private static double trendMetricValue(String metric, ServiceTrendBucketPoint bucket, int intervalSec) {
        long req = bucket.requestCount();
        long err = bucket.errorCount();
        double sumDurationNs = bucket.sumDurationNs();
        return switch (metric) {
            case "errRate" -> req > 0 ? 100.0 * err / req : 0;
            case "avgTime" -> req > 0 ? sumDurationNs / req : 0;
            case "errReqCount", "typeErrCount" -> err;
            case "succReqCount" -> req - err;
            case "minuteReqCount" -> req / Math.max(1.0, intervalSec / 60.0);
            case "p99Latencys", "p95Latencys", "p90Latencys" -> req > 0 ? sumDurationNs / req : 0;
            default -> req;
        };
    }

    private void putDistinctValues(
            Map<String, List<String>> result,
            String componentType,
            String table,
            String column,
            long from,
            long to,
            String filterClause) {
        try {
            String sql = MetricQueryBuilder.distinctResourceValuesSql(
                    metricDatabase, table, column, from, to, filterClause, 1000);
            List<String> values = readRepository.queryDistinctTags(sql);
            if (!values.isEmpty()) {
                result.put(componentType, values);
            }
        } catch (Exception ignored) {
            // optional table
        }
    }

    private static List<ResourceComponentSpec> resolveResourceComponentSpecs(String componentType) {
        if (componentType != null && !componentType.isBlank()) {
            return switch (componentType) {
                case "service.http" -> List.of(new ResourceComponentSpec("service.http", "service_http", "url"));
                case "service.db" -> List.of(new ResourceComponentSpec("service.db", "service_db", "sqlContent"));
                case "service.exception" -> List.of(new ResourceComponentSpec("service.exception", "service_exception", "errorType"));
                case "service.trace" -> List.of(new ResourceComponentSpec("service.trace", "service_trace", "resource"));
                default -> List.of();
            };
        }
        return List.of(
                new ResourceComponentSpec("service.http", "service_http", "url"),
                new ResourceComponentSpec("service.trace", "service_trace", "resource"));
    }

    private static String buildResourceFilterClause(String serviceId, String serviceInstance) {
        StringBuilder clause = new StringBuilder();
        if (serviceId != null && !serviceId.isBlank()) {
            clause.append(MetricQueryBuilder.metricFilterClause("serviceId", "=", serviceId));
        }
        if (serviceInstance != null && !serviceInstance.isBlank()) {
            clause.append(MetricQueryBuilder.metricFilterClause("serviceInstance", "=", serviceInstance));
        }
        return clause.toString();
    }

    private List<Map<String, Object>> buildMetricSeriesList(
            Map<String, String> tags,
            MetricTarget target,
            String fieldColumn,
            String fieldExpr,
            String filterClause,
            long from,
            long to) {
        return buildMetricSeriesList(tags, target, fieldColumn, fieldExpr, filterClause, from, to, null, null);
    }

    private List<Map<String, Object>> buildMetricSeriesList(
            Map<String, String> tags,
            MetricTarget target,
            String fieldColumn,
            String fieldExpr,
            String filterClause,
            long from,
            long to,
            String groupColumn,
            String groupValue) {
        try {
            String sql = groupColumn == null
                    ? MetricQueryBuilder.metricFieldSeriesSql(
                            metricDatabase, target.table(), fieldColumn, from, to, filterClause)
                    : MetricQueryBuilder.metricFieldSeriesByGroupSql(
                            metricDatabase, target.table(), fieldColumn, groupColumn, groupValue,
                            from, to, filterClause);
            List<MetricSeriesPoint> points = readRepository.queryMetricSeries(sql);
            return List.of(PortalMetricSeriesBuilder.series(tags, points, fieldExpr, from, to, 60));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static MetricTarget resolveMetricTarget(String metric, Map<String, Object> filters) {
        String rootResource = stringValue(filters.get("rootResource"), null);
        if ("service.exception".equals(metric) && rootResource != null) {
            return new MetricTarget("service_trace", "error");
        }
        if ("service.exception".equals(metric)) {
            return new MetricTarget("service_exception", "cnt");
        }
        if (metric.startsWith("service.http")) {
            return new MetricTarget("service_http", "error");
        }
        return new MetricTarget("service", "error");
    }

    private static String buildMetricFilterClause(Map<String, Object> filters, MetricTarget target) {
        StringBuilder clause = new StringBuilder();
        appendMetricFilter(clause, "serviceId", filters.get("serviceId"));
        appendMetricFilter(clause, "serviceInstance", filters.get("serviceInstance"));
        appendMetricFilter(clause, "srcServiceId", filters.get("srcServiceId"));
        if (filters.get("rootResource") != null && "service_trace".equals(target.table())) {
            appendMetricFilter(clause, "resource", filters.get("rootResource"));
        } else if (filters.get("rootResource") != null) {
            appendMetricFilter(clause, "url", filters.get("rootResource"));
        }
        return clause.toString();
    }

    private static void appendMetricFilter(StringBuilder clause, String column, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return;
        }
        clause.append(MetricQueryBuilder.metricFilterClause(column, "=", text));
    }

    private static String portalGroupColumn(String groupBy, String table) {
        if ("exceptionName".equals(groupBy)) {
            return "errorType";
        }
        if ("service.http".equals(table) && "resource".equals(groupBy)) {
            return "url";
        }
        return MetricIdentifierParser.toColumnName(groupBy);
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private record ResourceComponentSpec(String componentType, String table, String resourceColumn) {
    }

    private record MetricTarget(String table, String fieldColumn) {
    }

    static int intValue(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }
}
