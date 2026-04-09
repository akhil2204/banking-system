package com.banking.transactionservice.dto;

import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.entity.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionResponse(
        Long id,
        TransactionType type,
        TransactionStatus status,
        Long sourceAccountId,
        Long targetAccountId,
        BigDecimal amount,
        String description,
        String failureReason,
        LocalDateTime createdAt,
        LocalDateTime completedAt
) {
    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(
                t.getId(),
                t.getType(),
                t.getStatus(),
                t.getSourceAccountId(),
                t.getTargetAccountId(),
                t.getAmount(),
                t.getDescription(),
                t.getFailureReason(),
                t.getCreatedAt(),
                t.getCompletedAt()
        );
    }
}
