ALTER TABLE accounts DROP CONSTRAINT ck_accounts_type;
ALTER TABLE accounts ADD CONSTRAINT ck_accounts_type
    CHECK (type IN ('USER_WALLET', 'SYSTEM_CASH_IN', 'SYSTEM_FEES', 'SYSTEM_PIX_SETTLEMENT',
                    'SYSTEM_BILL_SETTLEMENT', 'SYSTEM_YIELD', 'SYSTEM_LOAN_PRINCIPAL', 'SYSTEM_INTEREST_INCOME',
                    'SYSTEM_TAX_PAYABLE'));

ALTER TABLE ledger_transactions DROP CONSTRAINT ck_ledger_transactions_type;
ALTER TABLE ledger_transactions ADD CONSTRAINT ck_ledger_transactions_type
    CHECK (type IN ('P2P_TRANSFER', 'CASH_IN', 'PIX_INTERNAL', 'PIX_OUT', 'PIX_OUT_REVERSAL', 'PIX_IN',
                    'CHARGE_PAYMENT', 'BILL_PAYMENT', 'BILL_PAYMENT_REVERSAL', 'YIELD_CREDIT',
                    'LOAN_DISBURSEMENT', 'LOAN_INSTALLMENT_PAYMENT'));

-- LOAN_PRINCIPAL goes negative by the principal customers owe; INTEREST_INCOME accumulates interest and late
-- charges earned; TAX_PAYABLE holds the IOF withheld until it is paid to the government.
INSERT INTO accounts (id, owner_id, type, balance, allow_negative, created_at, updated_at) VALUES
    ('00000000-0000-0000-0000-000000000006', NULL, 'SYSTEM_LOAN_PRINCIPAL', 0, TRUE, now(), now()),
    ('00000000-0000-0000-0000-000000000007', NULL, 'SYSTEM_INTEREST_INCOME', 0, FALSE, now(), now()),
    ('00000000-0000-0000-0000-000000000008', NULL, 'SYSTEM_TAX_PAYABLE', 0, FALSE, now(), now());

-- Every automated credit decision is stored with its inputs and reasons, so it can be explained to the customer.
CREATE TABLE credit_analyses (
    id              UUID                     PRIMARY KEY,
    user_id         BIGINT                   NOT NULL REFERENCES users (id),
    bureau_score    INT                      NOT NULL,
    internal_score  INT                      NOT NULL,
    score           INT                      NOT NULL,
    risk_band       VARCHAR(1),
    approved        BOOLEAN                  NOT NULL,
    credit_limit    BIGINT                   NOT NULL,
    monthly_rate    NUMERIC(8, 6),
    reasons         TEXT                     NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_credit_analyses_user ON credit_analyses (user_id, created_at DESC);

CREATE TABLE loans (
    id                     UUID                     PRIMARY KEY,
    user_id                BIGINT                   NOT NULL REFERENCES users (id),
    analysis_id            UUID                     NOT NULL REFERENCES credit_analyses (id),
    amount                 BIGINT                   NOT NULL,
    iof                    BIGINT                   NOT NULL,
    financed               BIGINT                   NOT NULL,
    monthly_rate           NUMERIC(8, 6)            NOT NULL,
    cet_monthly            NUMERIC(10, 6)           NOT NULL,
    cet_annual             NUMERIC(10, 6)           NOT NULL,
    installments           INT                      NOT NULL,
    status                 VARCHAR(8)               NOT NULL,
    idempotency_key        VARCHAR(200)             NOT NULL UNIQUE,
    ledger_transaction_id  UUID                     NOT NULL REFERENCES ledger_transactions (id),
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    paid_off_at            TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_loans_status CHECK (status IN ('ACTIVE', 'PAID_OFF')),
    CONSTRAINT ck_loans_amounts CHECK (amount > 0 AND iof >= 0 AND financed = amount + iof)
);

CREATE INDEX idx_loans_user ON loans (user_id, created_at DESC);

CREATE TABLE loan_installments (
    loan_id                UUID                     NOT NULL REFERENCES loans (id),
    number                 INT                      NOT NULL,
    due_date               DATE                     NOT NULL,
    amount                 BIGINT                   NOT NULL,
    principal              BIGINT                   NOT NULL,
    interest               BIGINT                   NOT NULL,
    status                 VARCHAR(7)               NOT NULL,
    late_charges           BIGINT,
    paid_at                TIMESTAMP WITH TIME ZONE,
    ledger_transaction_id  UUID                     REFERENCES ledger_transactions (id),
    PRIMARY KEY (loan_id, number),
    CONSTRAINT ck_installments_status CHECK (status IN ('PENDING', 'OVERDUE', 'PAID')),
    CONSTRAINT ck_installments_amount CHECK (amount = principal + interest)
);

CREATE INDEX idx_installments_due ON loan_installments (due_date) WHERE status <> 'PAID';
