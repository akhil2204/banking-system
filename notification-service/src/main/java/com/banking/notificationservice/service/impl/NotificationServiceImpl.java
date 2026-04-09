package com.banking.notificationservice.service.impl;

import com.banking.notificationservice.dto.NotificationResponse;
import com.banking.notificationservice.dto.SendNotificationRequest;
import com.banking.notificationservice.dto.UnreadCountResponse;
import com.banking.notificationservice.entity.Notification;
import com.banking.notificationservice.entity.NotificationStatus;
import com.banking.notificationservice.exception.NotificationNotFoundException;
import com.banking.notificationservice.repository.NotificationRepository;
import com.banking.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;

    @Override
    @Transactional
    public NotificationResponse send(SendNotificationRequest request) {
        Notification notification = Notification.builder()
                .userId(request.userId())
                .type(request.type())
                .channel(request.channel())
                .title(request.title())
                .message(request.message())
                .build();

        return NotificationResponse.from(notificationRepository.save(notification));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getForUser(Long userId, Boolean unreadOnly, Pageable pageable) {
        if (Boolean.TRUE.equals(unreadOnly)) {
            return notificationRepository
                    .findAllByUserIdAndStatus(userId, NotificationStatus.UNREAD, pageable)
                    .map(NotificationResponse::from);
        }
        // Exclude DELETED rows — the user doesn't see soft-deleted notifications
        return notificationRepository
                .findAllByUserIdAndStatusNot(userId, NotificationStatus.DELETED, pageable)
                .map(NotificationResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationResponse getById(Long id) {
        return notificationRepository.findById(id)
                .map(NotificationResponse::from)
                .orElseThrow(() -> new NotificationNotFoundException(id));
    }

    @Override
    @Transactional(readOnly = true)
    public UnreadCountResponse getUnreadCount(Long userId) {
        long count = notificationRepository.countByUserIdAndStatus(userId, NotificationStatus.UNREAD);
        return new UnreadCountResponse(userId, count);
    }

    @Override
    @Transactional
    public NotificationResponse markAsRead(Long id) {
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new NotificationNotFoundException(id));

        if (notification.getStatus() == NotificationStatus.UNREAD) {
            notification.setStatus(NotificationStatus.READ);
            notification.setReadAt(LocalDateTime.now());
            notificationRepository.save(notification);
        }

        return NotificationResponse.from(notification);
    }

    /*
     * Bulk UPDATE — single SQL statement regardless of how many unread
     * notifications the user has. Far more efficient than loading each
     * notification into memory, setting READ, and saving one by one.
     */
    @Override
    @Transactional
    public void markAllAsRead(Long userId) {
        notificationRepository.markAllAsReadForUser(
                userId,
                LocalDateTime.now(),
                NotificationStatus.READ,
                NotificationStatus.UNREAD
        );
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new NotificationNotFoundException(id));

        notification.setStatus(NotificationStatus.DELETED);
        notificationRepository.save(notification);
    }
}
