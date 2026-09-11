package com.databuff.apm.demo.fault;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Kafka transport for configuration change evidence consumed by BuffOps. */
public final class KafkaChangeEventPublisher implements ChangeEventPublisher {

    public static final String DEFAULT_TOPIC = "buffops.demo.alerts";
    private static final long ACK_TIMEOUT_SECONDS = 12L;

    private final String topic;
    private final Producer<String, String> producer;

    public KafkaChangeEventPublisher(String bootstrapServers, String topic) {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("Kafka bootstrap servers required");
        }
        this.topic = topic == null || topic.isBlank() ? DEFAULT_TOPIC : topic.trim();
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers.trim());
        properties.put(ProducerConfig.CLIENT_ID_CONFIG, "databuff-demo-change-producer");
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
        this.producer = new KafkaProducer<>(properties);
    }

    @Override
    public void publish(DemoConfigurationChange change) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                topic, null, change.occurredAt().toEpochMilli(), change.fingerprint(), change.toKafkaJson());
        record.headers().add(new RecordHeader(
                "content-type", "application/json".getBytes(StandardCharsets.UTF_8)));
        producer.send(record).get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String topic() {
        return topic;
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(5));
    }
}
