-- Stage 5A: Feed's local projections of blocks and mutes, fed only by social-events (UserBlocked/UserUnblocked,
-- UserMuted/UserUnmuted from SocialGraph). Same rules as follows (V15): one row per pair ever seen, no foreign keys
-- (BC isolation), an undo leaves a tombstone (active = false), an event is applied only if its occurredAt is newer than
-- last_event_at ("newest wins": SocialGraph's per-user clock is strictly increasing per blocker / muter).
--
-- Read side: every list query computes "users hidden from viewer V" with the indexes below (blocks in BOTH directions,
-- mutes only V's own) and filters in SQL, before LIMIT, so keyset pagination stays exact.
CREATE TABLE feed_blocks (
    blocker_id    UUID        NOT NULL,
    blocked_id    UUID        NOT NULL,
    active        BOOLEAN     NOT NULL,          -- false = tombstone of an unblock
    last_event_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_feed_blocks PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT ck_feed_blocks_not_self CHECK (blocker_id <> blocked_id)
);
-- "who blocks V" (the PK serves "whom V blocks").
CREATE INDEX ix_feed_blocks_blocked_active ON feed_blocks (blocked_id, blocker_id) WHERE active;

CREATE TABLE feed_mutes (
    muter_id      UUID        NOT NULL,
    muted_id      UUID        NOT NULL,
    active        BOOLEAN     NOT NULL,          -- false = tombstone of an unmute
    last_event_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_feed_mutes PRIMARY KEY (muter_id, muted_id),
    CONSTRAINT ck_feed_mutes_not_self CHECK (muter_id <> muted_id)
);
