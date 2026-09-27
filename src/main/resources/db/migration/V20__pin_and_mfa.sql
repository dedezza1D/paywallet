-- Transaction PIN required to move money out, separate from the login password.
ALTER TABLE users ADD COLUMN transaction_pin_hash VARCHAR(100);
ALTER TABLE users ADD COLUMN pin_changed_at TIMESTAMP WITH TIME ZONE;

-- TOTP second factor. The secret is encrypted like other sensitive fields; it only counts once confirmed.
ALTER TABLE users ADD COLUMN totp_secret VARCHAR(255);
ALTER TABLE users ADD COLUMN totp_enabled_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE mfa_recovery_codes (
    id          UUID                     PRIMARY KEY,
    user_id     BIGINT                   NOT NULL REFERENCES users (id),
    code_hash   VARCHAR(64)              NOT NULL,
    used_at     TIMESTAMP WITH TIME ZONE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_mfa_recovery_codes_user ON mfa_recovery_codes (user_id) WHERE used_at IS NULL;

-- Devices that have signed in, so the first sign-in from a new one can be reported to the customer.
CREATE TABLE known_devices (
    user_id      BIGINT                   NOT NULL REFERENCES users (id),
    device_hash  VARCHAR(64)              NOT NULL,
    user_agent   VARCHAR(255),
    last_ip      VARCHAR(45),
    first_seen   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen    TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, device_hash)
);
