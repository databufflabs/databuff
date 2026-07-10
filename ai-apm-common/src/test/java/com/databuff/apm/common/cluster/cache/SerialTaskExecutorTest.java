package com.databuff.apm.common.cluster.cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SerialTaskExecutorTest {

    @Test
    void runExecutesTaskOnWorkerThread() {
        AtomicInteger counter = new AtomicInteger();
        try (SerialTaskExecutor executor = new SerialTaskExecutor("cache-test")) {
            executor.run(counter::incrementAndGet);
        }
        assertThat(counter).hasValue(1);
    }

    @Test
    void callReturnsValue() {
        try (SerialTaskExecutor executor = new SerialTaskExecutor("cache-test")) {
            assertThat(executor.call(() -> "ok")).isEqualTo("ok");
        }
    }

    @Test
    void runPropagatesRuntimeException() {
        try (SerialTaskExecutor executor = new SerialTaskExecutor("cache-test")) {
            assertThatThrownBy(() -> executor.run(() -> {
                throw new IllegalArgumentException("boom");
            })).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("boom");
        }
    }

    @Test
    void callWrapsCheckedException() {
        try (SerialTaskExecutor executor = new SerialTaskExecutor("cache-test")) {
            assertThatThrownBy(() -> executor.call(() -> {
                throw new Exception("checked");
            })).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Serial cache task failed")
                    .cause()
                    .hasMessage("checked");
        }
    }

    @Test
    void tasksRunSerially() {
        AtomicInteger last = new AtomicInteger();
        try (SerialTaskExecutor executor = new SerialTaskExecutor("cache-test")) {
            executor.run(() -> last.set(1));
            executor.run(() -> assertThat(last).hasValue(1));
            executor.run(() -> last.set(2));
        }
        assertThat(last).hasValue(2);
    }
}
