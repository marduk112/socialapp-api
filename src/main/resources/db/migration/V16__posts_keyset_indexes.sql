-- Keyset (cursor) pagination of the feed: ORDER BY created_at DESC, id DESC with
-- WHERE (created_at, id) < (:cursorCreatedAt, :cursorId).

-- scope=all: one ordered index scan, stops after the page.
CREATE INDEX ix_posts_created_id ON posts (created_at DESC, id DESC);

-- scope=following: per-author ordered scans (author_id IN followees + self) merged by created_at, id.
-- Supersedes idx_posts_author (author_id, created_at DESC), which is a prefix of it.
CREATE INDEX ix_posts_author_created_id ON posts (author_id, created_at DESC, id DESC);
DROP INDEX IF EXISTS idx_posts_author;
