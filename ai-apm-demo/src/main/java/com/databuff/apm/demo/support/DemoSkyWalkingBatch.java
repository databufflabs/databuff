package com.databuff.apm.demo.support;

import com.databuff.apm.demo.fault.DemoFaultSnapshot;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentCollection;

/** SkyWalking checkout demo batch: segments + span refs for correlated logs. */
public record DemoSkyWalkingBatch(
        SegmentCollection segments,
        String traceId,
        String segmentAId,
        String segmentBId,
        long traceStartMs,
        long traceEndMs,
        DemoFaultSnapshot fault,
        SpanRef serviceARoot,
        SpanRef serviceAHttpClient,
        SpanRef serviceBHttpServer,
        SpanRef serviceBOrderCache,
        SpanRef serviceBOrderDatabase) {

    public record SpanRef(
            String segmentId,
            int spanId,
            String service,
            String serviceInstance,
            String hostName,
            long timeMs) {
    }
}
