-- V1__init.sql — auth-service initial schema
--
-- Stores hashed credentials separately from user profile data (user-service).
-- This is the "separation of concerns" principle applied at the data layer:
--   - user-service owns: name, email, status (who the user IS)
--   - auth-service owns: password hash, role (how the user authenticates)
--
-- Why separate? If auth-service is compromised, the attacker gets hashed passwords
-- but not the full user profile. If user-service is compromised, no credentials leak.

CREATE TABLE credentials (
    id            BIGSERIAL    PRIMARY KEY,

    -- Email is the login identifier. Unique constraint enforces one credential
    -- record per email — prevents registering the same email twice.
    email         VARCHAR(255) NOT NULL,

    -- BCrypt hash of the user's password.
    -- BCrypt output is always 60 characters: $2a$10$<22-char-salt><31-char-hash>
    -- We store VARCHAR(255) with room to spare in case of algorithm migration.
    -- NEVER store plaintext passwords. NEVER use MD5 or SHA-1 for passwords.
    password_hash VARCHAR(255) NOT NULL,

    -- Logical reference to user-service. No DB-level FK — cross-DB constraints
    -- would couple two independent schemas. The FK is enforced at the application
    -- layer: register() first creates the user in user-service, then stores
    -- the returned userId here.
    user_id       BIGINT       NOT NULL,

    -- @Enumerated(EnumType.STRING) → VARCHAR(255)
    -- Values: USER, ADMIN
    role          VARCHAR(255) NOT NULL,

    created_at    TIMESTAMP    NOT NULL,

    CONSTRAINT uk_credentials_email   UNIQUE (email),
    CONSTRAINT uk_credentials_user_id UNIQUE (user_id)
);
