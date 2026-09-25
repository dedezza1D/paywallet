ALTER TABLE accounts DROP CONSTRAINT ck_accounts_type;
ALTER TABLE accounts ADD CONSTRAINT ck_accounts_type
    CHECK (type IN ('USER_WALLET', 'SYSTEM_CASH_IN', 'SYSTEM_FEES', 'SYSTEM_PIX_SETTLEMENT'));

ALTER TABLE ledger_transactions DROP CONSTRAINT ck_ledger_transactions_type;
ALTER TABLE ledger_transactions ADD CONSTRAINT ck_ledger_transactions_type
    CHECK (type IN ('P2P_TRANSFER', 'CASH_IN', 'PIX_INTERNAL', 'PIX_OUT', 'PIX_OUT_REVERSAL', 'PIX_IN'));

-- Mirrors the platform's settlement account at the central bank: Pix sent to other institutions is
-- credited here, Pix received from them is debited here.
INSERT INTO accounts (id, owner_id, type, balance, allow_negative, created_at, updated_at) VALUES
    ('00000000-0000-0000-0000-000000000003', NULL, 'SYSTEM_PIX_SETTLEMENT', 0, TRUE, now(), now());

CREATE TABLE pix_keys (
    id          UUID                     PRIMARY KEY,
    user_id     BIGINT                   NOT NULL REFERENCES users (id),
    key_type    VARCHAR(5)               NOT NULL,
    key_value   VARCHAR(77)              NOT NULL UNIQUE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_pix_keys_type CHECK (key_type IN ('CPF', 'CNPJ', 'EMAIL', 'PHONE', 'EVP'))
);

CREATE INDEX idx_pix_keys_user ON pix_keys (user_id);

CREATE TABLE pix_payments (
    id                       UUID                     PRIMARY KEY,
    end_to_end_id            VARCHAR(32)              NOT NULL UNIQUE,
    direction                VARCHAR(3)               NOT NULL,
    scope                    VARCHAR(8)               NOT NULL,
    status                   VARCHAR(9)               NOT NULL,
    payer_user_id            BIGINT                   REFERENCES users (id),
    payee_user_id            BIGINT                   REFERENCES users (id),
    key_value                VARCHAR(77),
    counterparty_name        VARCHAR(140),
    counterparty_document    VARCHAR(20),
    counterparty_ispb        VARCHAR(8),
    amount                   BIGINT                   NOT NULL,
    description              VARCHAR(140),
    idempotency_key          VARCHAR(200)             UNIQUE,
    ledger_transaction_id    UUID                     NOT NULL REFERENCES ledger_transactions (id),
    reversal_transaction_id  UUID                     REFERENCES ledger_transactions (id),
    failure_reason           VARCHAR(255),
    attempts                 INT                      NOT NULL DEFAULT 0,
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    settled_at               TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_pix_payments_direction CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT ck_pix_payments_scope CHECK (scope IN ('INTERNAL', 'EXTERNAL')),
    CONSTRAINT ck_pix_payments_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_pix_payments_amount CHECK (amount > 0)
);

CREATE INDEX idx_pix_payments_pending ON pix_payments (created_at) WHERE status = 'PENDING';
CREATE INDEX idx_pix_payments_payer ON pix_payments (payer_user_id, created_at DESC);
CREATE INDEX idx_pix_payments_payee ON pix_payments (payee_user_id, created_at DESC);
