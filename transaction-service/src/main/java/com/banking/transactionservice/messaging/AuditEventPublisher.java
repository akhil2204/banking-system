package com.banking.transactionservice.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Publish an audit event to a Kafka topic.
     *
     * key = entityId as String — routes all events for the same entity to the
     * same partition, ensuring ordered consumption by audit-service.
     *
     * Fire-and-forget: Kafka publish failure must NEVER affect the transaction
     * outcome. The transaction is already committed to the DB at this point.
     * A production system would use the transactional outbox pattern to guarantee
     * at-least-once delivery, but that is beyond this phase's scope.
     */
    public void publish(String topic, String key, Object event) {
        try {
            kafkaTemplate.send(topic, key, event);
            log.debug("Published audit event: topic={} key={}", topic, key);
        } catch (Exception e) {
            log.warn("Failed to publish audit event (non-critical): topic={} key={} error={}",
                    topic, key, e.getMessage());
        }
    }
}
