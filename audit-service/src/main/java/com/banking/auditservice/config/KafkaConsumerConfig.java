package com.banking.auditservice.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * ConsumerFactory — creates Kafka Consumer instances.
     *
     * Key deserializer   = StringDeserializer (entity ID as string)
     * Value deserializer = StringDeserializer — we receive raw JSON strings
     *   and parse them with ObjectMapper in each listener.
     *
     * Why String deserialization instead of typed JsonDeserializer?
     * - Avoids the "magic" of __TypeId__ headers (which require both services
     *   to have identical class names on the classpath).
     * - Makes deserialization explicit and easy to follow in the listener code.
     * - Works cleanly with multiple event types on different topics without
     *   needing separate container factories.
     *
     * Consumer group = "audit-service":
     * - All audit-service instances share this group ID.
     * - Kafka assigns each partition to exactly one instance within the group.
     * - Scaling to N instances → N instances split the partitions, parallelising
     *   consumption without any duplicate processing.
     */
    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "audit-service");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        /*
         * earliest: on first startup (no committed offset yet), read from the
         * beginning of the topic. This ensures audit-service picks up events
         * published before it was running — critical for replay capability.
         */
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Listener container factory — configures how @KafkaListener methods run.
     *
     * AckMode.RECORD: commit the offset after each message is successfully
     * processed. If the listener throws, the offset is NOT committed and the
     * message will be redelivered on restart.
     *
     * Contrast with RabbitMQ: in RabbitMQ we used defaultRequeueRejected=false
     * to route failed messages to DLQ immediately. Kafka's equivalent is a
     * SeekToCurrentErrorHandler or DeadLetterPublishingRecoverer — deferred
     * to a future hardening phase. For now, on failure the consumer logs the
     * error and skips (commits offset) via the default error handler.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }
}
