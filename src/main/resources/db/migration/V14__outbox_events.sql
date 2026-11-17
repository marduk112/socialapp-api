-- Transactional outbox for domain events (see com.socialapp.config.outbox).
-- A row is inserted in the SAME transaction as the aggregate change; OutboxRelay publishes rows to
-- Kafka in id order and stamps published_at. Rows are never updated by business code.
CREATE TABLE outbox_events (
    id             BIGSERIAL    PRIMARY KEY,           -- publish order
    event_id       UUID         NOT NULL,              -- DomainEvent.eventId (as produced by the aggregate)
    aggregate_type VARCHAR(100) NOT NULL,              -- e.g. User, Post
    event_type     VARCHAR(100) NOT NULL,              -- DomainEvent.eventType
    topic          VARCHAR(249) NOT NULL,
    message_key    VARCHAR(255) NOT NULL,              -- Kafka record key (aggregate id)
    payload        JSONB        NOT NULL,              -- the event as JSON (same mapper as the Kafka serializer)
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,                        -- NULL = not yet published
    attempts       INT          NOT NULL DEFAULT 0,    -- failed publish attempts
    last_error     TEXT,
    parked_at      TIMESTAMPTZ,                        -- set when a poison row is parked (blocks only its key)
    CONSTRAINT uq_outbox_events_event_id UNIQUE (event_id)
);

-- Relay scan: unpublished rows in id order (stays tiny when the relay keeps up).
CREATE INDEX ix_outbox_events_unpublished ON outbox_events (id) WHERE published_at IS NULL;

-- Per-key blocking behind parked (poison) rows.
CREATE INDEX ix_outbox_events_parked_key ON outbox_events (topic, message_key, id)
    WHERE published_at IS NULL AND parked_at IS NOT NULL;

-- Retention cleanup of published rows.
CREATE INDEX ix_outbox_events_published_at ON outbox_events (published_at) WHERE published_at IS NOT NULL;
