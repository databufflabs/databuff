package com.databuff.apm.ingest.pipeline.component;

import com.databuff.apm.ingest.event.TraceEvent;
import com.databuff.apm.ingest.component.TraceComponent;
import com.databuff.apm.ingest.support.IngestTestComponents;
import com.databuff.apm.common.storage.DorisBatchWriter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractComponentTest {

    @Test
    void emitFailsBeforeStart() {
        TraceComponent component = IngestTestComponents.trace(
                IngestTestComponents.aggregate(new DorisBatchWriter()),
                new DorisBatchWriter());
        assertThat(component.emit("k", new TraceEvent(null))).isFalse();
    }

    @Test
    void closeIsSafeWhenNotStarted() {
        TraceComponent component = IngestTestComponents.trace(
                IngestTestComponents.aggregate(new DorisBatchWriter()),
                new DorisBatchWriter());
        component.close();
    }

    @Test
    void emitFailsWhenDisabledEvenAfterStart() {
        TraceComponent component = IngestTestComponents.trace(
                IngestTestComponents.aggregate(new DorisBatchWriter()),
                new DorisBatchWriter());
        try {
            component.start(1);
            component.setEnabled(false);
            assertThat(component.emit("k", new TraceEvent(null))).isFalse();
        } finally {
            component.close();
        }
    }

    @Test
    void startAndInitWireUpTaskPool() {
        TraceComponent component = IngestTestComponents.trace(
                IngestTestComponents.aggregate(new DorisBatchWriter()),
                new DorisBatchWriter());
        try {
            component.start(2);
            assertThat(component.taskPool()).isNotNull();
        } finally {
            component.close();
        }
    }

    @Test
    void samplePipelineQueueGaugesIsSafeWithoutPool() {
        TraceComponent component = IngestTestComponents.trace(
                IngestTestComponents.aggregate(new DorisBatchWriter()),
                new DorisBatchWriter());
        component.samplePipelineQueueGauges();
    }

    @Test
    void samplePipelineQueueGaugesReportsAsyncTaskUsage() {
        TraceComponent component = IngestTestComponents.trace(
                IngestTestComponents.aggregate(new DorisBatchWriter()),
                new DorisBatchWriter());
        try {
            component.start(2);
            component.samplePipelineQueueGauges();
            assertThat(component.taskPool().getTaskSize()).isEqualTo(2);
        } finally {
            component.close();
        }
    }
}
