package com.banking.userservice.exception;

import java.time.LocalDateTime;

/*
 * The standard error envelope defined in CLAUDE.md.
 * Every error, from every service, looks identical to clients:
 *   { timestamp, status, error, message, path }
 *
 * A record is the right type here: it's immutable, constructed once
 * inside GlobalExceptionHandler, and never mutated.
 */
public record ErrorResponse(
        LocalDateTime timestamp,
        int status,
        String error,
        String message,
        String path
) {}
