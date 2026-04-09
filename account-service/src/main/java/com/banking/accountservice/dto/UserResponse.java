package com.banking.accountservice.dto;

/*
 * Local mirror of user-service's UserResponse — used only for deserializing
 * the Feign response. We only declare the fields we actually use.
 *
 * Feign + Jackson silently ignore fields present in the JSON but absent here,
 * so this stays lean. status is String (not UserStatus enum) to avoid importing
 * an enum defined in another service's codebase — loose coupling between services.
 */
public record UserResponse(
        Long id,
        String firstName,
        String lastName,
        String email,
        String status
) {}
