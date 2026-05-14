-- Audit events: immutable log of all significant state changes across services.
-- Written only by Kafka consumers — no REST write endpoints exist.
-- Records are never deleted or updated: the audit trail must be tamper-evident.

CREATE TABLE audit_events (
    id           BIGSERIAL PRIMARY KEY,
    event_type   VARCHAR(50)  NOT NULL,           -- TRANSACTION or ACCOUNT
    entity_id    BIGINT       NOT NULL,            -- transactionId or accountId
    user_id      BIGINT,                           -- nullable: account owner
    action       VARCHAR(100) NOT NULL,            -- COMPLETED, FAILED, CREATED, DEPOSITED, etc.
    payload      TEXT         NOT NULL,            -- full JSON snapshot of the original Kafka event
    occurred_at  TIMESTAMP    NOT NULL,            -- business time (from source service)
    received_at  TIMESTAMP    NOT NULL             -- processing time (when audit-service persisted it)
);

-- Indexes for the four common query patterns in AuditController
CREATE INDEX idx_audit_event_type  ON audit_events(event_type);
CREATE INDEX idx_audit_entity_id   ON audit_events(entity_id);
CREATE INDEX idx_audit_user_id     ON audit_events(user_id);
CREATE INDEX idx_audit_occurred_at ON audit_events(occurred_at);
