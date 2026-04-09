-- V1__init.sql — user-service initial schema
--
-- Column types must match exactly what Hibernate 6 maps each Java type to,
-- because ddl-auto: validate will reject any mismatch at startup.
--
-- Naming: Spring Boot's default naming strategy converts camelCase fields
-- to snake_case columns (firstName → first_name, createdAt → created_at).
--
-- Why BIGSERIAL? PostgreSQL's BIGSERIAL is syntactic sugar for
-- BIGINT DEFAULT nextval('seq') — the same underlying mechanism as
-- IDENTITY columns. Both are valid; BIGSERIAL is idiomatic for older PG
-- scripts and equally supported by Hibernate IDENTITY generation.

CREATE TABLE users (
    id         BIGSERIAL    PRIMARY KEY,

    -- VARCHAR(255): Hibernate's default length for String fields with no
    -- explicit @Column(length = ...) annotation.
    first_name VARCHAR(255) NOT NULL,
    last_name  VARCHAR(255) NOT NULL,

    -- UNIQUE enforced at DB level. Hibernate also generates a DDL constraint
    -- from @Column(unique = true) but validate mode does not check constraints.
    email      VARCHAR(255) NOT NULL,

    -- @Enumerated(EnumType.STRING) stores the enum name as text.
    -- No length specified on the entity → VARCHAR(255).
    status     VARCHAR(255) NOT NULL,

    -- @Column(updatable = false): Hibernate never issues an UPDATE for this
    -- column. The DB column itself is a normal TIMESTAMP — the updatable=false
    -- constraint is enforced at the ORM layer, not the DB level.
    created_at TIMESTAMP    NOT NULL,

    CONSTRAINT uk_users_email UNIQUE (email)
);
