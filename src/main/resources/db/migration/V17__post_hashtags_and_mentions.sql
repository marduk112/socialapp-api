-- Stage 2B: hashtags and @mentions of a post's own text (content; for a share, its comment only).
-- Written by PostService whenever a post is created, edited or shared (edit replaces both sets).
-- Same BC as posts, so foreign keys with ON DELETE CASCADE are fine: deleting a post drops its index rows.
-- Posts created before this migration have no rows here until they are edited (no backfill).

-- tag: normalised (lower-case, no '#'), <= 50 code points before lower-casing; lower-casing can lengthen a
--      string (e.g. U+0130 -> 2 code points), hence the headroom.
-- position: order of first appearance in the text (display order).
-- created_at: copy of posts.created_at (immutable), so the hashtag timeline is one ordered index range scan
--      over (tag, created_at DESC, post_id DESC) with the same keyset cursor as /feed, instead of joining and
--      sorting every post of a popular tag.
CREATE TABLE post_hashtags (
    post_id    UUID         NOT NULL,
    tag        VARCHAR(150) NOT NULL,
    position   SMALLINT     NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_post_hashtags PRIMARY KEY (post_id, tag),
    CONSTRAINT fk_post_hashtags_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE
);

-- GET /hashtags/{tag}/posts: WHERE tag = ? AND (created_at, post_id) < (?, ?) ORDER BY created_at DESC, post_id DESC.
CREATE INDEX ix_post_hashtags_tag_created_post ON post_hashtags (tag, created_at DESC, post_id DESC);

-- Resolved mentions only (against Feed's author_profiles; unknown @names stay plain text, self-mentions are
-- not stored). username = canonical username at save time; deliberately NOT refreshed on rename, so the
-- "@name" written in the text keeps matching it (mentioned_user_id is authoritative).
-- No FK to users (BC isolation).
CREATE TABLE post_mentions (
    post_id           UUID        NOT NULL,
    mentioned_user_id UUID        NOT NULL,
    username          VARCHAR(50) NOT NULL,
    position          SMALLINT    NOT NULL,
    CONSTRAINT pk_post_mentions PRIMARY KEY (post_id, mentioned_user_id),
    CONSTRAINT fk_post_mentions_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE
);

-- Mention resolution: case-insensitive username lookup in Feed's projection (one batched query per save).
CREATE INDEX ix_author_profiles_username_lower ON author_profiles (lower(username));
