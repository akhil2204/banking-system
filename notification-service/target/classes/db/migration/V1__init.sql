-- V1__init.sql — notification-service initial schema

CREATE TABLE notifications (
    id         BIGSERIAL     PRIMARY KEY,

    -- Logical reference to user-service — no DB-level FK.
    user_id    BIGINT        NOT NULL,

    -- @Enumerated(EnumType.STRING) fields — all VARCHAR(255).
    type       VARCHAR(255)  NOT NULL,
    channel    VARCHAR(255)  NOT NULL,

    title      VARCHAR(255)  NOT NULL,

    -- @Column(length = 1000): notifications can carry detailed messages.
    message    VARCHAR(1000) NOT NULL,

    status     VARCHAR(255)  NOT NULL,
    created_at TIMESTAMP     NOT NULL,

    -- Populated when a notification is read (status → READ).
    read_at    TIMESTAMP
);

-- These indexes mirror the @Index annotations declared on the entity's @Table.
-- With ddl-auto: validate, Hibernate no longer creates indexes automatically —
-- Flyway is the sole owner of the schema. Without these indexes, every
-- "get all notifications for user X" query would do a full table scan.

-- Covers the common case: fetch all notifications for a specific user.
CREATE INDEX idx_notifications_user_id
    ON notifications (user_id);

-- Covers the filtered case: fetch only unread notifications for a user.
-- A composite index on (user_id, status) lets PostgreSQL satisfy both the
-- equality filter on user_id AND the status filter in a single index scan.
CREATE INDEX idx_notifications_user_status
    ON notifications (user_id, status);
