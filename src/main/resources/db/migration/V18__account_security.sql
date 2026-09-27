ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMP WITH TIME ZONE;
-- Accounts created before email verification existed are trusted as they are.
UPDATE users SET email_verified_at = created_at;

-- One-time 6-digit codes sent by email. Only a keyed hash is stored: a plain hash of 6 digits is trivial to
-- reverse.
CREATE TABLE auth_codes (
    id           UUID                     PRIMARY KEY,
    user_id      BIGINT                   NOT NULL REFERENCES users (id),
    purpose      VARCHAR(20)              NOT NULL,
    code_hash    VARCHAR(64)              NOT NULL,
    attempts     INT                      NOT NULL DEFAULT 0,
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at  TIMESTAMP WITH TIME ZONE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_auth_codes_purpose CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET'))
);

CREATE INDEX idx_auth_codes_active ON auth_codes (user_id, purpose, created_at DESC) WHERE consumed_at IS NULL;
