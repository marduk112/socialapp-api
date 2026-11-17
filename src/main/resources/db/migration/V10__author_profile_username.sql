-- Feed's local projection of user data (fed only by user-events, never by reading users).
-- username: lets Feed refresh the denormalised author_username of posts/comments on rename.
-- last_event_at: occurredAt of the last user event applied, so a stale/late event never
--                overwrites newer data (order-tolerant projection).
ALTER TABLE author_profiles ADD COLUMN username      VARCHAR(50);
ALTER TABLE author_profiles ADD COLUMN last_event_at TIMESTAMPTZ;

-- Backfill username from Feed's own data (most recent post or comment of each author).
UPDATE author_profiles ap
SET username = src.author_username
FROM (
    SELECT DISTINCT ON (author_id) author_id, author_username
    FROM (
        SELECT author_id, author_username, created_at FROM posts
        UNION ALL
        SELECT author_id, author_username, created_at FROM comments
    ) x
    ORDER BY author_id, created_at DESC
) src
WHERE ap.author_id = src.author_id;
