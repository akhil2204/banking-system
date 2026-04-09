package com.banking.notificationservice.controller;

import com.banking.notificationservice.dto.NotificationResponse;
import com.banking.notificationservice.dto.SendNotificationRequest;
import com.banking.notificationservice.dto.UnreadCountResponse;
import com.banking.notificationservice.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Notifications", description = "User notification management")
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "Send a notification (called by other services)",
               responses = {
                   @ApiResponse(responseCode = "201", description = "Notification created"),
                   @ApiResponse(responseCode = "400", description = "Validation failed")
               })
    @PostMapping
    ResponseEntity<NotificationResponse> send(@RequestBody @Valid SendNotificationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(notificationService.send(request));
    }

    @Operation(summary = "Get notifications for a user",
               description = "?unreadOnly=true returns only UNREAD notifications. Default returns all non-deleted.")
    @GetMapping("/user/{userId}")
    ResponseEntity<Page<NotificationResponse>> getForUser(
            @PathVariable Long userId,
            @RequestParam(required = false) Boolean unreadOnly,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(notificationService.getForUser(userId, unreadOnly, pageable));
    }

    @Operation(summary = "Get unread notification count for a user")
    @GetMapping("/user/{userId}/unread-count")
    ResponseEntity<UnreadCountResponse> getUnreadCount(@PathVariable Long userId) {
        return ResponseEntity.ok(notificationService.getUnreadCount(userId));
    }

    @Operation(summary = "Get notification by ID")
    @GetMapping("/{id}")
    ResponseEntity<NotificationResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(notificationService.getById(id));
    }

    @Operation(summary = "Mark a single notification as read")
    @PutMapping("/{id}/read")
    ResponseEntity<NotificationResponse> markAsRead(@PathVariable Long id) {
        return ResponseEntity.ok(notificationService.markAsRead(id));
    }

    @Operation(summary = "Mark all notifications as read for a user")
    @PutMapping("/user/{userId}/read-all")
    ResponseEntity<Void> markAllAsRead(@PathVariable Long userId) {
        notificationService.markAllAsRead(userId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Delete a notification (soft delete)",
               responses = {
                   @ApiResponse(responseCode = "204", description = "Notification deleted"),
                   @ApiResponse(responseCode = "404", description = "Notification not found")
               })
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable Long id) {
        notificationService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
