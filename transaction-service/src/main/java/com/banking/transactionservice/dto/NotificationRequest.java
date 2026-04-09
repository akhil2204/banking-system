package com.banking.transactionservice.dto;

// Mirror of notification-service's SendNotificationRequest.
// String for type and channel — no enum coupling across service boundaries.
public record NotificationRequest(
        Long userId,
        String type,
        String channel,
        String title,
        String message
) {}
