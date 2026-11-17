-- Feed must not depend on UserManagement's schema: drop foreign keys from Feed tables to users(id)
-- (created in V2, V3 and V9). Author/user ids in Feed are references by identity only, kept in
-- sync through user-events. Intra-Feed FKs (posts, comments, parent comments) are kept.
ALTER TABLE posts             DROP CONSTRAINT IF EXISTS fk_posts_author;
ALTER TABLE comments          DROP CONSTRAINT IF EXISTS fk_comments_author;
ALTER TABLE comment_reactions DROP CONSTRAINT IF EXISTS fk_comment_reactions_user;
