package com.databuff.apm.common.storage;

import java.io.IOException;
import java.io.OutputStream;

/**
 * One NDJSON line for Doris Stream Load.
 * <p>
 * Rows may encode lazily on {@link #writeTo(OutputStream)} so flush can stream into HTTP
 * without first materializing every line as {@code byte[]} and joining them.
 * Implementations must be idempotent: failed Stream Loads re-queue the same row instances.
 */
@FunctionalInterface
public interface DorisJsonRow {

    void writeTo(OutputStream out) throws IOException;

    /**
     * Approximate encoded size in bytes (including a trailing NDJSON newline) for batch flush
     * thresholds. Exact for {@link BytesDorisJsonRow}; heuristic for lazy rows.
     */
    default long estimatedBytes() {
        return 256L;
    }

    static DorisJsonRow ofBytes(byte[] jsonLine) {
        return new BytesDorisJsonRow(jsonLine);
    }

    /** Materialize one row for tests / diagnostics. */
    static byte[] toByteArray(DorisJsonRow row) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(256);
        try {
            row.writeTo(out);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
