CREATE TABLE posts (
    id              UUID        NOT NULL PRIMARY KEY,
    author_id       UUID        NOT NULL,
    author_username VARCHAR(50) NOT NULL,
    content         TEXT        NOT NULL,
    image_url       TEXT,
    likes_count     BIGINT      NOT NULL DEFAULT 0,
    dislikes_count  BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_posts_author FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE post_reactions (
    id            UUID        NOT NULL PRIMARY KEY,
    post_id       UUID        NOT NULL,
    user_id       UUID        NOT NULL,
    reaction_type VARCHAR(10) NOT NULL CHECK (reaction_type IN ('LIKE', 'DISLIKE')),
    created_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_post_user_reaction UNIQUE (post_id, user_id, reaction_type),
    CONSTRAINT fk_reactions_post FOREIGN KEY (post_id) REFERENCES posts(id) ON DELETE CASCADE
);

CREATE INDEX idx_posts_author     ON posts (author_id, created_at DESC);
CREATE INDEX idx_reactions_post   ON post_reactions (post_id);
CREATE INDEX idx_reactions_user   ON post_reactions (user_id);
