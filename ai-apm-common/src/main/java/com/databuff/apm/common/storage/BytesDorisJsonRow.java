package com.databuff.apm.common.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/** Already-encoded NDJSON line. */
public final class BytesDorisJsonRow implements DorisJsonRow {

    private final byte[] bytes;

    public BytesDorisJsonRow(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes");
    }

    @Override
    public void writeTo(OutputStream out) throws IOException {
        out.write(bytes);
    }

    @Override
    public long estimatedBytes() {
        return bytes.length + 1L;
    }
}
