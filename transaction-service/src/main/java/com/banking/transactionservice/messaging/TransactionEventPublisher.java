package com.banking.transactionservice.messaging;

import com.banking.transactionservice.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    /**
     * Publish a transaction outcome event to the topic exchange.
     *
     * Fire-and-forget: a publish failure must NEVER affect the transaction
     * outcome — the transaction is already persisted and the balance already
     * updated.  All exceptions are caught and logged here.
     *
     * Why publish instead of Feign call?
     * With Feign, notification-service being slow or down adds latency to
     * the transaction response and may exhaust the circuit breaker.
     * With RabbitMQ, the event is handed off to the broker in milliseconds
     * and notification-service processes it in its own time, independently.
     */
    public void publish(TransactionEvent event, String routingKey) {
        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, routingKey, event);
            log.debug("Published event: routingKey={} txn={} userId={}",
                    routingKey, event.transactionId(), event.userId());
        } catch (Exception e) {
            log.warn("Failed to publish transaction event (non-critical): txn={} reason={}",
                    event.transactionId(), e.getMessage());
        }
    }
}
