package com.banking.authservice.dto;

/*
 * Mirror DTO for the Feign call to user-service POST /users.
 * Matches user-service's CreateUserRequest shape (firstName, lastName, email).
 * We do NOT import user-service's DTO class directly — that would create a
 * compile-time coupling between two independent services. Each service owns
 * its own copy of cross-service DTO mirrors.
 */
public record CreateUserRequest(
        String firstName,
        String lastName,
        String email
) {}
