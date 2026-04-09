package com.banking.notificationservice.entity;

public enum NotificationStatus {
    UNREAD,
    READ,
    DELETED  // soft delete — consistent with system-wide "never hard delete" rule
}
