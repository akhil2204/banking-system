package com.banking.transactionservice.dto;

/*
 * Local mirror of account-service's AccountResponse.
 * userId added so transaction-service can tell notification-service
 * which user to notify after a debit/credit/transfer.
 * status is String — no enum coupling across service boundaries.
 */
public record AccountResponse(
        Long id,
        Long userId,
        String accountNumber,
        String status
) {}
