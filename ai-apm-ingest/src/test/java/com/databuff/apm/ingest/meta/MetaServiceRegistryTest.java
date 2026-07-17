package com.databuff.apm.ingest.meta;

import com.databuff.apm.common.meta.MetaServiceInfo;
import com.databuff.apm.common.query.ApmQueryModels.MetaServicePoint;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.common.storage.DorisBatchWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetaServiceRegistryTest {

    private MetaServiceRegistry registry;

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.stop();
        }
    }

    @Test
    void syncLoadsCatalogFromDoris() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryMetaServices(anyString())).thenReturn(List.of(
                MetaServicePoint.minimal("svc-1", "Order Service")));
        registry = new MetaServiceRegistry(reader, "databuff", 60_000L);
        registry.start();

        assertThat(registry.getByServiceId("svc-1")).isPresent();
        assertThat(registry.getByServiceId("svc-1").orElseThrow().name()).isEqualTo("Order Service");
        assertThat(registry.getByName("Order Service")).isPresent();
    }

    @Test
    void stagesInsertForUnknownService() {
        registry = new MetaServiceRegistry(mock(ApmReadRepository.class), "databuff", 60_000L);
        registry.start();

        DorisBatchWriter writer = new DorisBatchWriter(8);
        registry.remember(MetaServiceInfo.minimal("new-svc", "Checkout"));
        assertThat(registry.stagePending(writer)).isEqualTo(1);
        assertThat(writer.flushAll()).hasSize(1);
    }

    @Test
    void stagesUpdateWhenEnrichmentChanges() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryMetaServices(anyString())).thenReturn(List.of(
                MetaServicePoint.minimal("svc-1", "Old Name")));
        registry = new MetaServiceRegistry(reader, "databuff", 60_000L);
        registry.start();

        registry.remember(MetaServiceInfo.minimal("svc-1", "New Name"));
        DorisBatchWriter writer = new DorisBatchWriter(8);
        assertThat(registry.stagePending(writer)).isEqualTo(1);
        String json = new String(writer.flushAll().get(0));
        assertThat(json).contains("\"name\":\"New Name\"");
    }

    @Test
    void stagesUpdateWhenLanguageAppearsFromEmpty() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryMetaServices(anyString())).thenReturn(List.of(
                MetaServicePoint.minimal("svc-1", "checkout")));
        registry = new MetaServiceRegistry(reader, "databuff", 60_000L);
        registry.start();

        registry.remember(MetaServiceInfo.fromNames(
                "svc-1",
                "checkout",
                "checkout",
                java.util.Map.of(
                        "telemetry.sdk.language", "java",
                        "process.runtime.name", "OpenJDK",
                        "process.runtime.version", "17"),
                false));
        DorisBatchWriter writer = new DorisBatchWriter(8);
        assertThat(registry.stagePending(writer)).isEqualTo(1);
        String json = new String(writer.flushAll().get(0));
        assertThat(json).contains("\"language\":\"java\"");
        assertThat(json).contains("\"technology\":\"jvm\"");
    }
}
