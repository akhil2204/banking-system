package com.banking.notificationservice.messaging;

/**
 * Mirror of transaction-service's TransactionEvent.
 *
 * Each service owns its own copy — no shared library, no coupling.
 * If the contract changes, both services update their local copy independently.
 * Jackson deserializes the incoming JSON payload into this record by field name.
 */
public record TransactionEvent(
        Long transactionId,
        Long userId,
        String type,    // TRANSACTION_COMPLETED or TRANSACTION_FAILED
        String channel, // IN_APP
        String title,
        String message
) {}
