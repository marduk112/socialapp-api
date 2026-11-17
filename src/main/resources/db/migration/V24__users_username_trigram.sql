-- Stage 4B: people search (GET /search/users) matches usernames case-insensitively anywhere in the name.
-- pg_trgm is a "trusted" extension (PostgreSQL 13+): creating it needs only CREATE on the database.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Serves: lower(username) LIKE '%term%' (terms of >= 3 characters; shorter ones fall back to a scan of a small table).
CREATE INDEX ix_users_username_trgm ON users USING GIN (lower(username) gin_trgm_ops);
