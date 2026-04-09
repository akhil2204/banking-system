package com.banking.notificationservice.repository;

import com.banking.notificationservice.entity.Notification;
import com.banking.notificationservice.entity.NotificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // All non-deleted notifications for a user (regardless of read/unread)
    Page<Notification> findAllByUserIdAndStatusNot(Long userId, NotificationStatus excluded, Pageable pageable);

    // Filtered by status (e.g. UNREAD only)
    Page<Notification> findAllByUserIdAndStatus(Long userId, NotificationStatus status, Pageable pageable);

    // Count UNREAD for a user — used by the badge counter endpoint
    long countByUserIdAndStatus(Long userId, NotificationStatus status);

    /*
     * Bulk UPDATE instead of loading all records and saving individually.
     * For a user with 500 unread notifications, this is one SQL statement
     * vs 500 SELECT + 500 UPDATE round trips.
     *
     * clearAutomatically = true: evicts the first-level (EntityManager) cache
     * after the query runs, so subsequent reads in the same session reflect
     * the new status instead of stale cached values.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Notification n SET n.status = :readStatus, n.readAt = :readAt " +
           "WHERE n.userId = :userId AND n.status = :unreadStatus")
    int markAllAsReadForUser(
            @Param("userId") Long userId,
            @Param("readAt") LocalDateTime readAt,
            @Param("readStatus") NotificationStatus readStatus,
            @Param("unreadStatus") NotificationStatus unreadStatus
    );
}
