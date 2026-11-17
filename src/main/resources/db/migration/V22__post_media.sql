-- Images in posts (stage 3C). Owned by Feed; no Kafka events. An upload is first stored UNATTACHED (post_id NULL,
-- position NULL) and owned by the uploader; creating a post/quote attaches it (post_id + position 0..3) in the create
-- transaction. Media is immutable afterwards. owner_id has no FK (users live in another BC); post_id cascades, so
-- deleting a post removes its media rows (the service deletes the files after commit). Unattached rows older than
-- app.media.unattached-ttl are purged with their files by MediaCleanup.
CREATE TABLE post_media (
    id           UUID        PRIMARY KEY,
    owner_id     UUID        NOT NULL,
    post_id      UUID        NULL REFERENCES posts (id) ON DELETE CASCADE,
    position     SMALLINT    NULL,
    content_type VARCHAR(20) NOT NULL,
    width        INTEGER     NULL,
    height       INTEGER     NULL,
    size_bytes   BIGINT      NOT NULL,
    storage_key  VARCHAR(64) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_post_media_storage_key UNIQUE (storage_key),
    CONSTRAINT chk_post_media_content_type
        CHECK (content_type IN ('image/jpeg', 'image/png', 'image/gif', 'image/webp')),
    CONSTRAINT chk_post_media_attachment
        CHECK ((post_id IS NULL AND position IS NULL) OR (post_id IS NOT NULL AND position BETWEEN 0 AND 3)),
    CONSTRAINT chk_post_media_dimensions
        CHECK ((width IS NULL OR width > 0) AND (height IS NULL OR height > 0)),
    CONSTRAINT chk_post_media_size CHECK (size_bytes > 0)
);

-- Media of a page of posts, in display order: WHERE post_id IN (...) ORDER BY post_id, position. Unique: one image per
-- slot of a post.
CREATE UNIQUE INDEX ux_post_media_post_position ON post_media (post_id, position) WHERE post_id IS NOT NULL;

-- Pending (unattached) uploads of one user: the per-user cap counts them.
CREATE INDEX ix_post_media_unattached_owner ON post_media (owner_id, created_at) WHERE post_id IS NULL;

-- Cleanup of expired unattached uploads, oldest first: WHERE post_id IS NULL AND created_at < ?.
CREATE INDEX ix_post_media_unattached_created ON post_media (created_at) WHERE post_id IS NULL;
