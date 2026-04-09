-- V1__init.sql — account-service initial schema

CREATE TABLE accounts (
    id             BIGSERIAL     PRIMARY KEY,

    -- Logical FK to user-service. No DB-level foreign key — that would
    -- couple two independent databases. Referential integrity is enforced
    -- in the application layer (Feign call to user-service on account creation).
    user_id        BIGINT        NOT NULL,

    account_number VARCHAR(255)  NOT NULL,

    -- @Enumerated(EnumType.STRING) → VARCHAR(255)
    type           VARCHAR(255)  NOT NULL,

    -- NUMERIC(19,4): exact decimal arithmetic. precision=19 covers values up to
    -- 999 trillion; scale=4 gives four decimal places for sub-cent precision.
    -- Never use FLOAT or DOUBLE for money — they cannot represent most decimal
    -- fractions exactly (0.1 + 0.2 = 0.30000000000000004 in IEEE 754).
    balance        NUMERIC(19,4) NOT NULL,

    status         VARCHAR(255)  NOT NULL,
    created_at     TIMESTAMP     NOT NULL,

    -- @Version field for optimistic locking. Hibernate reads this on every
    -- SELECT and appends WHERE version = ? to every UPDATE. If two concurrent
    -- requests both read version=3, the first UPDATE succeeds (version becomes 4)
    -- and the second UPDATE finds no row — Hibernate throws
    -- ObjectOptimisticLockingFailureException → GlobalExceptionHandler → 409.
    -- Nullable because Hibernate initialises it to 0 on first INSERT.
    version        BIGINT,

    CONSTRAINT uk_accounts_account_number UNIQUE (account_number)
);
