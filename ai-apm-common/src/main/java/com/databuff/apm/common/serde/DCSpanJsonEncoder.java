package com.databuff.apm.common.serde;

import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.storage.DorisVarcharLimits;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Hand-rolled NDJSON encoder for {@link DcSpan}.
 * Avoids bean-introspection / {@code ObjectMapper.writeValue} cost on the Stream Load hot path.
 * <p>
 * The {@code meta} field is written via Jackson's native {@link JsonGenerator#writeStringField},
 * which uses the optimized {@code UTF8JsonGenerator._writeStringSegment} escaper. The
 * stringified-JSON content is produced once in {@code OtelConverter.buildDcSpan} (via
 * {@link OtelAttributeMaps#encode}) and re-escaped by Jackson here; profiling showed Jackson's
 * native escaper beats a hand-rolled {@code StringBuilder}+{@code writeRawValue} path.
 */
public final class DCSpanJsonEncoder {

    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    private static final ThreadLocal<ByteArrayOutputStream> BUFFERS =
            ThreadLocal.withInitial(() -> new ByteArrayOutputStream(2048));

    private DCSpanJsonEncoder() {
    }

    public static byte[] encode(DcSpan span) throws IOException {
        OtelAttributeMaps.materialize(span);
        ByteArrayOutputStream buffer = BUFFERS.get();
        buffer.reset();
        try (JsonGenerator gen = JSON_FACTORY.createGenerator(buffer)) {
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
            writeString(gen, "meta", DorisVarcharLimits.truncate(span.meta, DorisVarcharLimits.SPAN_META));
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
        }
        return buffer.toByteArray();
    }

    private static void writeString(JsonGenerator gen, String field, String value) throws IOException {
        if (value != null) {
            gen.writeStringField(field, value);
        }
    }
}
