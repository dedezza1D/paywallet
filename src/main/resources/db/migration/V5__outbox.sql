-- Transactional outbox: events are written in the same transaction as the business change and
-- relayed to Kafka afterwards, so a crash can never commit money without its event (or vice versa).
CREATE TABLE outbox_events (
    id             UUID                     PRIMARY KEY,
    topic          VARCHAR(200)             NOT NULL,
    message_key    VARCHAR(200)             NOT NULL,
    event_type     VARCHAR(100)             NOT NULL,
    payload        JSONB                    NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at   TIMESTAMP WITH TIME ZONE,
    attempts       INT                      NOT NULL DEFAULT 0,
    last_error     VARCHAR(1000)
);

CREATE INDEX idx_outbox_pending ON outbox_events (created_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_published ON outbox_events (published_at) WHERE published_at IS NOT NULL;
