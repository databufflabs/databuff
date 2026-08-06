package com.databuff.apm.ingest.receiver;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class OtlpHttpCompressionFilterTest {

    private final OtlpHttpCompressionFilter filter = new OtlpHttpCompressionFilter();

    @Test
    void decompressesGzipBodyBeforeChain() throws Exception {
        byte[] raw = "hello-otlp".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gzipped)) {
            gzip.write(raw);
        }

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/traces");
        request.addHeader("Content-Encoding", "gzip");
        request.setContent(gzipped.toByteArray());
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.request.getInputStream().readAllBytes()).isEqualTo(raw);
        assertThat(chain.request.getHeader("Content-Encoding")).isNull();
    }

    @Test
    void rejectsUnsupportedEncoding() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/metrics");
        request.addHeader("Content-Encoding", "brotli");
        request.setContent(new byte[] {1, 2, 3});
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        verifyNoInteractions(chain);
    }

    @Test
    void passesThroughNonOtlpPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/other");
        request.setContent(new byte[] {1, 2, 3});
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.request).isSameAs(request);
    }

    @Test
    void passesThroughIdentityEncoding() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/traces");
        request.addHeader("Content-Encoding", "identity");
        request.setContent(new byte[] {1, 2, 3});
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.request).isSameAs(request);
    }

    @Test
    void rejectsCorruptCompressedBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/traces");
        request.addHeader("Content-Encoding", "gzip");
        request.setContent(new byte[] {1, 2, 3});
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        verifyNoInteractions(chain);
    }

    @Test
    void decompressedRequestExposesBodyAndHidesEncodings() throws Exception {
        byte[] raw = "hello-otlp".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gzipped)) {
            gzip.write(raw);
        }
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/metrics");
        request.addHeader("Content-Encoding", "gzip");
        request.addHeader("X-Custom", "keep-me");
        request.setContent(gzipped.toByteArray());
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request, response, chain);

        ServletInputStream in = chain.request.getInputStream();
        assertThat(in.isFinished()).isFalse();
        assertThat(in.isReady()).isTrue();
        in.setReadListener(null);
        assertThat(in.read()).isEqualTo('h');
        byte[] rest = new byte[raw.length - 1];
        assertThat(in.read(rest, 0, rest.length)).isEqualTo(rest.length);
        assertThat(chain.request.getContentLength()).isEqualTo(raw.length);
        assertThat(chain.request.getContentLengthLong()).isEqualTo(raw.length);
        assertThat(chain.request.getHeader("Content-Encoding")).isNull();
        assertThat(chain.request.getHeader("Content-Length")).isNull();
        assertThat(chain.request.getHeader("X-Custom")).isEqualTo("keep-me");
        assertThat(Collections.list(chain.request.getHeaders("Content-Encoding"))).isEmpty();
        assertThat(Collections.list(chain.request.getHeaders("X-Custom"))).hasSize(1);
        assertThat(Collections.list(chain.request.getHeaderNames()))
                .doesNotContain("Content-Encoding", "Content-Length")
                .contains("X-Custom");
    }

    private static final class RecordingChain implements FilterChain {
        private HttpServletRequest request;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            this.request = (HttpServletRequest) request;
        }
    }
}
