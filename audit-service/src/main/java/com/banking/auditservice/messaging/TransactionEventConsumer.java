package com.banking.auditservice.messaging;

import com.banking.auditservice.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionEventConsumer {

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /**
     * Consume transaction audit events from Kafka and persist immutable records.
     *
     * Contrast with notification-service's RabbitMQ consumer:
     * - RabbitMQ: message is deleted once consumed. No replay possible.
     * - Kafka:    message is retained (default 7 days). audit-service can be
     *   restarted with offset reset to "earliest" to rebuild the entire audit
     *   table from scratch — this is Kafka's superpower for audit use cases.
     *
     * The message payload is a raw JSON String. ObjectMapper.readValue() maps
     * it to TransactionAuditEvent by field name — no class coupling to the producer.
     *
     * Idempotency note: if the consumer crashes after DB write but before Kafka
     * commit, the message is redelivered and a duplicate row is written.
     * A production system would deduplicate by transactionId, but that is
     * beyond this phase's scope.
     */
    @KafkaListener(topics = KafkaTopics.AUDIT_TRANSACTIONS, groupId = "audit-service")
    public void consume(String payload) {
        try {
            TransactionAuditEvent event = objectMapper.readValue(payload, TransactionAuditEvent.class);
            log.info("Received transaction audit event: txn={} type={} status={}",
                    event.transactionId(), event.type(), event.status());

            auditService.save(
                    "TRANSACTION",
                    event.transactionId(),
                    event.userId(),
                    event.status(),   // COMPLETED or FAILED
                    payload,          // store raw JSON — complete, immutable snapshot
                    event.occurredAt()
            );
        } catch (Exception e) {
            log.error("Failed to process transaction audit event: payload={} error={}", payload, e.getMessage());
            // Offset is committed (AckMode.RECORD default behavior on exception in RECORD mode
            // depends on error handler — here we log and move on to avoid blocking the partition).
        }
    }
}
