package com.databuff.apm.ingest.pipeline.task;

import com.databuff.apm.common.platform.PlatformMetricNames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncTaskPipelineMetricsTest {

    private RecordingTask task;

    @AfterEach
    void tearDown() {
        if (task != null) {
            task.close();
        }
    }

    @Test
    void mutableEventStoresEnqueueNanos() {
        MutableEvent event = new MutableEvent();
        event.set("k", "v", 123L);
        assertThat(event.getEnqueueNanos()).isEqualTo(123L);
        event.clear();
        assertThat(event.getEnqueueNanos()).isZero();
    }

    @Test
    void processesEventWithPipelineDim() throws Exception {
        task = new RecordingTask(16, PlatformMetricNames.PIPELINE_TRACE);
        task.init();
        assertThat(task.handleEvent("k", "v")).isTrue();
        assertThat(task.awaitProcessed(2, TimeUnit.SECONDS)).isTrue();
    }

    private static final class RecordingTask extends AsyncTask {

        private final CountDownLatch processed = new CountDownLatch(1);

        RecordingTask(int bufferSize, String pipelineDim) {
            super(bufferSize, 0, pipelineDim);
        }

        boolean awaitProcessed(long timeout, TimeUnit unit) throws InterruptedException {
            return processed.await(timeout, unit);
        }

        @Override
        protected void processEvent(Object key, Object event) {
            processed.countDown();
        }
    }
}
