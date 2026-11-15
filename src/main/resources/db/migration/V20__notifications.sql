-- Notification BC (stage 3A). Fed only by Kafka events (user-, social-, post-, reaction-, comment-events); no
-- foreign keys to users/posts/comments of other BCs (BC isolation; events may also arrive before or after the
-- referenced rows exist or are deleted).

-- Notification's own projection of user display data (from user-events): create-if-absent on UserCreated,
-- newest occurredAt wins on UserUpdated (same rules as Feed's author_profiles).
CREATE TABLE notification_actor_profiles (
    user_id       UUID PRIMARY KEY,
    username      VARCHAR(50),
    avatar_url    TEXT,
    last_event_at TIMESTAMPTZ
);

-- One row per (recipient, type, actor, target) ever notified: the dedupe key. target_id is the followed user for
-- FOLLOW, the liked post/comment for LIKE, the new comment for COMMENT/REPLY, the mentioning post for MENTION, the
-- ORIGINAL post for REPOST (re-reposting never notifies twice) and the quote post for QUOTE.
-- retracted = tombstone: a LIKE/FOLLOW undone while the notification was still unread is hidden (not deleted), so
-- a late/redelivered older event cannot resurrect it; last_event_at (occurredAt of the last applied event) makes
-- every change "newest wins", hence idempotent and order-tolerant.
CREATE TABLE notifications (
    id             UUID         PRIMARY KEY,
    recipient_id   UUID         NOT NULL,
    type           VARCHAR(16)  NOT NULL,
    actor_id       UUID         NOT NULL,
    target_id      UUID         NOT NULL,
    post_id        UUID,                         -- the post to open (null for FOLLOW)
    comment_id     UUID,                         -- COMMENT / REPLY / LIKE on a comment
    snippet        VARCHAR(160),                 -- <= 120 code points (shared.events.Snippet)
    actor_username VARCHAR(50),                  -- snapshot from the event, used until the profile is known
    created_at     TIMESTAMPTZ  NOT NULL,        -- occurredAt of the event that (re)created it
    last_event_at  TIMESTAMPTZ  NOT NULL,
    read_at        TIMESTAMPTZ,
    retracted      BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT ux_notifications_key UNIQUE (recipient_id, type, actor_id, target_id),
    CONSTRAINT ck_notifications_type CHECK (type IN ('FOLLOW', 'LIKE', 'COMMENT', 'REPLY', 'MENTION', 'REPOST', 'QUOTE')),
    CONSTRAINT ck_notifications_not_self CHECK (recipient_id <> actor_id)
);

-- GET /notifications: keyset over (created_at DESC, id DESC) per recipient, tombstones skipped.
CREATE INDEX ix_notifications_recipient_page ON notifications (recipient_id, created_at DESC, id DESC)
    WHERE NOT retracted;
-- GET /notifications/unread-count and "mark all as read".
CREATE INDEX ix_notifications_recipient_unread ON notifications (recipient_id)
    WHERE read_at IS NULL AND NOT retracted;

-- No backfill of historic notifications: events that occurred before this migration ran (still retained in Kafka
-- and replayed by the new consumer groups from the earliest offset) are ignored. The profile projection does use them.
CREATE TABLE notification_cutoff (
    id    SMALLINT    PRIMARY KEY CHECK (id = 1),
    since TIMESTAMPTZ NOT NULL
);
INSERT INTO notification_cutoff (id, since) VALUES (1, now());
