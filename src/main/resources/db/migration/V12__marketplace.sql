ALTER TABLE accounts DROP CONSTRAINT ck_accounts_type;
ALTER TABLE accounts ADD CONSTRAINT ck_accounts_type
    CHECK (type IN ('USER_WALLET', 'SYSTEM_CASH_IN', 'SYSTEM_FEES', 'SYSTEM_PIX_SETTLEMENT',
                    'SYSTEM_BILL_SETTLEMENT', 'SYSTEM_YIELD', 'SYSTEM_LOAN_PRINCIPAL', 'SYSTEM_INTEREST_INCOME',
                    'SYSTEM_TAX_PAYABLE', 'SYSTEM_CARD_HOLDS', 'SYSTEM_CARD_SETTLEMENT', 'SYSTEM_CARD_RECEIVABLES',
                    'SYSTEM_MARKETPLACE_SETTLEMENT', 'SYSTEM_CASHBACK'));

ALTER TABLE ledger_transactions DROP CONSTRAINT ck_ledger_transactions_type;
ALTER TABLE ledger_transactions ADD CONSTRAINT ck_ledger_transactions_type
    CHECK (type IN ('P2P_TRANSFER', 'CASH_IN', 'PIX_INTERNAL', 'PIX_OUT', 'PIX_OUT_REVERSAL', 'PIX_IN',
                    'CHARGE_PAYMENT', 'BILL_PAYMENT', 'BILL_PAYMENT_REVERSAL', 'YIELD_CREDIT',
                    'LOAN_DISBURSEMENT', 'LOAN_INSTALLMENT_PAYMENT', 'CARD_HOLD', 'CARD_HOLD_RELEASE',
                    'CARD_DEBIT_CLEARING', 'CARD_CREDIT_CLEARING', 'CARD_REVOLVING_INTEREST',
                    'CARD_STATEMENT_PAYMENT', 'MARKETPLACE_PURCHASE', 'MARKETPLACE_REFUND',
                    'MARKETPLACE_COMMISSION', 'CASHBACK_CREDIT'));

-- MARKETPLACE_SETTLEMENT: purchases owed to product providers, net of the platform commission.
-- CASHBACK: marketing expense paid back to customers (negative as cashback accumulates).
INSERT INTO accounts (id, owner_id, type, balance, allow_negative, created_at, updated_at) VALUES
    ('00000000-0000-0000-0000-000000000012', NULL, 'SYSTEM_MARKETPLACE_SETTLEMENT', 0, FALSE, now(), now()),
    ('00000000-0000-0000-0000-000000000013', NULL, 'SYSTEM_CASHBACK', 0, TRUE, now(), now());

-- Commission is what the provider pays the platform per sale; cashback returns part of it to the customer.
CREATE TABLE marketplace_products (
    id              VARCHAR(40)  PRIMARY KEY,
    category        VARCHAR(15)  NOT NULL,
    brand           VARCHAR(40)  NOT NULL,
    name            VARCHAR(80)  NOT NULL,
    denominations   VARCHAR(200) NOT NULL,
    commission_bps  INT          NOT NULL,
    cashback_bps    INT          NOT NULL,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_products_category CHECK (category IN ('GIFT_CARD', 'MOBILE_RECHARGE')),
    CONSTRAINT ck_products_rates CHECK (commission_bps BETWEEN 0 AND 10000 AND cashback_bps BETWEEN 0 AND commission_bps)
);

INSERT INTO marketplace_products (id, category, brand, name, denominations, commission_bps, cashback_bps) VALUES
    ('netflix', 'GIFT_CARD', 'Netflix', 'Netflix gift card', '3500,5000,7000,10000', 500, 200),
    ('spotify', 'GIFT_CARD', 'Spotify', 'Spotify Premium gift card', '2190,4380,6570', 500, 200),
    ('uber', 'GIFT_CARD', 'Uber', 'Uber credit', '2000,5000,10000', 400, 150),
    ('ifood', 'GIFT_CARD', 'iFood', 'iFood credit', '2500,5000,10000', 400, 150),
    ('google-play', 'GIFT_CARD', 'Google Play', 'Google Play gift card', '3000,5000,10000,20000', 600, 250),
    ('vivo', 'MOBILE_RECHARGE', 'Vivo', 'Vivo prepaid recharge', '1500,2000,3000,5000,10000', 300, 100),
    ('claro', 'MOBILE_RECHARGE', 'Claro', 'Claro prepaid recharge', '1500,2000,3000,5000,10000', 300, 100),
    ('tim', 'MOBILE_RECHARGE', 'TIM', 'TIM prepaid recharge', '1500,2000,3000,5000,10000', 300, 100);

CREATE TABLE marketplace_orders (
    id                       UUID                     PRIMARY KEY,
    user_id                  BIGINT                   NOT NULL REFERENCES users (id),
    product_id               VARCHAR(40)              NOT NULL REFERENCES marketplace_products (id),
    amount                   BIGINT                   NOT NULL,
    commission               BIGINT                   NOT NULL,
    cashback                 BIGINT                   NOT NULL,
    phone_number             VARCHAR(11),
    status                   VARCHAR(9)               NOT NULL,
    idempotency_key          VARCHAR(200)             NOT NULL UNIQUE,
    ledger_transaction_id    UUID                     NOT NULL REFERENCES ledger_transactions (id),
    reversal_transaction_id  UUID                     REFERENCES ledger_transactions (id),
    -- Gift card codes are bearer credentials: stored encrypted (AES-GCM), shown only to the buyer.
    voucher_code             VARCHAR(512),
    provider_reference       VARCHAR(64),
    failure_reason           VARCHAR(255),
    attempts                 INT                      NOT NULL DEFAULT 0,
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at             TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_orders_amounts CHECK (amount > 0 AND commission >= 0 AND cashback >= 0 AND cashback <= commission)
);

CREATE INDEX idx_marketplace_orders_pending ON marketplace_orders (created_at) WHERE status = 'PENDING';
CREATE INDEX idx_marketplace_orders_user ON marketplace_orders (user_id, created_at DESC);
