package com.databuff.apm.ingest.support;

import com.databuff.apm.common.cluster.aggregate.ClusterAggregator;
import com.databuff.apm.common.cluster.aggregate.ClusterPartialForwarder;
import com.databuff.apm.ingest.meta.IngestMetaCache;
import com.databuff.apm.common.storage.DorisBatchWriter;
import com.databuff.apm.ingest.component.AggregateComponent;
import com.databuff.apm.ingest.component.TraceComponent;
import com.databuff.apm.ingest.trace.SpanResourceIgnoreFilter;

/** Shared ingest component wiring for unit tests. */
public final class IngestTestComponents {

    private IngestTestComponents() {
    }

    public static AggregateComponent aggregate(DorisBatchWriter writer) {
        return aggregate(new ClusterAggregator("n1"), writer);
    }

    public static AggregateComponent aggregate(ClusterAggregator aggregator, DorisBatchWriter writer) {
        return new AggregateComponent(aggregator, TestClusterMembership.standalone("n1"), writer);
    }

    public static TraceComponent trace(AggregateComponent aggregateComponent, DorisBatchWriter traceWriter) {
        return trace(aggregateComponent, traceWriter, 2_000L);
    }

    public static TraceComponent trace(
            AggregateComponent aggregateComponent,
            DorisBatchWriter traceWriter,
            long assemblyCheckIntervalMs) {
        return trace(aggregateComponent, traceWriter, null, SpanResourceIgnoreFilter.NOOP, assemblyCheckIntervalMs);
    }

    public static TraceComponent trace(
            AggregateComponent aggregateComponent,
            DorisBatchWriter traceWriter,
            IngestMetaCache metaCache,
            long assemblyCheckIntervalMs) {
        return trace(aggregateComponent, traceWriter, metaCache, SpanResourceIgnoreFilter.NOOP, assemblyCheckIntervalMs);
    }

    public static TraceComponent trace(
            AggregateComponent aggregateComponent,
            DorisBatchWriter traceWriter,
            IngestMetaCache metaCache,
            SpanResourceIgnoreFilter resourceIgnoreFilter,
            long assemblyCheckIntervalMs) {
        return new TraceComponent(
                aggregateComponent,
                traceWriter,
                metaCache,
                null,
                null,
                null,
                null,
                TestClusterMembership.standalone("n1"),
                ClusterPartialForwarder.NOOP,
                null,
                resourceIgnoreFilter,
                assemblyCheckIntervalMs,
                1024);
    }
}
