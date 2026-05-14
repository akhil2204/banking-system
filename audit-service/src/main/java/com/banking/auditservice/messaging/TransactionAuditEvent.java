package com.banking.auditservice.messaging;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Local copy of transaction-service's TransactionAuditEvent.
 * Each service owns its own copy — Jackson deserializes by field name, not class identity.
 */
public record TransactionAuditEvent(
        Long transactionId,
        Long sourceAccountId,
        Long targetAccountId,
        Long userId,
        String type,
        BigDecimal amount,
        String status,
        String description,
        String failureReason,
        LocalDateTime occurredAt
) {}
