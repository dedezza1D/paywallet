-- Yield is now credited net of income tax and IOF, which go to SYSTEM_TAX_PAYABLE. "credited" keeps meaning what
-- reached the wallet; earlier days were credited gross, with nothing withheld.
ALTER TABLE yield_accruals ADD COLUMN gross BIGINT;
UPDATE yield_accruals SET gross = credited;
ALTER TABLE yield_accruals ALTER COLUMN gross SET NOT NULL;
ALTER TABLE yield_accruals ADD COLUMN income_tax BIGINT NOT NULL DEFAULT 0;
ALTER TABLE yield_accruals ADD COLUMN iof BIGINT NOT NULL DEFAULT 0;
ALTER TABLE yield_accruals ADD CONSTRAINT ck_yield_accruals_net CHECK (credited = gross - income_tax - iof);

ALTER TABLE yield_runs ADD COLUMN total_withheld BIGINT NOT NULL DEFAULT 0;

-- The balance split by the local date each part came in, so taxes follow how long the money has been invested.
CREATE TABLE yield_lots (
    account_id  UUID    NOT NULL REFERENCES accounts (id),
    lot_date    DATE    NOT NULL,
    amount      BIGINT  NOT NULL,
    PRIMARY KEY (account_id, lot_date),
    CONSTRAINT ck_yield_lots_amount CHECK (amount > 0)
);

-- Postings of each wallet already reflected in its lots.
CREATE TABLE yield_lot_cursors (
    account_id       UUID                     PRIMARY KEY REFERENCES accounts (id),
    processed_until  TIMESTAMP WITH TIME ZONE NOT NULL
);
