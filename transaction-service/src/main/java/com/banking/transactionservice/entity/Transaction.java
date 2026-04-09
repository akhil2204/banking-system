package com.banking.transactionservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    /*
     * sourceAccountId and targetAccountId are logical references — no FK
     * constraint, same reason as account-service's userId column.
     * Cross-service foreign keys would couple two independent databases.
     *
     * DEBIT:    sourceAccountId = debited account,  targetAccountId = null
     * CREDIT:   sourceAccountId = null,             targetAccountId = credited account
     * TRANSFER: sourceAccountId = from account,     targetAccountId = to account
     */
    @Column
    private Long sourceAccountId;

    @Column
    private Long targetAccountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(length = 255)
    private String description;

    /*
     * failureReason is populated when status = FAILED.
     * It records what went wrong (e.g. "Insufficient funds", "Account suspended",
     * "CRITICAL: compensation failed — manual intervention required").
     * Stored in the DB so ops can query failed transactions and diagnose issues.
     */
    @Column(length = 512)
    private String failureReason;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime completedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) {
            status = TransactionStatus.PENDING;
        }
    }
}
