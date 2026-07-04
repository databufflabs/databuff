package com.databuff.apm.demo.support;

import com.google.protobuf.ByteString;

/** One synthetic checkout trace batch plus span ids for correlated OTLP logs. */
public record DemoTraceBatch(
        byte[] traceBytes,
        ByteString traceId,
        long traceStartNanos,
        long traceEndNanos,
        SpanRef serviceARoot,
        SpanRef serviceAHttpClient,
        SpanRef serviceBHttpServer,
        SpanRef serviceBDubboMysql) {

    public record SpanRef(
            ByteString spanId,
            String service,
            String instanceId,
            String hostName,
            long timeNanos) {
    }
}
