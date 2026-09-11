package com.databuff.apm.demo.fault;

/** Keeps normal telemetry available when the optional Demo World Kafka link is not configured. */
public final class UnavailableChangeEventPublisher implements ChangeEventPublisher {

    private final String topic;

    public UnavailableChangeEventPublisher(String topic) {
        this.topic = topic == null || topic.isBlank()
                ? KafkaChangeEventPublisher.DEFAULT_TOPIC
                : topic.trim();
    }

    @Override
    public void publish(DemoConfigurationChange change) {
        throw new IllegalStateException("DEMO_CHANGE_KAFKA_BOOTSTRAP_SERVERS is not configured");
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String topic() {
        return topic;
    }
}
