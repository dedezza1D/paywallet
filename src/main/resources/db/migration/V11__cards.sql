ALTER TABLE accounts DROP CONSTRAINT ck_accounts_type;
ALTER TABLE accounts ADD CONSTRAINT ck_accounts_type
    CHECK (type IN ('USER_WALLET', 'SYSTEM_CASH_IN', 'SYSTEM_FEES', 'SYSTEM_PIX_SETTLEMENT',
                    'SYSTEM_BILL_SETTLEMENT', 'SYSTEM_YIELD', 'SYSTEM_LOAN_PRINCIPAL', 'SYSTEM_INTEREST_INCOME',
                    'SYSTEM_TAX_PAYABLE', 'SYSTEM_CARD_HOLDS', 'SYSTEM_CARD_SETTLEMENT', 'SYSTEM_CARD_RECEIVABLES'));

ALTER TABLE ledger_transactions DROP CONSTRAINT ck_ledger_transactions_type;
ALTER TABLE ledger_transactions ADD CONSTRAINT ck_ledger_transactions_type
    CHECK (type IN ('P2P_TRANSFER', 'CASH_IN', 'PIX_INTERNAL', 'PIX_OUT', 'PIX_OUT_REVERSAL', 'PIX_IN',
                    'CHARGE_PAYMENT', 'BILL_PAYMENT', 'BILL_PAYMENT_REVERSAL', 'YIELD_CREDIT',
                    'LOAN_DISBURSEMENT', 'LOAN_INSTALLMENT_PAYMENT', 'CARD_HOLD', 'CARD_HOLD_RELEASE',
                    'CARD_DEBIT_CLEARING', 'CARD_CREDIT_CLEARING', 'CARD_REVOLVING_INTEREST',
                    'CARD_STATEMENT_PAYMENT'));

-- CARD_HOLDS: debit card money reserved at authorization, until cleared or released.
-- CARD_SETTLEMENT: owed to the card network for cleared purchases (settled outside the platform).
-- CARD_RECEIVABLES: what credit card holders owe (negative while there is debt).
INSERT INTO accounts (id, owner_id, type, balance, allow_negative, created_at, updated_at) VALUES
    ('00000000-0000-0000-0000-000000000009', NULL, 'SYSTEM_CARD_HOLDS', 0, FALSE, now(), now()),
    ('00000000-0000-0000-0000-000000000010', NULL, 'SYSTEM_CARD_SETTLEMENT', 0, FALSE, now(), now()),
    ('00000000-0000-0000-0000-000000000011', NULL, 'SYSTEM_CARD_RECEIVABLES', 0, TRUE, now(), now());

-- Card numbers and CVVs never reach this platform (PCI-DSS): the processor keeps them and returns a token.
CREATE TABLE cards (
    id               UUID                     PRIMARY KEY,
    user_id          BIGINT                   NOT NULL REFERENCES users (id),
    type             VARCHAR(6)               NOT NULL,
    status           VARCHAR(9)               NOT NULL,
    processor_token  VARCHAR(64)              NOT NULL UNIQUE,
    last4            VARCHAR(4)               NOT NULL,
    brand            VARCHAR(20)              NOT NULL,
    exp_month        INT                      NOT NULL,
    exp_year         INT                      NOT NULL,
    credit_limit     BIGINT,
    closing_day      INT,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_cards_type CHECK (type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_cards_status CHECK (status IN ('ACTIVE', 'BLOCKED', 'CANCELLED')),
    CONSTRAINT ck_cards_credit CHECK (type = 'DEBIT' OR (credit_limit > 0 AND closing_day BETWEEN 1 AND 28))
);

CREATE UNIQUE INDEX ux_cards_one_active_per_type ON cards (user_id, type) WHERE status <> 'CANCELLED';

-- Keyed by the processor's authorization id, so repeated authorization requests are answered consistently.
CREATE TABLE card_authorizations (
    id                     VARCHAR(64)              PRIMARY KEY,
    card_id                UUID                     NOT NULL REFERENCES cards (id),
    amount                 BIGINT                   NOT NULL,
    merchant_name          VARCHAR(100)             NOT NULL,
    mcc                    VARCHAR(4),
    installments           INT                      NOT NULL,
    status                 VARCHAR(8)               NOT NULL,
    response_code          VARCHAR(2)               NOT NULL,
    decline_reason         VARCHAR(100),
    cleared_amount         BIGINT,
    hold_transaction_id    UUID                     REFERENCES ledger_transactions (id),
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_card_auth_status CHECK (status IN ('APPROVED', 'DECLINED', 'CLEARED', 'REVERSED')),
    CONSTRAINT ck_card_auth_amount CHECK (amount > 0 AND installments BETWEEN 1 AND 12)
);

CREATE INDEX idx_card_authorizations_card ON card_authorizations (card_id, created_at DESC);

CREATE TABLE card_statements (
    id               UUID                     PRIMARY KEY,
    card_id          UUID                     NOT NULL REFERENCES cards (id),
    closing_date     DATE                     NOT NULL,
    due_date         DATE                     NOT NULL,
    total            BIGINT                   NOT NULL,
    minimum_payment  BIGINT                   NOT NULL,
    paid             BIGINT                   NOT NULL DEFAULT 0,
    status           VARCHAR(7)               NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (card_id, closing_date),
    CONSTRAINT ck_statements_status CHECK (status IN ('OPEN', 'PAID', 'CARRIED')),
    CONSTRAINT ck_statements_paid CHECK (paid >= 0 AND paid <= total)
);

-- Credit card lines: each installment of a purchase, carried balances and revolving interest. A line enters the
-- first statement that closes on or after its billing date.
CREATE TABLE card_charges (
    id                 UUID                     PRIMARY KEY,
    card_id            UUID                     NOT NULL REFERENCES cards (id),
    authorization_id   VARCHAR(64)              REFERENCES card_authorizations (id),
    description        VARCHAR(140)             NOT NULL,
    amount             BIGINT                   NOT NULL,
    installment        INT                      NOT NULL DEFAULT 1,
    installments       INT                      NOT NULL DEFAULT 1,
    billing_date       DATE                     NOT NULL,
    statement_id       UUID                     REFERENCES card_statements (id),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_card_charges_amount CHECK (amount > 0)
);

CREATE INDEX idx_card_charges_unbilled ON card_charges (card_id, billing_date) WHERE statement_id IS NULL;
