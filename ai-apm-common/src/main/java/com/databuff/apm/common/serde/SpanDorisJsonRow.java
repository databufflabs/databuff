package com.databuff.apm.common.serde;

import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.storage.DorisJsonRow;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/** Lazily encodes a {@link DcSpan} as one Doris NDJSON line on Stream Load flush. */
public final class SpanDorisJsonRow implements DorisJsonRow {

    private final DcSpan span;

    public SpanDorisJsonRow(DcSpan span) {
        this.span = Objects.requireNonNull(span, "span");
    }

    @Override
    public void writeTo(OutputStream out) throws IOException {
        DCSpanJsonEncoder.encodeTo(span, out);
    }

    /**
     * Lightweight size heuristic for batch thresholds (avoids a full encode on the offer path).
     * Fixed JSON overhead plus dominant string fields; metaMap used when meta is not materialized.
     */
    @Override
    public long estimatedBytes() {
        long n = 384L;
        n += len(span.serviceId);
        n += len(span.resource);
        n += len(span.span_id);
        n += len(span.trace_id);
        n += len(span.parent_id);
        n += len(span.service);
        n += len(span.serviceInstance);
        n += len(span.srcService);
        n += len(span.srcServiceId);
        n += len(span.srcServiceInstance);
        n += len(span.dstService);
        n += len(span.dstServiceId);
        n += len(span.dstServiceInstance);
        n += len(span.hostName);
        n += len(span.type);
        n += len(span.host_id);
        n += len(span.name);
        n += len(span.metrics);
        n += len(span.metaHttpUrl);
        n += len(span.metaErrorType);
        n += len(span.metaPeerHostname);
        n += len(span.metaHttpMethod);
        n += len(span.startTime);
        if (span.meta != null) {
            n += span.meta.length() * 2L;
        } else if (span.metaMap != null) {
            for (var e : span.metaMap.entrySet()) {
                n += len(e.getKey()) + len(e.getValue()) + 8L;
            }
        }
        return n + 1L;
    }

    private static long len(String s) {
        return s == null ? 0L : s.length();
    }
}
