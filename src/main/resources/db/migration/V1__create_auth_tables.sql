-- =====================================================================
--  HITECH DZ - Schéma d'authentification
-- =====================================================================

CREATE TABLE users (
    id             UUID                     PRIMARY KEY,
    first_name     VARCHAR(40)              NOT NULL,
    last_name      VARCHAR(40)              NOT NULL,
    email          VARCHAR(254)             NOT NULL,
    phone          VARCHAR(30)              NOT NULL,
    password_hash  VARCHAR(100)             NOT NULL,
    role           VARCHAR(20)              NOT NULL DEFAULT 'CUSTOMER',
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_users_email      UNIQUE (email),
    CONSTRAINT ck_users_email_lower CHECK (email = LOWER(email)),
    CONSTRAINT ck_users_role       CHECK (role IN ('CUSTOMER', 'ADMIN'))
);

-- Refresh tokens : seul le hash SHA-256 est stocké.
-- La colonne "revoked" constitue la liste de révocation.
CREATE TABLE refresh_tokens (
    id           UUID                     PRIMARY KEY,
    user_id      UUID                     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash   VARCHAR(64)              NOT NULL,
    remember_me  BOOLEAN                  NOT NULL DEFAULT FALSE,
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked      BOOLEAN                  NOT NULL DEFAULT FALSE,
    revoked_at   TIMESTAMP WITH TIME ZONE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user    ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens (expires_at);

-- Codes de réinitialisation : un seul code actif par utilisateur, stocké hashé (BCrypt).
CREATE TABLE password_reset_codes (
    id          UUID                     PRIMARY KEY,
    user_id     UUID                     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash   VARCHAR(100)             NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts    INTEGER                  NOT NULL DEFAULT 0,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_password_reset_codes_user UNIQUE (user_id)
);
