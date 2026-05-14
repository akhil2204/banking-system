package com.banking.transactionservice.messaging;

/**
 * Event published to RabbitMQ after every transaction outcome.
 *
 * Why strings instead of enums for type/channel?
 * Enums would couple transaction-service to notification-service's
 * type definitions — if notification-service renames or removes an enum
 * value, transaction-service breaks too.  Using plain strings keeps the
 * contract loose: notification-service converts them to its own enums
 * locally via valueOf(), and any mismatch routes to the DLQ for inspection.
 */
public record TransactionEvent(
        Long transactionId,
        Long userId,
        String type,    // matches NotificationType name: TRANSACTION_COMPLETED, TRANSACTION_FAILED
        String channel, // matches NotificationChannel name: IN_APP
        String title,
        String message
) {}
