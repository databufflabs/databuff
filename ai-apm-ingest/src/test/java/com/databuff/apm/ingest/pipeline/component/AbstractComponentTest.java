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
}
