package com.banking.authservice.dto;

import java.time.LocalDateTime;

/*
 * Returned by both /auth/register and /auth/login.
 * The client stores the token and sends it as:
 *   Authorization: Bearer <token>
 * on every subsequent request to the api-gateway.
 */
public record AuthResponse(
        String token,
        String tokenType,       // always "Bearer"
        Long userId,
        String email,
        String role,
        LocalDateTime issuedAt,
        LocalDateTime expiresAt
) {}
