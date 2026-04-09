package com.banking.notificationservice.service;

import com.banking.notificationservice.dto.NotificationResponse;
import com.banking.notificationservice.dto.SendNotificationRequest;
import com.banking.notificationservice.dto.UnreadCountResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface NotificationService {

    NotificationResponse send(SendNotificationRequest request);

    Page<NotificationResponse> getForUser(Long userId, Boolean unreadOnly, Pageable pageable);

    NotificationResponse getById(Long id);

    UnreadCountResponse getUnreadCount(Long userId);

    NotificationResponse markAsRead(Long id);

    void markAllAsRead(Long userId);

    void delete(Long id);
}
