package com.databuff.apm.ingest.receiver.compression;

import net.jpountz.lz4.LZ4FrameInputStream;
import org.xerial.snappy.Snappy;
import org.xerial.snappy.SnappyFramedInputStream;
import com.github.luben.zstd.ZstdInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Decompresses OTLP/HTTP request bodies based on {@code Content-Encoding}, matching
 * OpenTelemetry Collector confighttp algorithms:
 * {@code gzip}, {@code zstd}, {@code zlib}, {@code deflate}, {@code snappy},
 * {@code x-snappy-framed}, {@code lz4}.
 */
public final class OtlpHttpContentDecoder {

    /** Framing magic used by snappy framed streams (and historical collector {@code snappy}). */
    private static final byte[] SNAPPY_FRAMING_HEADER = {
            (byte) 0xff, 0x06, 0x00, 0x00,
            0x73, 0x4e, 0x61, 0x50, 0x70, 0x59
    };

    private static final Set<String> SUPPORTED = Set.of(
            "gzip",
            "zstd",
            "zlib",
            "deflate",
            "snappy",
            "x-snappy-framed",
            "lz4");

    private OtlpHttpContentDecoder() {
    }

    public static boolean isSupported(String encoding) {
        if (encoding == null || encoding.isBlank()) {
            return true;
        }
        String normalized = normalize(encoding);
        return normalized.isEmpty()
                || "identity".equals(normalized)
                || "none".equals(normalized)
                || SUPPORTED.contains(normalized);
    }

    public static String normalize(String encoding) {
        return encoding == null ? "" : encoding.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns a stream of uncompressed bytes. Empty / identity / none encodings return {@code in} as-is.
     *
     * @throws UnsupportedCompressionException if the encoding is not supported
     * @throws IOException if decompression fails
     */
    public static InputStream decode(String encoding, InputStream in) throws IOException {
        String normalized = normalize(encoding);
        if (normalized.isEmpty() || "identity".equals(normalized) || "none".equals(normalized)) {
            return in;
        }
        return switch (normalized) {
            case "gzip" -> new GZIPInputStream(in);
            case "zlib", "deflate" -> new InflaterInputStream(in);
            case "zstd" -> new ZstdInputStream(in);
            case "lz4" -> new LZ4FrameInputStream(in);
            case "x-snappy-framed" -> new SnappyFramedInputStream(in);
            case "snappy" -> decodeSnappy(in);
            default -> throw new UnsupportedCompressionException(
                    "unsupported Content-Encoding: " + encoding);
        };
    }

    /**
     * Auto-detects block vs framed snappy for {@code Content-Encoding: snappy}, matching collector behavior.
     */
    private static InputStream decodeSnappy(InputStream in) throws IOException {
        byte[] compressed = in.readAllBytes();
        if (compressed.length >= SNAPPY_FRAMING_HEADER.length
                && Arrays.equals(
                        Arrays.copyOf(compressed, SNAPPY_FRAMING_HEADER.length),
                        SNAPPY_FRAMING_HEADER)) {
            return new SnappyFramedInputStream(new ByteArrayInputStream(compressed));
        }
        return new ByteArrayInputStream(Snappy.uncompress(compressed));
    }
}
