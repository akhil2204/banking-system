package com.banking.transactionservice.messaging;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Kafka event published after every transaction reaches a terminal state
 * (COMPLETED or FAILED). Consumed by audit-service for immutable audit logging.
 *
 * Why Kafka instead of RabbitMQ for audit?
 * Kafka retains every message for a configurable period (default 7 days).
 * audit-service can replay the entire topic from offset 0 to rebuild its
 * audit table — invaluable after a data loss event. RabbitMQ deletes
 * messages once consumed; replay is not possible.
 *
 * Key = transactionId (String): ensures all events for the same transaction
 * land in the same partition, preserving ordering per transaction.
 */
public record TransactionAuditEvent(
        Long transactionId,
        Long sourceAccountId,   // null for CREDIT
        Long targetAccountId,   // null for DEBIT
        Long userId,            // owner of the source account (or target for CREDIT)
        String type,            // DEBIT, CREDIT, TRANSFER
        BigDecimal amount,
        String status,          // COMPLETED or FAILED
        String description,
        String failureReason,   // null when status = COMPLETED
        LocalDateTime occurredAt
) {}
