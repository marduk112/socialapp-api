-- Stage 5A: Notification's own projections of blocks and mutes (from social-events), same rules as Feed's (V26):
-- one row per pair, tombstones, "newest occurredAt wins", no foreign keys.
-- Used twice: no notification is created between a blocked pair (either direction) or from a muted actor, and the
-- read side (page, unread count, SSE re-read) hides existing ones while the block / mute is active.
CREATE TABLE notification_blocks (
    blocker_id    UUID        NOT NULL,
    blocked_id    UUID        NOT NULL,
    active        BOOLEAN     NOT NULL,
    last_event_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_notification_blocks PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT ck_notification_blocks_not_self CHECK (blocker_id <> blocked_id)
);

CREATE TABLE notification_mutes (
    muter_id      UUID        NOT NULL,
    muted_id      UUID        NOT NULL,
    active        BOOLEAN     NOT NULL,
    last_event_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_notification_mutes PRIMARY KEY (muter_id, muted_id),
    CONSTRAINT ck_notification_mutes_not_self CHECK (muter_id <> muted_id)
);
