package com.databuff.apm.ingest.cluster;

import com.databuff.apm.common.cluster.coordination.ClusterPartitionMembership;
import com.databuff.apm.ingest.support.LogRateLimiter;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Stage-tagged cluster pipeline diagnostics.
 *
 * <p>Grep markers (one per critical hop):
 * <ul>
 *   <li>{@code [cluster.membership]} — ZK member add/remove/endpoint change</li>
 *   <li>{@code [cluster.route]} — local-owner vs forward decision</li>
 *   <li>{@code [cluster.forward-out]} — gRPC partial leave this node</li>
 *   <li>{@code [cluster.forward-in]} — gRPC partial arrive / accept / reject</li>
 *   <li>{@code [cluster.assemble]} — local owner assembly path sample</li>
 *   <li>{@code [doris.flush]} — Stream Load / flush scheduler</li>
 * </ul>
 *
 * <p>Failures always emit one WARN with raw fields. Success samples are rate-limited INFO
 * so hot paths stay readable while still proving traffic is flowing.
 */
public final class ClusterPipelineLog {

    public static final String MEMBERSHIP = "[cluster.membership]";
    public static final String ROUTE = "[cluster.route]";
    public static final String FORWARD_OUT = "[cluster.forward-out]";
    public static final String FORWARD_IN = "[cluster.forward-in]";
    public static final String ASSEMBLE = "[cluster.assemble]";
    public static final String DORIS_FLUSH = "[doris.flush]";

    private static final LogRateLimiter ROUTE_FORWARD_OK = new LogRateLimiter(10_000L);
    private static final LogRateLimiter ROUTE_LOCAL_OK = new LogRateLimiter(10_000L);
    private static final LogRateLimiter FORWARD_OUT_OK = new LogRateLimiter(10_000L);
    private static final LogRateLimiter FORWARD_IN_OK = new LogRateLimiter(10_000L);
    private static final LogRateLimiter ASSEMBLE_OK = new LogRateLimiter(10_000L);
    private static final LogRateLimiter DORIS_OK = new LogRateLimiter(10_000L);

    private ClusterPipelineLog() {
    }

    public static String endpointsBrief(ClusterPartitionMembership membership) {
        if (membership == null) {
            return "endpoints={}";
        }
        Map<String, String> endpoints = membership.endpointsByNodeId();
        return "endpoints=" + endpoints;
    }

    public static void membershipChanged(Logger log, String localNodeId, String delta, Map<String, String> endpoints) {
        log.warn(
                "{} CHANGED localNodeId={} {} fullEndpoints={}",
                MEMBERSHIP,
                localNodeId,
                delta,
                endpoints);
    }

    public static void routeForward(
            Logger log,
            String stream,
            String partitionKey,
            String owner,
            ClusterPartitionMembership membership) {
        long tally = ROUTE_FORWARD_OK.record();
        if (tally <= 0) {
            return;
        }
        log.info(
                "{} DECISION=FORWARD stream={} partition={} owner={} tally={} {} {}",
                ROUTE,
                stream,
                ClusterAggregationLog.partitionKeyBrief(partitionKey),
                owner,
                tally,
                ClusterAggregationLog.membershipBrief(membership),
                endpointsBrief(membership));
    }

    public static void routeLocal(
            Logger log,
            String stream,
            String partitionKey,
            ClusterPartitionMembership membership) {
        long tally = ROUTE_LOCAL_OK.record();
        if (tally <= 0) {
            return;
        }
        log.info(
                "{} DECISION=LOCAL_OWNER stream={} partition={} tally={} {} {}",
                ROUTE,
                stream,
                ClusterAggregationLog.partitionKeyBrief(partitionKey),
                tally,
                ClusterAggregationLog.membershipBrief(membership),
                endpointsBrief(membership));
    }

