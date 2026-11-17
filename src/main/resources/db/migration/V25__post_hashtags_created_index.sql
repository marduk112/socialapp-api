-- Stage 4C: trending hashtags aggregate every post_hashtags row of a recent time window
-- (GET /trends/hashtags: WHERE created_at >= now() - window). The V17 index starts with `tag`, so it cannot serve a
-- range over all tags; this one turns the window into an index range scan.
CREATE INDEX ix_post_hashtags_created ON post_hashtags (created_at DESC);
