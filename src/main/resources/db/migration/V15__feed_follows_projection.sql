-- Feed's local projection of the follow graph, fed only by social-events (UserFollowedEvent /
-- UserUnfollowedEvent from SocialGraph). No foreign keys to users: BC isolation, and events may arrive
-- before Feed knows the users.
--
-- One row per (follower, followee) pair ever seen. An unfollow does not delete the row but turns it into a
-- tombstone (active = false), so a late/redelivered follow that is OLDER than the unfollow cannot
-- resurrect it: an event is applied only if its occurredAt is newer than last_event_at ("newest wins").
-- SocialGraph assigns occurredAt from a per-follower clock that is strictly increasing in graph commit
-- order, so the newest event per pair always reflects the graph's current state.
CREATE TABLE follows (
    follower_id   UUID        NOT NULL,
    followee_id   UUID        NOT NULL,
    active        BOOLEAN     NOT NULL,          -- false = tombstone of an unfollow
    followed_at   TIMESTAMPTZ,                   -- occurredAt of the follow; NULL while tombstoned
    last_event_at TIMESTAMPTZ NOT NULL,          -- occurredAt of the last applied event (order tolerance)
    CONSTRAINT pk_follows PRIMARY KEY (follower_id, followee_id),
    CONSTRAINT ck_follows_not_self CHECK (follower_id <> followee_id)
);

-- Following timeline / SSE filter: "active followees of X". Partial and covering, so tombstones are skipped.
CREATE INDEX ix_follows_follower_active ON follows (follower_id, followee_id) WHERE active;
