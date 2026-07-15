package com.databuff.apm.common.serde;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Thread-local {@link ByteArrayOutputStream} reuse for hot Jackson encode paths.
 * Avoids per-call {@code writeValueAsBytes} allocating a fresh buffer/generator setup.
 */
public final class ReusableJson {

    private static final ThreadLocal<ByteArrayOutputStream> BUFFERS =
            ThreadLocal.withInitial(() -> new ByteArrayOutputStream(4096));

    private ReusableJson() {
    }

    public static byte[] writeValueAsBytes(ObjectMapper mapper, Object value) throws JsonProcessingException {
        ByteArrayOutputStream buffer = BUFFERS.get();
        buffer.reset();
        try {
            mapper.writeValue(buffer, value);
        } catch (JsonProcessingException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("json encode failed", e);
        }
        return buffer.toByteArray();
    }
}
