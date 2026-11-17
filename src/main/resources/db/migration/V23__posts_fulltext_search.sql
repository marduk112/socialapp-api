-- Stage 4B: full-text search over post text (GET /search/posts).
-- 'simple' configuration: no stemming and no stop words, so it behaves the same for Polish and English text
-- (accents/inflections are not folded). posts.content is the post's own text (for a QUOTE its comment; REPOSTs
-- carry none and are excluded by the query). The query must use exactly this expression to use the index:
--   to_tsvector('simple', p.content) @@ websearch_to_tsquery('simple', :q)
CREATE INDEX ix_posts_content_fts ON posts USING GIN (to_tsvector('simple', content));
