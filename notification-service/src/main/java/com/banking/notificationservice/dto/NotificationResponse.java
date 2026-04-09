package com.banking.notificationservice.dto;

import com.banking.notificationservice.entity.Notification;
import com.banking.notificationservice.entity.NotificationChannel;
import com.banking.notificationservice.entity.NotificationStatus;
import com.banking.notificationservice.entity.NotificationType;

import java.time.LocalDateTime;

public record NotificationResponse(
        Long id,
        Long userId,
        NotificationType type,
        NotificationChannel channel,
        String title,
        String message,
        NotificationStatus status,
        LocalDateTime createdAt,
        LocalDateTime readAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getId(),
                n.getUserId(),
                n.getType(),
                n.getChannel(),
                n.getTitle(),
                n.getMessage(),
                n.getStatus(),
                n.getCreatedAt(),
                n.getReadAt()
        );
    }
}
