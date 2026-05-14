package com.banking.authservice.dto;

import java.time.LocalDateTime;

/*
 * Mirror of user-service's UserResponse.
 * Only the fields auth-service actually needs are included (userId, status).
 * Extra fields from user-service are silently ignored by Jackson.
 */
public record UserResponse(
        Long id,
        String firstName,
        String lastName,
        String email,
        String status,
        LocalDateTime createdAt
) {}
