package com.databuff.apm.web.portal;

import com.databuff.apm.common.query.ApmQueryModels.ServiceFlowEdge;
import com.databuff.apm.common.query.ApmQueryModels.ServiceSummaryPoint;
import com.databuff.apm.common.query.ApmQueryModels.TopologyEdge;
import com.databuff.apm.common.util.PortalServiceIdResolver;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.common.storage.DorisTableNames;
import com.databuff.apm.common.storage.MetricQueryBuilder;
import com.databuff.apm.web.config.ApmStorageProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads global topology edges from HTTP/RPC and virtual-service component metric tables,
 * preferring {@code isOut=1} over {@code isIn=1}.
 */
@Service
public class GlobalTopologyQueryService {

    private static final List<String> WEB_PEER_TABLES = List.of(
            DorisTableNames.METRIC_SERVICE_HTTP,
            DorisTableNames.METRIC_SERVICE_RPC);

    private record VirtualMetricTable(String tableName, boolean virtualDestinationOnly) {
    }

    private static final List<VirtualMetricTable> VIRTUAL_SERVICE_TABLES = List.of(
            new VirtualMetricTable(DorisTableNames.METRIC_SERVICE_DB, false),
            new VirtualMetricTable(DorisTableNames.METRIC_SERVICE_REDIS, false),
            new VirtualMetricTable(DorisTableNames.METRIC_SERVICE_MQ, false),
            new VirtualMetricTable(DorisTableNames.METRIC_SERVICE_REMOTE, true),
            new VirtualMetricTable(DorisTableNames.METRIC_SERVICE_CONFIG, false));

    private final ApmReadRepository readRepository;
    private final String metricDatabase;

    public GlobalTopologyQueryService(ApmReadRepository readRepository, ApmStorageProperties storageProperties) {
        this.readRepository = readRepository;
        this.metricDatabase = storageProperties.metricDatabase();
    }

    public List<ServiceFlowEdge> listEdges(long fromMillis, long toMillis, int limit) {
        try {
            List<ServiceFlowEdge> inbound = new ArrayList<>();
            for (String table : WEB_PEER_TABLES) {
                inbound.addAll(queryWebPeerEdges(table, fromMillis, toMillis, limit, 1, null));
            }
            for (VirtualMetricTable table : VIRTUAL_SERVICE_TABLES) {
                inbound.addAll(queryVirtualEdges(table, fromMillis, toMillis, limit, 1, null));
            }

            List<ServiceFlowEdge> outbound = new ArrayList<>();
            for (String table : WEB_PEER_TABLES) {
                outbound.addAll(queryWebPeerEdges(table, fromMillis, toMillis, limit, null, 1));
            }
            for (VirtualMetricTable table : VIRTUAL_SERVICE_TABLES) {
                outbound.addAll(queryVirtualEdges(table, fromMillis, toMillis, limit, null, 1));
            }
            return mergePreferOutbound(inbound, outbound);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** Real services with metric_service traffic in the window (including isolated services). */
    public List<ServiceSummaryPoint> listServices(long fromMillis, long toMillis, int limit) {
        try {
            String sql = MetricQueryBuilder.globalTopologyServicesSql(metricDatabase, fromMillis, toMillis, limit);
            return readRepository.queryServiceSummaries(sql);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public List<TopologyEdge> listTopologyEdges(long fromMillis, long toMillis, int limit) {
        return listEdges(fromMillis, toMillis, limit).stream()
                .map(edge -> new TopologyEdge(edge.srcService(), edge.dstService(), edge.callCount(), edge.errorCount()))
                .toList();
    }

    private List<ServiceFlowEdge> queryWebPeerEdges(
            String table,
            long fromMillis,
            long toMillis,
            int limit,
            Integer isIn,
            Integer isOut) throws Exception {
        String sql = MetricQueryBuilder.globalTopologyPeerEdgesSql(
                metricDatabase, table, fromMillis, toMillis, limit, isIn, isOut);
        return readRepository.queryServiceFlow(sql);
    }

    private List<ServiceFlowEdge> queryVirtualEdges(
            VirtualMetricTable table,
            long fromMillis,
            long toMillis,
            int limit,
            Integer isIn,
            Integer isOut) throws Exception {
        String sql = MetricQueryBuilder.globalTopologyVirtualEdgesSql(
                metricDatabase,
                table.tableName(),
                fromMillis,
                toMillis,
                limit,
                isIn,
                isOut,
                table.virtualDestinationOnly());
        return readRepository.queryServiceFlow(sql);
    }

    static List<ServiceFlowEdge> mergePreferOutbound(List<ServiceFlowEdge> inbound, List<ServiceFlowEdge> outbound) {
        Map<String, ServiceFlowEdge> merged = aggregateByEdgeKey(inbound);
        Map<String, ServiceFlowEdge> outboundByKey = aggregateByEdgeKey(outbound);
        for (Map.Entry<String, ServiceFlowEdge> entry : outboundByKey.entrySet()) {
            merged.put(entry.getKey(), entry.getValue());
        }
        return List.copyOf(merged.values());
    }

    private static Map<String, ServiceFlowEdge> aggregateByEdgeKey(List<ServiceFlowEdge> edges) {
        Map<String, ServiceFlowEdge> aggregated = new LinkedHashMap<>();
        for (ServiceFlowEdge edge : edges) {
            String key = edgeKey(edge);
            if (key == null) {
                continue;
            }
            aggregated.merge(key, edge, GlobalTopologyQueryService::combineEdges);
        }
        return aggregated;
    }

    private static String edgeKey(ServiceFlowEdge edge) {
        String srcId = PortalServiceIdResolver.resolve(edge.srcServiceId(), edge.srcService());
        String dstId = PortalServiceIdResolver.resolve(edge.dstServiceId(), edge.dstService());
        if (srcId.isBlank() || dstId.isBlank() || srcId.equals(dstId)) {
            return null;
        }
        return srcId + "\0" + dstId;
    }

    private static ServiceFlowEdge combineEdges(ServiceFlowEdge left, ServiceFlowEdge right) {
        long callCnt = left.callCount() + right.callCount();
        long errCnt = left.errorCount() + right.errorCount();
        double avgDuration = weightedAvg(left.avgDuration(), left.callCount(), right.avgDuration(), right.callCount());
        return new ServiceFlowEdge(
                left.srcService(),
                left.dstService(),
                callCnt,
                errCnt,
                avgDuration,
                left.srcServiceId(),
                left.dstServiceId());
    }

    private static double weightedAvg(double leftAvg, long leftCnt, double rightAvg, long rightCnt) {
        long total = leftCnt + rightCnt;
        if (total <= 0) {
            return 0;
        }
        return (leftAvg * leftCnt + rightAvg * rightCnt) / total;
    }
}
