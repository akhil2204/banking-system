-- V1__init.sql — transaction-service initial schema

CREATE TABLE transactions (
    id   BIGSERIAL    PRIMARY KEY,

    -- @Enumerated(EnumType.STRING) → VARCHAR(255)
    type             VARCHAR(255)  NOT NULL,
    status           VARCHAR(255)  NOT NULL,

    -- Both columns are nullable:
    --   DEBIT:    source_account_id = debited account, target_account_id = NULL
    --   CREDIT:   source_account_id = NULL,            target_account_id = credited account
    --   TRANSFER: both populated
    -- No FK constraints — cross-service DB references are forbidden.
    source_account_id BIGINT,
    target_account_id BIGINT,

    amount            NUMERIC(19,4) NOT NULL,

    -- @Column(length = 255) → VARCHAR(255)
    description       VARCHAR(255),

    -- @Column(length = 512): failure details written when status = FAILED.
    -- Sized at 512 to hold detailed Feign error messages and compensation notes.
    failure_reason    VARCHAR(512),

    created_at        TIMESTAMP    NOT NULL,

    -- Populated only when status transitions to COMPLETED.
    -- NULL for PENDING and FAILED transactions.
    completed_at      TIMESTAMP
);
