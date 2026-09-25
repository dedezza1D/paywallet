-- Double-entry ledger.
-- Amounts are always in cents (BIGINT). postings is the source of truth and is immutable;
-- accounts.balance is a snapshot updated in the same transaction as the postings, used for fast
-- reads and as the row lock. CREDIT increases a balance, DEBIT decreases it, so all balances sum to zero.

CREATE TABLE accounts (
    id              UUID                     PRIMARY KEY,
    owner_id        BIGINT                   REFERENCES users (id),
    type            VARCHAR(30)              NOT NULL,
    currency        VARCHAR(3)               NOT NULL DEFAULT 'BRL',
    balance         BIGINT                   NOT NULL DEFAULT 0,
    allow_negative  BOOLEAN                  NOT NULL DEFAULT FALSE,
    version         BIGINT                   NOT NULL DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_accounts_type CHECK (type IN ('USER_WALLET', 'SYSTEM_CASH_IN', 'SYSTEM_FEES')),
    CONSTRAINT ck_accounts_non_negative CHECK (allow_negative OR balance >= 0),
    CONSTRAINT ck_accounts_user_has_owner CHECK (type <> 'USER_WALLET' OR owner_id IS NOT NULL)
);

CREATE UNIQUE INDEX ux_accounts_owner_wallet ON accounts (owner_id) WHERE type = 'USER_WALLET';

CREATE TABLE ledger_transactions (
    id               UUID                     PRIMARY KEY,
    type             VARCHAR(30)              NOT NULL,
    idempotency_key  VARCHAR(200)             NOT NULL UNIQUE,
    description      VARCHAR(255),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_ledger_transactions_type CHECK (type IN ('P2P_TRANSFER', 'CASH_IN'))
);

CREATE TABLE postings (
    id              UUID                     PRIMARY KEY,
    transaction_id  UUID                     NOT NULL REFERENCES ledger_transactions (id),
    account_id      UUID                     NOT NULL REFERENCES accounts (id),
    direction       VARCHAR(6)               NOT NULL,
    amount          BIGINT                   NOT NULL,
    balance_after   BIGINT                   NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_postings_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_postings_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_postings_account_created ON postings (account_id, created_at DESC);
CREATE INDEX idx_postings_transaction ON postings (transaction_id);

-- Ledger rows are never updated or deleted; corrections are new reversing transactions.
CREATE FUNCTION ledger_forbid_changes() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Ledger records are immutable (table %)', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_postings_immutable
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_changes();

CREATE TRIGGER trg_ledger_transactions_immutable
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_changes();

-- Enforced by the database at COMMIT, so even an application bug cannot persist an unbalanced transaction.
CREATE FUNCTION ledger_check_balanced() RETURNS trigger AS $$
DECLARE
    diff BIGINT;
BEGIN
    SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0)
      INTO diff
      FROM postings
     WHERE transaction_id = NEW.transaction_id;

    IF diff <> 0 THEN
        RAISE EXCEPTION 'Transaction % is unbalanced (off by % cents)', NEW.transaction_id, diff;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_postings_balanced
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_balanced();

-- System counterpart accounts. CASH_IN offsets money entering from outside and goes negative
-- (it is what the platform owes its customers); FEES holds platform revenue.
INSERT INTO accounts (id, owner_id, type, balance, allow_negative, created_at, updated_at) VALUES
    ('00000000-0000-0000-0000-000000000001', NULL, 'SYSTEM_CASH_IN', 0, TRUE,  now(), now()),
    ('00000000-0000-0000-0000-000000000002', NULL, 'SYSTEM_FEES',    0, FALSE, now(), now());