    public static void forwardOutOk(
            Logger log,
            String stream,
            String origin,
            String target,
            String endpoint,
            String partitionKey,
            long windowStart,
            int bytes,
            ClusterPartitionMembership membership) {
        long tally = FORWARD_OUT_OK.record();
        if (tally <= 0) {
            return;
        }
        log.info(
                "{} OK stream={} origin={} target={} endpoint={} partition={} windowMs={} bytes={} tally={} {} {}",
                FORWARD_OUT,
                stream,
                origin,
                target,
                endpoint,
                ClusterAggregationLog.partitionKeyBrief(partitionKey),
                windowStart,
                bytes,
                tally,
                ClusterAggregationLog.membershipBrief(membership),
                endpointsBrief(membership));
    }

    public static void forwardOutFailed(
            Logger log,
            String reason,
            String stream,
            String origin,
            String target,
            String endpoint,
            String partitionKey,
            long windowStart,
            int bytes,
            ClusterPartitionMembership membership,
            String cause) {
        // Always emit raw failure line — accuracy drops are diagnosed from these.
        log.warn(
                "{} FAILED reason={} stream={} origin={} target={} endpoint={} partition={} windowMs={} bytes={} cause={} {} {}",
                FORWARD_OUT,
                reason,
                stream,
                origin,
                target,
                endpoint,
                ClusterAggregationLog.partitionKeyBrief(partitionKey),
                windowStart,
                bytes,
                cause,
                ClusterAggregationLog.membershipBrief(membership),
                endpointsBrief(membership));
    }

    public static void forwardInOk(
            Logger log,
            String stream,
            String origin,
            String partitionKey,
            int partialCount,
            int bytes,
            ClusterPartitionMembership membership) {
        long tally = FORWARD_IN_OK.record();
        if (tally <= 0) {
            return;
        }
        log.info(
                "{} OK stream={} origin={} partition={} partials={} bytes={} tally={} {} {}",
                FORWARD_IN,
                stream,
                origin,
                ClusterAggregationLog.partitionKeyBrief(partitionKey),
                partialCount,
                bytes,
                tally,
                ClusterAggregationLog.membershipBrief(membership),
                endpointsBrief(membership));
    }

    public static void forwardInFailed(
            Logger log,
            String reason,
            String stream,
            String origin,
            String partitionKey,
            int partialCount,
            ClusterPartitionMembership membership,
            String cause) {
        log.warn(
                "{} FAILED reason={} stream={} origin={} partition={} partials={} cause={} {} {}",
                FORWARD_IN,
                reason,
                stream,
                origin,
                ClusterAggregationLog.partitionKeyBrief(partitionKey),
                partialCount,
                cause,
                ClusterAggregationLog.membershipBrief(membership),
                endpointsBrief(membership));
    }

    public static void assembleSample(
            Logger log,
            String stream,
            String partitionKey,
            String how,
            ClusterPartitionMembership membership) {
        long tally = ASSEMBLE_OK.record();
        if (tally <= 0) {
            return;
        }
        log.info(
                "{} OK how={} stream={} partition={} tally={} {} {}",
                ASSEMBLE,
                how,
                stream,
                ClusterAggregationLog.partitionKeyBrief(partitionKey),
                tally,
                ClusterAggregationLog.membershipBrief(membership),
                endpointsBrief(membership));
    }

    public static void dorisFlushFailed(
            Logger log,
            String table,
            String database,
            String detail) {
        log.warn("{} FAILED database={} table={} detail={}", DORIS_FLUSH, database, table, detail);
    }

    public static void dorisFlushDropped(
            Logger log,
            String table,
            String database,
            int consecutiveFailures,
            int rows,
            String sample,
            String cause) {
        log.error(
                "{} DROPPED database={} table={} consecutiveFailures={} rows={} sample={} cause={}",
                DORIS_FLUSH,
                database,
                table,
                consecutiveFailures,
                rows,
                sample,
                cause);
    }

    public static void dorisFlushOkSample(Logger log, String table, String database, int rows) {
        long tally = DORIS_OK.record();
        if (tally <= 0) {
            return;
        }
        log.info(
                "{} OK database={} table={} rows={} tally={}",
                DORIS_FLUSH,
                database,
                table,
                rows,
                tally);
    }
}
