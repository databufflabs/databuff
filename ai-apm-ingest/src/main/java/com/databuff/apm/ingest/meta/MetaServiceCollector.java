package com.databuff.apm.ingest.meta;

import com.databuff.apm.common.meta.MetaServiceInfo;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.ingest.otel.OtlLogLine;
import com.databuff.apm.ingest.otel.OtlMetricLine;
import com.databuff.apm.common.storage.DorisBatchWriter;

import java.util.Objects;

/**
 * Stages Doris {@code meta_service} rows discovered during ingest.
 * Backed by {@link MetaServiceRegistry} (legacy {@code ServiceSyncService} pattern).
 */
public final class MetaServiceCollector {

    private final MetaServiceRegistry registry;
    private final DorisBatchWriter batchWriter;

    public MetaServiceCollector(MetaServiceRegistry registry, DorisBatchWriter batchWriter) {
        this.registry = Objects.requireNonNull(registry);
        this.batchWriter = Objects.requireNonNull(batchWriter);
    }

    public void remember(DcSpan span) {
        MetaServiceInfo info = MetaServiceInfo.fromDcSpan(span);
        remember(info);
    }

    public void remember(MetaServiceInfo info) {
        if (info != null) {
            registry.remember(info);
        }
    }

    public void remember(OtlMetricLine line) {
        if (line == null) {
            return;
        }
        MetaServiceInfo info = MetaServiceInfo.fromMetric(line.serviceId(), line.service(), line.resourceMeta());
        if (info != null) {
            registry.remember(info);
        }
    }

    public void remember(OtlLogLine line) {
        if (line == null) {
            return;
        }
        remember(line.serviceId(), line.service());
    }

    public void remember(String serviceId, String serviceName) {
        if (serviceId == null || serviceId.isBlank()) {
            return;
        }
        registry.remember(MetaServiceInfo.minimal(serviceId, serviceName));
    }

    public int stagePending() {
        return registry.stagePending(batchWriter);
    }

    public void onFlushComplete() {
        registry.onFlushComplete();
    }
}
