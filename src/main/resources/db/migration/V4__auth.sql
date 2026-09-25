-- ADMIN is granted only through the database, never through the API.
ALTER TABLE users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER';
ALTER TABLE users ADD CONSTRAINT ck_users_role CHECK (role IN ('CUSTOMER', 'ADMIN'));

-- Only the SHA-256 of each refresh token is stored, so a database leak yields no usable tokens.
CREATE TABLE refresh_tokens (
    id           UUID                     PRIMARY KEY,
    user_id      BIGINT                   NOT NULL REFERENCES users (id),
    family_id    UUID                     NOT NULL,
    token_hash   VARCHAR(64)              NOT NULL UNIQUE,
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at      TIMESTAMP WITH TIME ZONE,
    revoked_at   TIMESTAMP WITH TIME ZONE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
