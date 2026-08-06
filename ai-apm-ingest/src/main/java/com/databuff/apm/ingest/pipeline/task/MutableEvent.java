package com.databuff.apm.ingest.pipeline.task;

public final class MutableEvent {

    private Object key;
    private Object event;
    /** NanoTime when published into the Disruptor ring (0 if unset). */
    private long enqueueNanos;

    public void set(Object key, Object event) {
        set(key, event, 0L);
    }

    public void set(Object key, Object event, long enqueueNanos) {
        this.key = key;
        this.event = event;
        this.enqueueNanos = enqueueNanos;
    }

    public Object getKey() {
        return key;
    }

    public Object getEvent() {
        return event;
    }

    public long getEnqueueNanos() {
        return enqueueNanos;
    }

    public void clear() {
        key = null;
        event = null;
        enqueueNanos = 0L;
    }
}
