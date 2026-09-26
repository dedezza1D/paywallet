ALTER TABLE accounts DROP CONSTRAINT ck_accounts_type;
ALTER TABLE accounts ADD CONSTRAINT ck_accounts_type
    CHECK (type IN ('USER_WALLET', 'SYSTEM_CASH_IN', 'SYSTEM_FEES', 'SYSTEM_PIX_SETTLEMENT',
                    'SYSTEM_BILL_SETTLEMENT'));

ALTER TABLE ledger_transactions DROP CONSTRAINT ck_ledger_transactions_type;
ALTER TABLE ledger_transactions ADD CONSTRAINT ck_ledger_transactions_type
    CHECK (type IN ('P2P_TRANSFER', 'CASH_IN', 'PIX_INTERNAL', 'PIX_OUT', 'PIX_OUT_REVERSAL', 'PIX_IN',
                    'CHARGE_PAYMENT', 'BILL_PAYMENT', 'BILL_PAYMENT_REVERSAL'));

-- Holds bill payments owed to the clearing system until they are settled with the beneficiary's bank.
INSERT INTO accounts (id, owner_id, type, balance, allow_negative, created_at, updated_at) VALUES
    ('00000000-0000-0000-0000-000000000004', NULL, 'SYSTEM_BILL_SETTLEMENT', 0, TRUE, now(), now());

CREATE TABLE bill_payments (
    id                       UUID                     PRIMARY KEY,
    payer_user_id            BIGINT                   NOT NULL REFERENCES users (id),
    barcode                  VARCHAR(44)              NOT NULL,
    digitable_line           VARCHAR(48)              NOT NULL,
    kind                     VARCHAR(7)               NOT NULL,
    bank_code                VARCHAR(3),
    beneficiary_name         VARCHAR(140),
    beneficiary_document     VARCHAR(20),
    due_date                 DATE,
    nominal_amount           BIGINT,
    amount                   BIGINT                   NOT NULL,
    status                   VARCHAR(9)               NOT NULL,
    idempotency_key          VARCHAR(200)             NOT NULL UNIQUE,
    ledger_transaction_id    UUID                     NOT NULL REFERENCES ledger_transactions (id),
    reversal_transaction_id  UUID                     REFERENCES ledger_transactions (id),
    authentication_code      VARCHAR(64),
    failure_reason           VARCHAR(255),
    attempts                 INT                      NOT NULL DEFAULT 0,
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    settled_at               TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_bill_payments_kind CHECK (kind IN ('BANK', 'UTILITY')),
    CONSTRAINT ck_bill_payments_status CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED')),
    CONSTRAINT ck_bill_payments_amount CHECK (amount > 0)
);

-- A bill can be paid only once: at most one payment per barcode that is in flight or confirmed.
CREATE UNIQUE INDEX ux_bill_payments_active_barcode ON bill_payments (barcode) WHERE status IN ('PENDING', 'CONFIRMED');
CREATE INDEX idx_bill_payments_pending ON bill_payments (created_at) WHERE status = 'PENDING';
CREATE INDEX idx_bill_payments_payer ON bill_payments (payer_user_id, created_at DESC);
