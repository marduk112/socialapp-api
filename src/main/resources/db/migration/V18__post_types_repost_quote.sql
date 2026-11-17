-- Stage 2C: first-class post types. POST (own text) | REPOST (no text, at most one per author+original) |
-- QUOTE (required comment). A REPOST/QUOTE references its original by original_post_id (V3 FK, ON DELETE SET
-- NULL, kept): when an original is deleted, PostService deletes its REPOSTs first and clears the snapshot columns
-- of its QUOTEs; the FK then nulls the QUOTEs' original_post_id ("original unavailable"). The CHECK below makes
-- the database refuse a REPOST without original, so a deletion path that forgets the REPOSTs fails instead of
-- leaving orphans.
--
-- Backfill of legacy "shares" (rows with any original_* column set):
--   blank content -> REPOST (content normalised to ''), otherwise -> QUOTE.
--   Flattening: a share of a share that is now a REPOST is re-pointed at the end of that chain (the first
--     non-REPOST), and its original_content / original_author_username snapshot is taken from that post.
--   Original gone (original_post_id NULL, or a chain ending in a deleted post): REPOSTs are deleted (same rule as
--     deleting an original now), QUOTEs stay with original_post_id / original_content / original_author_username
--     NULL.
--   Several REPOSTs by one author of the same original: the oldest (created_at, id) is kept, the others are
--     deleted (they had no text; their comments/reactions/index rows go with them by FK cascade).
--   image_url of REPOSTs/QUOTEs is cleared: a share never had an image of its own (it copied the original's),
--     clients show the original's image through the nested original.

ALTER TABLE posts ADD COLUMN type VARCHAR(10) NOT NULL DEFAULT 'POST';

-- 1. Classify legacy shares.
UPDATE posts
SET type = CASE WHEN content ~ '^[[:space:]]*$' THEN 'REPOST' ELSE 'QUOTE' END
WHERE original_post_id IS NOT NULL OR original_author_username IS NOT NULL OR original_content IS NOT NULL;

-- 2. Flatten: every REPOST/QUOTE that points at a REPOST gets the final target of the chain (NULL = dead end).
WITH RECURSIVE chain (start_id, target_id, depth) AS (
    SELECT p.id, t.original_post_id, 1
    FROM posts p JOIN posts t ON t.id = p.original_post_id
    WHERE p.type IN ('REPOST', 'QUOTE') AND t.type = 'REPOST'
  UNION ALL
    SELECT c.start_id, t.original_post_id, c.depth + 1
    FROM chain c JOIN posts t ON t.id = c.target_id
    WHERE t.type = 'REPOST' AND c.depth < 10000
),
final AS (
    SELECT DISTINCT ON (start_id) start_id, target_id FROM chain ORDER BY start_id, depth DESC
)
UPDATE posts p
SET original_post_id         = f.target_id,
    original_content         = left(o.content, 2000),
    original_author_username = o.author_username
FROM final f LEFT JOIN posts o ON o.id = f.target_id
WHERE p.id = f.start_id;

-- 3. Original gone. Nothing points at a REPOST any more (step 2), so these deletes cascade to no other post.
DELETE FROM posts WHERE type = 'REPOST' AND original_post_id IS NULL;
UPDATE posts SET original_content = NULL, original_author_username = NULL
WHERE type = 'QUOTE' AND original_post_id IS NULL;

-- 4. Duplicate REPOSTs: keep the oldest per (author, original).
DELETE FROM posts p
USING (SELECT id, row_number() OVER (PARTITION BY author_id, original_post_id ORDER BY created_at, id) AS rn
       FROM posts WHERE type = 'REPOST') d
WHERE p.id = d.id AND d.rn > 1;

-- 5. Normalise.
UPDATE posts SET content = '' WHERE type = 'REPOST' AND content <> '';
UPDATE posts SET image_url = NULL WHERE type IN ('REPOST', 'QUOTE') AND image_url IS NOT NULL;

-- Invariants of the aggregate (Post), enforced by the database as well.
ALTER TABLE posts ADD CONSTRAINT chk_posts_type CHECK (type IN ('POST', 'REPOST', 'QUOTE'));
ALTER TABLE posts ADD CONSTRAINT chk_posts_type_original CHECK (
       (type = 'POST'   AND original_post_id IS NULL)
    OR (type = 'REPOST' AND original_post_id IS NOT NULL AND content = '')
    OR  type = 'QUOTE');

-- Idempotent repost: at most one REPOST per (author, original). Also serves "did the viewer repost these posts"
-- (author_id = :viewer AND original_post_id IN (...)) and the undo lookup.
CREATE UNIQUE INDEX ux_posts_repost_author_original ON posts (author_id, original_post_id) WHERE type = 'REPOST';

-- repostsCount / quotesCount: one grouped query per page (original_post_id IN (...) GROUP BY original_post_id, type),
-- index-only. Also used by the FK's ON DELETE SET NULL and by the deletion of an original's reposts.
-- Supersedes idx_posts_original (original_post_id), which is a prefix of it.
CREATE INDEX ix_posts_original_type ON posts (original_post_id, type);
DROP INDEX IF EXISTS idx_posts_original;
