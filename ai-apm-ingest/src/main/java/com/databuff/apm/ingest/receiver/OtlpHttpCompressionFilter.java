package com.databuff.apm.ingest.receiver;

import com.databuff.apm.ingest.receiver.compression.OtlpHttpContentDecoder;
import com.databuff.apm.ingest.receiver.compression.UnsupportedCompressionException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Decompresses OTLP/HTTP request bodies according to {@code Content-Encoding}.
 * <p>
 * Supported encodings (aligned with otel-collector confighttp):
 * {@code gzip}, {@code zstd}, {@code zlib}, {@code deflate}, {@code snappy},
 * {@code x-snappy-framed}, {@code lz4}, plus {@code none}/{@code identity}/empty
 * (no compression). Collector default is {@code gzip}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class OtlpHttpCompressionFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.endsWith("/v1/traces")
                || path.endsWith("/v1/metrics")
                || path.endsWith("/v1/logs"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String encoding = request.getHeader(HttpHeaders.CONTENT_ENCODING);
        String normalized = OtlpHttpContentDecoder.normalize(encoding);
        if (normalized.isEmpty() || "identity".equals(normalized) || "none".equals(normalized)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!OtlpHttpContentDecoder.isSupported(normalized)) {
            response.sendError(
                    HttpServletResponse.SC_BAD_REQUEST,
                    "unsupported Content-Encoding: " + encoding);
            return;
        }
        try {
            byte[] body;
            try (InputStream decoded =
                    OtlpHttpContentDecoder.decode(normalized, request.getInputStream())) {
                body = decoded.readAllBytes();
            }
            filterChain.doFilter(new DecompressedRequest(request, body), response);
        } catch (UnsupportedCompressionException e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            response.sendError(
                    HttpServletResponse.SC_BAD_REQUEST,
                    "failed to decompress request body: " + e.getMessage());
        }
    }

    private static final class DecompressedRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private DecompressedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // unused for synchronous Spring MVC body reading
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public String getHeader(String name) {
            if (isHiddenHeader(name)) {
                return null;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (isHiddenHeader(name)) {
                return Collections.emptyEnumeration();
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            names.removeIf(DecompressedRequest::isHiddenHeader);
            return Collections.enumeration(names);
        }

        private static boolean isHiddenHeader(String name) {
            return HttpHeaders.CONTENT_ENCODING.equalsIgnoreCase(name)
                    || HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name);
        }
    }
}
