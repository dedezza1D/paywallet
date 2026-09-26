ALTER TABLE accounts DROP CONSTRAINT ck_accounts_type;
ALTER TABLE accounts ADD CONSTRAINT ck_accounts_type
    CHECK (type IN ('USER_WALLET', 'SYSTEM_CASH_IN', 'SYSTEM_FEES', 'SYSTEM_PIX_SETTLEMENT',
                    'SYSTEM_BILL_SETTLEMENT', 'SYSTEM_YIELD', 'SYSTEM_LOAN_PRINCIPAL', 'SYSTEM_INTEREST_INCOME',
                    'SYSTEM_TAX_PAYABLE', 'SYSTEM_CARD_HOLDS', 'SYSTEM_CARD_SETTLEMENT', 'SYSTEM_CARD_RECEIVABLES',
                    'SYSTEM_MARKETPLACE_SETTLEMENT', 'SYSTEM_CASHBACK', 'SYSTEM_PIX_MED_HOLDS'));

ALTER TABLE ledger_transactions DROP CONSTRAINT ck_ledger_transactions_type;
ALTER TABLE ledger_transactions ADD CONSTRAINT ck_ledger_transactions_type
    CHECK (type IN ('P2P_TRANSFER', 'CASH_IN', 'PIX_INTERNAL', 'PIX_OUT', 'PIX_OUT_REVERSAL', 'PIX_IN',
                    'CHARGE_PAYMENT', 'BILL_PAYMENT', 'BILL_PAYMENT_REVERSAL', 'YIELD_CREDIT',
                    'LOAN_DISBURSEMENT', 'LOAN_INSTALLMENT_PAYMENT', 'CARD_HOLD', 'CARD_HOLD_RELEASE',
                    'CARD_DEBIT_CLEARING', 'CARD_CREDIT_CLEARING', 'CARD_REVOLVING_INTEREST',
                    'CARD_STATEMENT_PAYMENT', 'MARKETPLACE_PURCHASE', 'MARKETPLACE_REFUND',
                    'MARKETPLACE_COMMISSION', 'CASHBACK_CREDIT', 'PIX_RETURN', 'PIX_RETURN_REVERSAL',
                    'PIX_MED_BLOCK', 'PIX_MED_RELEASE', 'PIX_MED_RETURN', 'CHARGE_REFUND', 'CARD_REFUND',
                    'CARD_CHARGEBACK'));

-- Money frozen in a receiver's wallet while a Pix fraud claim (MED) is analyzed.
INSERT INTO accounts (id, owner_id, type, balance, allow_negative, created_at, updated_at) VALUES
    ('00000000-0000-0000-0000-000000000014', NULL, 'SYSTEM_PIX_MED_HOLDS', 0, FALSE, now(), now());

