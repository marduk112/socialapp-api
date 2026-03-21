-- Add updatedAt and sharing support to posts
ALTER TABLE posts ADD COLUMN updated_at TIMESTAMPTZ;
ALTER TABLE posts ADD COLUMN original_post_id UUID REFERENCES posts(id) ON DELETE SET NULL;
ALTER TABLE posts ADD COLUMN original_author_username VARCHAR(50);
ALTER TABLE posts ADD COLUMN comments_count BIGINT NOT NULL DEFAULT 0;

UPDATE posts SET updated_at = created_at WHERE updated_at IS NULL;

-- Comments table
CREATE TABLE comments (
    id              UUID        NOT NULL PRIMARY KEY,
    post_id         UUID        NOT NULL,
    author_id       UUID        NOT NULL,
    author_username VARCHAR(50) NOT NULL,
    content         TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ,
    CONSTRAINT fk_comments_post   FOREIGN KEY (post_id)   REFERENCES posts(id) ON DELETE CASCADE,
    CONSTRAINT fk_comments_author FOREIGN KEY (author_id)  REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_comments_post ON comments (post_id, created_at);
CREATE INDEX idx_comments_author ON comments (author_id);
CREATE INDEX idx_posts_original ON posts (original_post_id);
