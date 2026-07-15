package com.databuff.apm.ingest.metric;

import com.databuff.apm.common.metric.MetricSchemaRegistry;
import com.databuff.apm.common.storage.DorisBatchWriter;
import com.databuff.apm.common.storage.DorisStreamLoadSink;
import com.databuff.apm.common.storage.DorisStreamLoader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Batch writers and stream-load sinks for all Doris metric tables used by ingest. */
public final class MetricTableWriterRegistry {

    private final Map<String, DorisBatchWriter> writersByTable;
    private final List<DorisStreamLoadSink> sinks;

    private MetricTableWriterRegistry(Map<String, DorisBatchWriter> writersByTable, List<DorisStreamLoadSink> sinks) {
        this.writersByTable = Map.copyOf(writersByTable);
        this.sinks = List.copyOf(sinks);
    }

    public static MetricTableWriterRegistry create(DorisStreamLoader loader, String database) {
        Map<String, DorisBatchWriter> writers = new LinkedHashMap<>();
        List<DorisStreamLoadSink> sinkList = new ArrayList<>();
        for (String table : MetricSchemaRegistry.allTableNames()) {
            DorisBatchWriter writer = new DorisBatchWriter(1024);
            writers.put(table, writer);
            sinkList.add(new DorisStreamLoadSink(writer, loader, database, table));
        }
        return new MetricTableWriterRegistry(writers, sinkList);
    }

    public Map<String, DorisBatchWriter> writersByTable() {
        return writersByTable;
    }

    public List<DorisStreamLoadSink> sinks() {
        return sinks;
    }

    public DorisBatchWriter writer(String table) {
        return writersByTable.get(table);
    }
}
