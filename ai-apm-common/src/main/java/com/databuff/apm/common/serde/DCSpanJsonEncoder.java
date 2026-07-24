package com.databuff.apm.common.serde;

import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.storage.DorisVarcharLimits;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Hand-rolled NDJSON encoder for {@link DcSpan}.
 * Avoids bean-introspection / {@code ObjectMapper.writeValue} cost on the Stream Load hot path.
 * <p>
 * {@code meta} is a Doris VARCHAR holding JSON text. The working {@link DcSpan#metaMap} is
 * stringified once via lightweight {@link OtelAttributeMaps#encode} (no ObjectMapper), then
 * written with Jackson's {@link JsonGenerator#writeString} so the outer NDJSON escapes it as a
 * JSON string field. Nested-object wire form was rejected: decoder / Stream Load expect a string.
 */
public final class DCSpanJsonEncoder {

    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    private static final ThreadLocal<ByteArrayOutputStream> BUFFERS =
            ThreadLocal.withInitial(() -> new ByteArrayOutputStream(2048));
    private static final ThreadLocal<char[]> META_CHARS =
            ThreadLocal.withInitial(() -> new char[512]);

    private DCSpanJsonEncoder() {
    }

    /**
     * Encode into a fresh {@code byte[]} (tests / cluster forward). Prefer {@link #encodeTo}
     * on the Stream Load path to avoid an extra copy.
     */
    public static byte[] encode(DcSpan span) throws IOException {
        ByteArrayOutputStream buffer = BUFFERS.get();
        buffer.reset();
        encodeTo(span, buffer);
        return buffer.toByteArray();
    }

    /** Write one JSON object (no trailing newline) to {@code out}. Does not close {@code out}. */
    public static void encodeTo(DcSpan span, OutputStream out) throws IOException {
        OtelAttributeMaps.materialize(span);
        JsonGenerator gen = JSON_FACTORY.createGenerator(out);
        gen.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
        try {
            gen.writeStartObject();
            gen.writeNumberField("minutes", span.minutes);
            writeString(gen, "serviceId", span.serviceId);
            writeString(gen, "resource", DorisVarcharLimits.truncate(span.resource, DorisVarcharLimits.SPAN_RESOURCE));
            gen.writeNumberField("error", span.error);
            gen.writeNumberField("slow", span.slow);
            gen.writeNumberField("hours", span.hours);
            writeString(gen, "span_id", span.span_id);
            writeString(gen, "startTime", span.startTime);
            gen.writeNumberField("is_parent", span.is_parent);
            writeString(gen, "trace_id", span.trace_id);
            writeString(gen, "parent_id", span.parent_id);
            writeString(gen, "service", span.service);
            writeString(gen, "serviceInstance", span.serviceInstance);
            writeString(gen, "srcService", span.srcService);
            writeString(gen, "srcServiceId", span.srcServiceId);
            writeString(gen, "srcServiceInstance", span.srcServiceInstance);
            writeString(gen, "dstService", span.dstService);
            writeString(gen, "dstServiceId", span.dstServiceId);
            writeString(gen, "dstServiceInstance", span.dstServiceInstance);
            gen.writeNumberField("end", span.end);
            writeString(gen, "hostName", span.hostName);
            writeString(gen, "type", span.type);
            gen.writeNumberField("isIn", span.isIn);
            gen.writeNumberField("duration", span.duration);
            gen.writeNumberField("start", span.start);
            writeString(gen, "host_id", span.host_id);
            writeMeta(gen, span.meta);
            writeString(gen, "name", span.name);
            gen.writeNumberField("isOut", span.isOut);
            writeString(gen, "metrics", DorisVarcharLimits.truncate(span.metrics, DorisVarcharLimits.SPAN_METRICS));
            if (span.metaHttpStatusCode != null) {
                gen.writeNumberField("meta.http.status_code", span.metaHttpStatusCode);
            }
            writeString(gen, "meta.error.type", span.metaErrorType);
            writeString(gen, "meta.peer.hostname", span.metaPeerHostname);
            writeString(gen, "meta.http.method", span.metaHttpMethod);
            writeString(gen, "meta.http.url",
                    DorisVarcharLimits.truncate(span.metaHttpUrl, DorisVarcharLimits.URL));
            gen.writeEndObject();
        } finally {
            gen.close();
        }
    }

    private static void writeMeta(JsonGenerator gen, String meta) throws IOException {
        String truncated = DorisVarcharLimits.truncate(meta, DorisVarcharLimits.SPAN_META);
        if (truncated == null) {
            return;
        }
        int len = truncated.length();
        char[] buf = META_CHARS.get();
        if (buf.length < len) {
            buf = new char[len];
            META_CHARS.set(buf);
        }
        truncated.getChars(0, len, buf, 0);
        gen.writeFieldName("meta");
        gen.writeString(buf, 0, len);
    }

    private static void writeString(JsonGenerator gen, String field, String value) throws IOException {
        if (value != null) {
            gen.writeStringField(field, value);
        }
    }
}
