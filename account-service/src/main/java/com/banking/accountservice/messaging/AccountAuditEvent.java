package com.banking.accountservice.messaging;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Kafka event published after every account state change.
 * Consumed by audit-service for immutable audit logging.
 *
 * action values: CREATED, DEPOSITED, WITHDRAWN, STATUS_CHANGED, CLOSED
 *
 * previousBalance / newBalance: null for non-balance operations (STATUS_CHANGED).
 * previousStatus / newStatus:   null for balance operations (DEPOSITED, WITHDRAWN).
 *
 * Key = accountId (String): all events for the same account land in the same
 * partition, preserving per-account ordering for audit-service consumers.
 */
public record AccountAuditEvent(
        Long accountId,
        String accountNumber,
        Long userId,
        String action,
        String previousStatus,    // null for DEPOSITED / WITHDRAWN / CREATED
        String newStatus,         // null for DEPOSITED / WITHDRAWN
        BigDecimal previousBalance, // null for STATUS_CHANGED
        BigDecimal newBalance,      // null for STATUS_CHANGED / CLOSED
        LocalDateTime occurredAt
) {}
