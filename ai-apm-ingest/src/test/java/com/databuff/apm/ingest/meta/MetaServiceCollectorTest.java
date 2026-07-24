package com.databuff.apm.ingest.meta;

import com.databuff.apm.common.storage.DorisJsonRow;

import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.common.storage.DorisBatchWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetaServiceCollectorTest {

    private MetaServiceRegistry registry;

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.stop();
        }
    }

    @Test
    void stagesUniqueServiceRows() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryMetaServices(anyString())).thenReturn(List.of());
        registry = new MetaServiceRegistry(reader, "databuff", 60_000L);
        registry.start();

        DorisBatchWriter writer = new DorisBatchWriter();
        MetaServiceCollector collector = new MetaServiceCollector(registry, writer);

        DcSpan span = new DcSpan();
        span.serviceId = "abc123";
        span.service = "checkout";
        span.meta = """
                {"host.name":"host-1","telemetry.sdk.language":"java","process.runtime.name":"OpenJDK","process.runtime.version":"17"}
                """;
        collector.remember(span);
        collector.remember("abc123", "checkout");

        assertThat(collector.stagePending()).isEqualTo(1);
        assertThat(collector.stagePending()).isZero();

        String json = new String(DorisJsonRow.toByteArray(writer.flushAll().get(0)), StandardCharsets.UTF_8);
        assertThat(json).contains("\"id\":\"abc123\"");
        assertThat(json).contains("\"name\":\"checkout\"");
        assertThat(json).contains("\"language\":\"java\"");
        assertThat(json).contains("\"technology\":\"jvm\"");
        assertThat(json).contains("\"fqdn\":\"host-1\"");
    }

    @Test
    void restagesAfterFlushCompleteWhenNameChanges() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryMetaServices(anyString())).thenReturn(List.of());
        registry = new MetaServiceRegistry(reader, "databuff", 60_000L);
        registry.start();

        DorisBatchWriter writer = new DorisBatchWriter();
        MetaServiceCollector collector = new MetaServiceCollector(registry, writer);

        collector.remember("svc-1", "Old Name");
        assertThat(collector.stagePending()).isEqualTo(1);
        writer.flushAll();
        collector.onFlushComplete();

        collector.remember("svc-1", "New Name");
        assertThat(collector.stagePending()).isEqualTo(1);
        String json = new String(DorisJsonRow.toByteArray(writer.flushAll().get(0)), StandardCharsets.UTF_8);
        assertThat(json).contains("\"name\":\"New Name\"");
    }
}
