ALTER TABLE comments
    ADD COLUMN likes_count       INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN dislikes_count    INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN parent_comment_id UUID    REFERENCES comments(id) ON DELETE CASCADE;

CREATE INDEX idx_comments_parent ON comments (parent_comment_id);

CREATE TABLE comment_reactions (
    id              UUID        NOT NULL PRIMARY KEY,
    comment_id      UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    reaction_type   VARCHAR(10) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_comment_user_reaction UNIQUE (comment_id, user_id, reaction_type),
    CONSTRAINT fk_comment_reactions_comment FOREIGN KEY (comment_id) REFERENCES comments(id) ON DELETE CASCADE,
    CONSTRAINT fk_comment_reactions_user    FOREIGN KEY (user_id)    REFERENCES users(id)    ON DELETE CASCADE
);

CREATE INDEX idx_comment_reactions_comment ON comment_reactions (comment_id);
