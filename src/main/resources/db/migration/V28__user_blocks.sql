-- Stage 5A: UserManagement's projection of blocks (from social-events), used only by people search: a user never
-- finds someone they block or who blocks them. Same rules as V26 (tombstones, newest occurredAt wins, no FKs).
CREATE TABLE user_blocks (
    blocker_id    UUID        NOT NULL,
    blocked_id    UUID        NOT NULL,
    active        BOOLEAN     NOT NULL,
    last_event_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_user_blocks PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT ck_user_blocks_not_self CHECK (blocker_id <> blocked_id)
);
CREATE INDEX ix_user_blocks_blocked_active ON user_blocks (blocked_id, blocker_id) WHERE active;
