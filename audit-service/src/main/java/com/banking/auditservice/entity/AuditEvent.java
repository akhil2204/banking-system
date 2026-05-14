package com.banking.auditservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "audit_events", indexes = {
        @Index(name = "idx_audit_event_type",  columnList = "event_type"),
        @Index(name = "idx_audit_entity_id",   columnList = "entity_id"),
        @Index(name = "idx_audit_user_id",     columnList = "user_id"),
        @Index(name = "idx_audit_occurred_at", columnList = "occurred_at")
})
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /*
     * eventType: coarse-grained category — TRANSACTION or ACCOUNT.
     * Allows efficient filtering without parsing the payload JSON.
     */
    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    /*
     * entityId: the primary key of the originating entity
     * (transactionId or accountId). Combined with eventType, uniquely
     * identifies what happened to which object.
     */
    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    /*
     * userId: the account owner involved in the event. Nullable because
     * some events (e.g. a failed transaction before account resolution)
     * may not have a userId.
     */
    @Column(name = "user_id")
    private Long userId;

    /*
     * action: fine-grained description of the operation.
     * TRANSACTION: COMPLETED, FAILED
     * ACCOUNT:     CREATED, DEPOSITED, WITHDRAWN, STATUS_CHANGED, CLOSED
     */
    @Column(nullable = false, length = 100)
    private String action;

    /*
     * payload: full JSON of the original Kafka event.
     * TEXT column — no length limit. Storing the raw event means the audit
     * log is self-contained: even if the source service changes its schema,
     * old records still have their complete original payload.
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    /*
     * occurredAt: when the event happened in the source service.
     * Taken from the event payload — represents business time.
     */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /*
     * receivedAt: when audit-service persisted this record.
     * Set by @PrePersist — represents processing time.
     * The gap between occurredAt and receivedAt is the end-to-end latency.
     */
    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @PrePersist
    void onPersist() {
        receivedAt = LocalDateTime.now();
    }
}
