package com.databuff.apm.ingest.receiver;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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

    private static final class RecordingChain implements FilterChain {
        private HttpServletRequest request;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            this.request = (HttpServletRequest) request;
        }
    }
}
