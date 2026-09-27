ALTER TABLE ledger_transactions DROP CONSTRAINT ck_ledger_transactions_type;
ALTER TABLE ledger_transactions ADD CONSTRAINT ck_ledger_transactions_type
    CHECK (type IN ('P2P_TRANSFER', 'CASH_IN', 'PIX_INTERNAL', 'PIX_OUT', 'PIX_OUT_REVERSAL', 'PIX_IN',
                    'CHARGE_PAYMENT', 'BILL_PAYMENT', 'BILL_PAYMENT_REVERSAL', 'YIELD_CREDIT',
                    'LOAN_DISBURSEMENT', 'LOAN_INSTALLMENT_PAYMENT', 'CARD_HOLD', 'CARD_HOLD_RELEASE',
                    'CARD_DEBIT_CLEARING', 'CARD_CREDIT_CLEARING', 'CARD_REVOLVING_INTEREST',
                    'CARD_STATEMENT_PAYMENT', 'MARKETPLACE_PURCHASE', 'MARKETPLACE_REFUND',
                    'MARKETPLACE_COMMISSION', 'CASHBACK_CREDIT', 'PIX_RETURN', 'PIX_RETURN_REVERSAL',
                    'PIX_MED_BLOCK', 'PIX_MED_RELEASE', 'PIX_MED_RETURN', 'CHARGE_REFUND', 'CARD_REFUND',
                    'CARD_CHARGEBACK', 'LOAN_PREPAYMENT', 'CARD_STATEMENT_FINANCING'));

ALTER TABLE loan_installments ADD COLUMN discount BIGINT;

-- A statement turned into fixed monthly installments, billed on the following statements.
ALTER TABLE card_statements ALTER COLUMN status TYPE VARCHAR(8);
ALTER TABLE card_statements DROP CONSTRAINT ck_statements_status;
ALTER TABLE card_statements ADD CONSTRAINT ck_statements_status
    CHECK (status IN ('OPEN', 'PAID', 'CARRIED', 'FINANCED'));

CREATE TABLE card_statement_plans (
    statement_id        UUID                     PRIMARY KEY REFERENCES card_statements (id),
    installments        INT                      NOT NULL,
    monthly_rate        NUMERIC(8, 6)            NOT NULL,
    financed            BIGINT                   NOT NULL,
    installment_amount  BIGINT                   NOT NULL,
    total               BIGINT                   NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_statement_plans CHECK (installments BETWEEN 2 AND 12 AND total >= financed)
);
