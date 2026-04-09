package com.banking.userservice.dto;

import com.banking.userservice.entity.User;
import com.banking.userservice.entity.UserStatus;

import java.time.LocalDateTime;

public record UserResponse(
        Long id,
        String firstName,
        String lastName,
        String email,
        UserStatus status,
        LocalDateTime createdAt
) {
    /*
     * Static factory keeps entity → DTO mapping co-located with the DTO.
     * The controller and service never touch entity fields directly —
     * they only see this clean response type. This means if the entity
     * changes shape (e.g., a column is renamed), there is exactly one
     * place to update the mapping.
     */
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getStatus(),
                user.getCreatedAt()
        );
    }
}
