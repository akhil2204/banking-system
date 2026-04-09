package com.banking.accountservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /*
     * userId is a logical reference to user-service — NOT a foreign key.
     * There is no FK constraint across service databases. Referential integrity
     * is enforced at the application layer (Feign call to user-service on creation).
     * A DB-level FK would couple account-service's DB to user-service's DB,
     * violating the "each service owns its own schema" rule.
     */
    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    /*
     * BigDecimal for money — never double or float.
     * Floating-point types cannot represent most decimal fractions exactly.
     * 0.1 + 0.2 == 0.30000000000000004 in double arithmetic.
     * In a financial system this error compounds across millions of transactions.
     *
     * precision = 19: up to 999 trillion units (covers any realistic balance)
     * scale = 4: four decimal places (supports micro-currencies and interest)
     * Maps to NUMERIC(19,4) in PostgreSQL — exact, no rounding.
     */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /*
     * @Version enables optimistic locking. Hibernate adds a WHERE version = ?
     * clause to every UPDATE. If two transactions read the same row (both see
     * version = 3), the first UPDATE succeeds (version becomes 4), and the
     * second UPDATE finds no matching row (version 3 is gone) and throws
     * ObjectOptimisticLockingFailureException. The GlobalExceptionHandler
     * converts this to 409 Conflict so the client can retry.
     *
     * This prevents the "lost update" problem on concurrent balance changes
     * without the performance cost of pessimistic (SELECT FOR UPDATE) locking.
     */
    @Version
    private Long version;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) {
            status = AccountStatus.ACTIVE;
        }
        if (balance == null) {
            balance = BigDecimal.ZERO;
        }
    }
}
