package com.databuff.apm.common.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

/**
 * Streams NDJSON from {@link DorisJsonRow}s: encode one row at a time into a reusable buffer,
 * insert {@code \n} between rows. Avoids joining all lines into one {@code byte[]} before HTTP.
 */
public final class NdjsonRowsInputStream extends InputStream {

    private final List<? extends DorisJsonRow> rows;
    private final RowBuffer rowBuffer = new RowBuffer(4096);

    private int rowIndex;
    private byte[] current = RowBuffer.EMPTY;
    private int pos;
    private int end;
    private boolean done;

    public NdjsonRowsInputStream(List<? extends DorisJsonRow> rows) {
        this.rows = Objects.requireNonNull(rows, "rows");
        if (rows.isEmpty()) {
            done = true;
        }
    }

    @Override
    public int read() throws IOException {
        if (!ensureData()) {
            return -1;
        }
        return current[pos++] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return 0;
        }
        if (!ensureData()) {
            return -1;
        }
        int n = Math.min(len, end - pos);
        System.arraycopy(current, pos, b, off, n);
        pos += n;
        return n;
    }

    private boolean ensureData() throws IOException {
        while (pos >= end) {
            if (done) {
                return false;
            }
            fillNext();
        }
        return true;
    }

    private void fillNext() throws IOException {
        if (rowIndex >= rows.size()) {
            done = true;
            pos = 0;
            end = 0;
            return;
        }
        rowBuffer.reset();
        if (rowIndex > 0) {
            rowBuffer.write('\n');
        }
        try {
            rows.get(rowIndex).writeTo(rowBuffer);
        } catch (UncheckedIOException e) {
            throw e.getCause() != null ? e.getCause() : new IOException(e);
        }
        current = rowBuffer.array();
        pos = 0;
        end = rowBuffer.size();
        rowIndex++;
    }

    /** Growable buffer that exposes its array without {@code toByteArray()} copying. */
    static final class RowBuffer extends OutputStream {
        static final byte[] EMPTY = new byte[0];

        private byte[] buf;
        private int count;

        RowBuffer(int initial) {
            this.buf = new byte[Math.max(32, initial)];
        }

        void reset() {
            count = 0;
        }

        byte[] array() {
            return buf;
        }

        int size() {
            return count;
        }

        @Override
        public void write(int b) {
            ensureCapacity(count + 1);
            buf[count++] = (byte) b;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            Objects.checkFromIndexSize(off, len, b.length);
            ensureCapacity(count + len);
            System.arraycopy(b, off, buf, count, len);
            count += len;
        }

        private void ensureCapacity(int minCapacity) {
            if (minCapacity <= buf.length) {
                return;
            }
            int newCap = Math.max(buf.length << 1, minCapacity);
            byte[] next = new byte[newCap];
            System.arraycopy(buf, 0, next, 0, count);
            buf = next;
        }
    }
}
