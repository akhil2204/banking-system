package com.banking.auditservice.messaging;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Local copy of account-service's AccountAuditEvent.
 */
public record AccountAuditEvent(
        Long accountId,
        String accountNumber,
        Long userId,
        String action,
        String previousStatus,
        String newStatus,
        BigDecimal previousBalance,
        BigDecimal newBalance,
        LocalDateTime occurredAt
) {}
