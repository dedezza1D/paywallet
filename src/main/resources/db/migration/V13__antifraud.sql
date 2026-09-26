-- Outflows the risk engine flagged: REVIEW went through and awaits an analyst, DECLINE was refused.
CREATE TABLE fraud_alerts (
    id            UUID                     PRIMARY KEY,
    user_id       BIGINT                   NOT NULL REFERENCES users (id),
    channel       VARCHAR(11)              NOT NULL,
    amount        BIGINT                   NOT NULL,
    counterparty  VARCHAR(120),
    score         INT                      NOT NULL,
    decision      VARCHAR(7)               NOT NULL,
    rules         VARCHAR(255)             NOT NULL,
    status        VARCHAR(9)               NOT NULL,
    resolved_by   BIGINT                   REFERENCES users (id),
    resolution_note VARCHAR(255),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_fraud_alerts_channel CHECK (channel IN ('P2P', 'PIX', 'CHARGE', 'BILL', 'MARKETPLACE', 'CARD')),
    CONSTRAINT ck_fraud_alerts_decision CHECK (decision IN ('REVIEW', 'DECLINE')),
    CONSTRAINT ck_fraud_alerts_status CHECK (status IN ('OPEN', 'DISMISSED', 'CONFIRMED'))
);

CREATE INDEX idx_fraud_alerts_open ON fraud_alerts (created_at) WHERE status = 'OPEN';
CREATE INDEX idx_fraud_alerts_user ON fraud_alerts (user_id, created_at DESC);

-- Counterparties reported as fraudulent (e.g. "pix:key", "user:42", "doc:12345678000199"): payments to them are refused.
CREATE TABLE fraud_watchlist (
    value       VARCHAR(120)             PRIMARY KEY,
    reason      VARCHAR(255)             NOT NULL,
    alert_id    UUID                     REFERENCES fraud_alerts (id),
    created_by  BIGINT                   REFERENCES users (id),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Accounts whose outflows are frozen after confirmed fraud; incoming money still arrives.
CREATE TABLE fraud_blocked_users (
    user_id     BIGINT                   PRIMARY KEY REFERENCES users (id),
    reason      VARCHAR(255)             NOT NULL,
    alert_id    UUID                     REFERENCES fraud_alerts (id),
    blocked_by  BIGINT                   REFERENCES users (id),
    blocked_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Who each customer has already paid, so a first large payment to someone new stands out.
CREATE TABLE fraud_counterparties (
    user_id       BIGINT                   NOT NULL REFERENCES users (id),
    counterparty  VARCHAR(120)             NOT NULL,
    first_seen    TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, counterparty)
);
