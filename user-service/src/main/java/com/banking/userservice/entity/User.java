package com.banking.userservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/*
 * Why Lombok on the entity but records for DTOs?
 *
 * JPA requires a no-arg constructor and mutable fields — records provide
 * neither. Lombok @Builder/@NoArgsConstructor/@AllArgsConstructor generates
 * exactly what JPA needs without boilerplate. DTOs, on the other hand, are
 * pure immutable data carriers, which is exactly what records are designed for.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /*
     * Why IDENTITY over SEQUENCE?
     * PostgreSQL's SERIAL / IDENTITY columns are the idiomatic choice.
     * SEQUENCE requires an extra round-trip to fetch the next value before
     * insert. IDENTITY delegates to the DB column default — simpler and faster
     * for single-node services. We'd reconsider for distributed ID generation.
     */

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false, unique = true)
    private String email;

    /*
     * EnumType.STRING persists "ACTIVE", "SUSPENDED", "DELETED" as strings.
     * EnumType.ORDINAL persists 0, 1, 2 — safe until someone inserts a new
     * value in the middle of the enum declaration, at which point every row
     * silently gets the wrong status. Always use STRING.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    /*
     * updatable = false: once written, the column is never touched again.
     * This is enforced at the Hibernate SQL level, not just by convention.
     */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) {
            status = UserStatus.ACTIVE;
        }
    }
}
