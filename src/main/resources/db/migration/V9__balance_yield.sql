ALTER TABLE accounts DROP CONSTRAINT ck_accounts_type;
ALTER TABLE accounts ADD CONSTRAINT ck_accounts_type
    CHECK (type IN ('USER_WALLET', 'SYSTEM_CASH_IN', 'SYSTEM_FEES', 'SYSTEM_PIX_SETTLEMENT',
                    'SYSTEM_BILL_SETTLEMENT', 'SYSTEM_YIELD'));

ALTER TABLE ledger_transactions DROP CONSTRAINT ck_ledger_transactions_type;
ALTER TABLE ledger_transactions ADD CONSTRAINT ck_ledger_transactions_type
    CHECK (type IN ('P2P_TRANSFER', 'CASH_IN', 'PIX_INTERNAL', 'PIX_OUT', 'PIX_OUT_REVERSAL', 'PIX_IN',
                    'CHARGE_PAYMENT', 'BILL_PAYMENT', 'BILL_PAYMENT_REVERSAL', 'YIELD_CREDIT'));

-- Source of the yield paid to customers: the return on the assets where customer balances are invested.
INSERT INTO accounts (id, owner_id, type, balance, allow_negative, created_at, updated_at) VALUES
    ('00000000-0000-0000-0000-000000000005', NULL, 'SYSTEM_YIELD', 0, TRUE, now(), now());

-- One row per business day processed.
CREATE TABLE yield_runs (
    reference_date   DATE                     PRIMARY KEY,
    cdi_daily_rate   NUMERIC(12, 8)           NOT NULL,
    cdi_percentage   NUMERIC(6, 2)            NOT NULL,
    accounts         INT                      NOT NULL DEFAULT 0,
    total_credited   BIGINT                   NOT NULL DEFAULT 0,
    started_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at     TIMESTAMP WITH TIME ZONE
);

-- One row per account and business day. Yield is computed with sub-cent precision; whole cents are credited
-- and the remainder is carried to the next day, so small balances still earn over time.
CREATE TABLE yield_accruals (
    account_id             UUID                     NOT NULL REFERENCES accounts (id),
    reference_date         DATE                     NOT NULL REFERENCES yield_runs (reference_date),
    balance                BIGINT                   NOT NULL,
    accrued                NUMERIC(24, 10)          NOT NULL,
    credited               BIGINT                   NOT NULL,
    carry                  NUMERIC(24, 10)          NOT NULL,
    ledger_transaction_id  UUID                     REFERENCES ledger_transactions (id),
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (account_id, reference_date),
    CONSTRAINT ck_yield_accruals_carry CHECK (carry >= 0 AND carry < 1)
);

CREATE INDEX idx_yield_accruals_account ON yield_accruals (account_id, reference_date DESC);
