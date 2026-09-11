package com.databuff.apm.demo.support;

import com.databuff.apm.demo.fault.DemoFaultSnapshot;
import com.google.protobuf.ByteString;

/** One synthetic checkout trace batch plus span ids for correlated OTLP logs. */
public record DemoTraceBatch(
        byte[] traceBytes,
        ByteString traceId,
        long traceStartNanos,
        long traceEndNanos,
        DemoFaultSnapshot fault,
        SpanRef serviceARoot,
        SpanRef serviceAHttpClient,
        SpanRef serviceBHttpServer,
        SpanRef serviceBOrderCache,
        SpanRef serviceBOrderDatabase) {

    public record SpanRef(
            ByteString spanId,
            String service,
            String instanceId,
            String hostName,
            long timeNanos) {
    }
}
