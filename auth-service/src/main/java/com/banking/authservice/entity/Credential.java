package com.banking.authservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "credentials")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Credential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /*
     * The login identifier. Unique at DB level — one credential per email.
     * Mirrors the email stored in user-service but is owned by auth-service.
     * If a user changes their email in user-service, auth-service must be
     * notified (future: via an event). For now, email is immutable after registration.
     */
    @Column(nullable = false, unique = true)
    private String email;

    /*
     * BCrypt hash — 60-char output.
     * BCrypt is intentionally slow (configurable cost factor, default 10 rounds).
     * This is a feature: it makes offline brute-force attacks expensive.
     */
    @Column(nullable = false)
    private String passwordHash;

    /*
     * Logical FK to user-service. No DB constraint — cross-service coupling.
     * Populated from the UserResponse returned when user-service creates the user.
     */
    @Column(nullable = false, unique = true)
    private Long userId;

    /*
     * Role stored as STRING. USER is the default on register.
     * ADMIN would be assigned out-of-band (direct DB update or admin endpoint).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        if (role == null) {
            role = Role.USER;
        }
    }
}
