-- Progress and completion markers of one-time data backfills that the application runs itself at startup
-- (com.socialapp.config.backfill: StartupBackfillRunner, BackfillStateRepository). Used for backfills of
-- PostgreSQL data that SQL alone cannot compute (e.g. the hashtag/mention index of posts written before V17:
-- the parsers need \p{L}/\p{N} Unicode classes). One row per backfill:
--   cursor_value  keyset position of the last fully processed item (format owned by the backfill), NULL = start
--   processed     items handled so far (informational)
--   completed_at  set once; a backfill with completed_at IS NOT NULL never runs again.
--                 Re-run: UPDATE backfill_state SET completed_at = NULL, cursor_value = NULL WHERE name = '...'
--                 (or DELETE the row) and restart the application.
CREATE TABLE backfill_state (
    name         VARCHAR(100) NOT NULL,
    cursor_value VARCHAR(200),
    processed    BIGINT       NOT NULL DEFAULT 0,
    started_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT pk_backfill_state PRIMARY KEY (name)
);
