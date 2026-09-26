ALTER TABLE ledger_transactions DROP CONSTRAINT ck_ledger_transactions_type;
ALTER TABLE ledger_transactions ADD CONSTRAINT ck_ledger_transactions_type
    CHECK (type IN ('P2P_TRANSFER', 'CASH_IN', 'PIX_INTERNAL', 'PIX_OUT', 'PIX_OUT_REVERSAL', 'PIX_IN',
                    'CHARGE_PAYMENT'));

CREATE TABLE charges (
    id                     UUID                     PRIMARY KEY,
    merchant_id            BIGINT                   NOT NULL REFERENCES users (id),
    -- Unguessable id used in the public payment link, so charges cannot be enumerated.
    public_token           VARCHAR(32)              NOT NULL UNIQUE,
    txid                   VARCHAR(25)              NOT NULL UNIQUE,
    amount                 BIGINT                   NOT NULL,
    description            VARCHAR(140),
    reference              VARCHAR(64),
    pix_key                VARCHAR(77),
    status                 VARCHAR(9)               NOT NULL,
    expires_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    paid_at                TIMESTAMP WITH TIME ZONE,
    payer_user_id          BIGINT                   REFERENCES users (id),
    payment_method         VARCHAR(6),
    end_to_end_id          VARCHAR(32),
    fee_bps                INT,
    fee_amount             BIGINT,
    net_amount             BIGINT,
    ledger_transaction_id  UUID                     REFERENCES ledger_transactions (id),
    CONSTRAINT ck_charges_amount CHECK (amount > 0),
    CONSTRAINT ck_charges_status CHECK (status IN ('PENDING', 'PAID', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_charges_method CHECK (payment_method IS NULL OR payment_method IN ('WALLET', 'PIX')),
    CONSTRAINT ck_charges_paid CHECK (status <> 'PAID' OR (paid_at IS NOT NULL AND ledger_transaction_id IS NOT NULL))
);

-- The merchant's order reference makes charge creation idempotent.
CREATE UNIQUE INDEX ux_charges_merchant_reference ON charges (merchant_id, reference) WHERE reference IS NOT NULL;
CREATE INDEX idx_charges_merchant_created ON charges (merchant_id, created_at DESC);
CREATE INDEX idx_charges_merchant_paid ON charges (merchant_id, paid_at) WHERE status = 'PAID';
CREATE INDEX idx_charges_pending_expiry ON charges (expires_at) WHERE status = 'PENDING';
