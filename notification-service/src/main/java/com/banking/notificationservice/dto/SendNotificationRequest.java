package com.banking.notificationservice.dto;

import com.banking.notificationservice.entity.NotificationChannel;
import com.banking.notificationservice.entity.NotificationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SendNotificationRequest(

        @NotNull(message = "User ID is required")
        Long userId,

        @NotNull(message = "Notification type is required")
        NotificationType type,

        @NotNull(message = "Channel is required")
        NotificationChannel channel,

        @NotBlank(message = "Title is required")
        String title,

        @NotBlank(message = "Message is required")
        String message
) {}
