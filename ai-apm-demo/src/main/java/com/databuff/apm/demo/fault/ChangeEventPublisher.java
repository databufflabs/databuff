package com.databuff.apm.demo.fault;

/** Synchronous publisher: returning from publish means the broker acknowledged the event. */
public interface ChangeEventPublisher extends AutoCloseable {

    void publish(DemoConfigurationChange change) throws Exception;

    boolean available();

    String topic();

    @Override
    default void close() {
    }
}
