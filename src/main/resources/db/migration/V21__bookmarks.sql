-- Private bookmarks ("saved posts", stage 3B). Owned by Feed; no Kafka events. user_id has no FK (users live in
-- another BC); post_id cascades, so deleting a post (also a repost item) removes everybody's bookmarks of it.
-- A bookmark of a repost item refers to that item's id (as given by the client).
CREATE TABLE bookmarks (
    user_id    UUID        NOT NULL,
    post_id    UUID        NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, post_id)
);

-- Keyset list of one user's bookmarks, newest first: WHERE user_id = ? AND (created_at, post_id) < (?, ?).
CREATE INDEX ix_bookmarks_user_created_post ON bookmarks (user_id, created_at DESC, post_id DESC);
