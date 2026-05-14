package com.banking.accountservice.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

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