-- A Pix sent back by its receiver, total or partial. Returns of Pix from other institutions settle through the PSP.
CREATE TABLE pix_returns (
    id                       UUID                     PRIMARY KEY,
    return_id                VARCHAR(32)              NOT NULL UNIQUE,
    original_end_to_end_id   VARCHAR(32)              NOT NULL REFERENCES pix_payments (end_to_end_id),
    requested_by             BIGINT                   NOT NULL REFERENCES users (id),
    amount                   BIGINT                   NOT NULL,
    reason                   VARCHAR(4)               NOT NULL,
    scope                    VARCHAR(8)               NOT NULL,
    status                   VARCHAR(9)               NOT NULL,
    idempotency_key          VARCHAR(200)             NOT NULL UNIQUE,
    ledger_transaction_id    UUID                     NOT NULL REFERENCES ledger_transactions (id),
    reversal_transaction_id  UUID                     REFERENCES ledger_transactions (id),
    failure_reason           VARCHAR(255),
    attempts                 INT                      NOT NULL DEFAULT 0,
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    settled_at               TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_pix_returns_amount CHECK (amount > 0),
    -- BE08 bank error, FR01 fraud, MD06 customer request, SL02 institution specific.
    CONSTRAINT ck_pix_returns_reason CHECK (reason IN ('BE08', 'FR01', 'MD06', 'SL02')),
    CONSTRAINT ck_pix_returns_scope CHECK (scope IN ('INTERNAL', 'EXTERNAL')),
    CONSTRAINT ck_pix_returns_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_pix_returns_original ON pix_returns (original_end_to_end_id);
CREATE INDEX idx_pix_returns_pending ON pix_returns (created_at) WHERE status = 'PENDING';

-- Special Return Mechanism (MED): a payer reports a Pix as fraud and the receiver's balance is frozen until analyzed.
CREATE TABLE pix_fraud_claims (
    id                     UUID                     PRIMARY KEY,
    end_to_end_id          VARCHAR(32)              NOT NULL UNIQUE REFERENCES pix_payments (end_to_end_id),
    claimant_user_id       BIGINT                   NOT NULL REFERENCES users (id),
    receiver_user_id       BIGINT                   NOT NULL REFERENCES users (id),
    description            VARCHAR(500)             NOT NULL,
    blocked_amount         BIGINT                   NOT NULL,
    returned_amount        BIGINT,
    status                 VARCHAR(8)               NOT NULL,
    resolved_by            BIGINT                   REFERENCES users (id),
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at            TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_pix_claims_status CHECK (status IN ('OPEN', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_pix_claims_amounts CHECK (blocked_amount >= 0)
);

CREATE INDEX idx_pix_claims_open ON pix_fraud_claims (created_at) WHERE status = 'OPEN';

-- Refunds of charges paid with wallet balance; charges paid by Pix are refunded with a Pix return.
ALTER TABLE charges ADD COLUMN refunded_amount BIGINT NOT NULL DEFAULT 0;
ALTER TABLE charges ADD CONSTRAINT ck_charges_refunded CHECK (refunded_amount >= 0 AND refunded_amount <= amount);

CREATE TABLE charge_refunds (
    id                     UUID                     PRIMARY KEY,
    charge_id              UUID                     NOT NULL REFERENCES charges (id),
    amount                 BIGINT                   NOT NULL,
    idempotency_key        VARCHAR(200)             NOT NULL UNIQUE,
    ledger_transaction_id  UUID                     NOT NULL REFERENCES ledger_transactions (id),
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_charge_refunds_amount CHECK (amount > 0)
);

CREATE INDEX idx_charge_refunds_charge ON charge_refunds (charge_id);

-- Card refunds after clearing (by the merchant) and chargebacks won in a dispute. Credit card refunds become
-- negative charges, which lower the next statement.
ALTER TABLE card_charges DROP CONSTRAINT ck_card_charges_amount;
ALTER TABLE card_charges ADD CONSTRAINT ck_card_charges_amount CHECK (amount <> 0);

CREATE TABLE card_refunds (
    id                 VARCHAR(64)              PRIMARY KEY,
    authorization_id   VARCHAR(64)              NOT NULL REFERENCES card_authorizations (id),
    kind               VARCHAR(10)              NOT NULL,
    amount             BIGINT                   NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_card_refunds_kind CHECK (kind IN ('REFUND', 'CHARGEBACK')),
    CONSTRAINT ck_card_refunds_amount CHECK (amount > 0)
);

CREATE INDEX idx_card_refunds_authorization ON card_refunds (authorization_id);

CREATE TABLE card_disputes (
    id                 UUID                     PRIMARY KEY,
    authorization_id   VARCHAR(64)              NOT NULL UNIQUE REFERENCES card_authorizations (id),
    reason             VARCHAR(20)              NOT NULL,
    description        VARCHAR(500),
    amount             BIGINT                   NOT NULL,
    status             VARCHAR(4)               NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_card_disputes_reason CHECK (reason IN ('NOT_RECOGNIZED', 'NOT_RECEIVED', 'DUPLICATE',
                                                          'WRONG_AMOUNT', 'CANCELLED')),
    CONSTRAINT ck_card_disputes_status CHECK (status IN ('OPEN', 'WON', 'LOST')),
    CONSTRAINT ck_card_disputes_amount CHECK (amount > 0)
);
