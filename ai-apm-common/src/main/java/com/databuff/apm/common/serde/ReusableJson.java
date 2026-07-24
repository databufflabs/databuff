package com.databuff.apm.common.serde;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Thread-local {@link ByteArrayOutputStream} reuse for hot Jackson encode paths.
 * Prefer {@link #writeValue(ObjectMapper, OutputStream, Object)} on Stream Load flush
 * to avoid {@code toByteArray()} copies.
 */
public final class ReusableJson {

    private static final ThreadLocal<ByteArrayOutputStream> BUFFERS =
            ThreadLocal.withInitial(() -> new ByteArrayOutputStream(4096));

    private ReusableJson() {
    }

    public static byte[] writeValueAsBytes(ObjectMapper mapper, Object value) throws JsonProcessingException {
        ByteArrayOutputStream buffer = BUFFERS.get();
        buffer.reset();
        writeValue(mapper, buffer, value);
        return buffer.toByteArray();
    }

    /** Write JSON to {@code out} without closing it. */
    public static void writeValue(ObjectMapper mapper, OutputStream out, Object value)
            throws JsonProcessingException {
        try {
            JsonGenerator gen = mapper.getFactory().createGenerator(out);
            gen.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            try {
                mapper.writeValue(gen, value);
            } finally {
                gen.close();
            }
        } catch (JsonProcessingException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("json encode failed", e);
        }
    }
}
