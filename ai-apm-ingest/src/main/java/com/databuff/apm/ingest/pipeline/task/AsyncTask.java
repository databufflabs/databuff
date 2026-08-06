package com.databuff.apm.ingest.pipeline.task;

import com.databuff.apm.common.platform.PlatformMetricNames;
import com.databuff.apm.common.platform.PlatformMetrics;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventTranslatorTwoArg;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public abstract class AsyncTask implements Task, EventHandler<MutableEvent> {

    private static final EventTranslatorTwoArg<MutableEvent, Object, Object> TRANSLATOR =
            (event, sequence, key, payload) -> event.set(key, payload, System.nanoTime());

    private final int bufferSize;
    private final int index;
    /** Relative name fragment for {@link PlatformMetricNames#pipeline(String, String)}; empty disables metrics. */
    private final String pipelineDim;
    private final AtomicLong overflowCount = new AtomicLong();
    private Disruptor<MutableEvent> disruptor;
    private RingBuffer<MutableEvent> ringBuffer;

    protected AsyncTask(int bufferSize, int index) {
        this(bufferSize, index, "");
    }

    protected AsyncTask(int bufferSize, int index, String pipelineDim) {
        this.bufferSize = bufferSize;
        this.index = index;
        this.pipelineDim = pipelineDim == null ? "" : pipelineDim;
    }

    @Override
    public void init() {
        disruptor = new Disruptor<>(
                MutableEvent::new,
                bufferSize,
                DaemonThreadFactory.INSTANCE,
                ProducerType.MULTI,
                new com.lmax.disruptor.BlockingWaitStrategy());
        ringBuffer = disruptor.getRingBuffer();
        disruptor.handleEventsWith(this);
        disruptor.start();
    }

    @Override
    public void onEvent(MutableEvent mutableEvent, long sequence, boolean endOfBatch) {
        Object key = mutableEvent.getKey();
        Object event = mutableEvent.getEvent();
        long enqueuedAt = mutableEvent.getEnqueueNanos();
        mutableEvent.clear();
        try {
            processEvent(key, event);
        } finally {
            recordProcessed(enqueuedAt);
        }
    }

    protected abstract void processEvent(Object key, Object event);

    private void recordProcessed(long enqueuedAtNanos) {
        if (pipelineDim.isEmpty()) {
            return;
        }
        PlatformMetrics.counter(PlatformMetricNames.pipeline(pipelineDim, PlatformMetricNames.KIND_REQ)).inc();
        if (enqueuedAtNanos > 0L) {
            long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - enqueuedAtNanos);
            PlatformMetrics.timer(PlatformMetricNames.pipeline(pipelineDim, PlatformMetricNames.KIND_COST_MS))
                    .add(Math.max(0L, costMs));
        }
    }

    @Override
    public void onShutdown() {
        // hook for subclasses
    }

    @Override
    public void close() {
        if (disruptor != null) {
            try {
                disruptor.shutdown(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                disruptor.halt();
            }
        }
        onShutdown();
    }

    @Override
    public boolean handleEvent(Object key, Object event) {
        if (ringBuffer == null) {
            return false;
        }
        if (ringBuffer.tryPublishEvent(TRANSLATOR, key, event)) {
            return true;
        }
        if (ringBuffer.tryPublishEvent(TRANSLATOR, key, event)) {
            return true;
        }
        overflowCount.incrementAndGet();
        if (!pipelineDim.isEmpty()) {
            PlatformMetrics.counter(PlatformMetricNames.pipeline(pipelineDim, PlatformMetricNames.KIND_DROP)).inc();
        }
        return false;
    }

    public long overflowCount() {
        return overflowCount.get();
    }

    public int bufferSize() {
        return bufferSize;
    }

    /** Slots currently occupied in the Disruptor ring (0 if not started). */
    public long usedSlots() {
        if (ringBuffer == null) {
            return 0L;
        }
        return Math.max(0L, bufferSize - ringBuffer.remainingCapacity());
    }

    @Override
    public String getName() {
        return getClass().getSimpleName() + "-" + index;
    }
}
