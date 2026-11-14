package com.socialapp.feed.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PostRepository extends JpaRepository<Post, UUID> {
    List<Post> findByAuthorIdOrderByCreatedAtDesc(UUID authorId);

    /**
     * Loads the post with a row lock (SELECT ... FOR UPDATE) held until the transaction ends.
     * Used by every operation that changes counters, so they are serialised per post.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Post p where p.id = :id")
    Optional<Post> findByIdForUpdate(@Param("id") UUID id);

    // ── Timeline (keyset pagination) ────────────────────────────────────────
    // Order: created_at DESC, id DESC (id breaks ties of equal created_at, so the order is total and stable).
    // A page starts strictly after the key (createdAt, id) of the previous page's last post; the first page
    // passes a key greater than any post. The row-value comparison is done by Postgres (uuid ordering of
    // the DB, not java.util.UUID#compareTo) and matches indexes from V16.

    /** Every post, newest first, strictly after {@code (createdAt, id)}. */
    @Query(value = """
            SELECT p.* FROM posts p
            WHERE (p.created_at, p.id) < (CAST(:createdAt AS timestamptz), CAST(:id AS uuid))
            ORDER BY p.created_at DESC, p.id DESC
            LIMIT :limit""", nativeQuery = true)
    List<Post> findPageAfter(@Param("createdAt") Instant createdAt, @Param("id") UUID id,
                             @Param("limit") int limit);

    /**
     * Posts by {@code viewerId} or by users {@code viewerId} actively follows (Feed's {@code follows}
     * projection), newest first, strictly after {@code (createdAt, id)}.
     */
    @Query(value = """
            SELECT p.* FROM posts p
            WHERE p.author_id IN (SELECT f.followee_id FROM follows f
                                  WHERE f.follower_id = CAST(:viewerId AS uuid) AND f.active
                                  UNION ALL
                                  SELECT CAST(:viewerId AS uuid))
              AND (p.created_at, p.id) < (CAST(:createdAt AS timestamptz), CAST(:id AS uuid))
            ORDER BY p.created_at DESC, p.id DESC
            LIMIT :limit""", nativeQuery = true)
    List<Post> findFollowingPageAfter(@Param("viewerId") UUID viewerId, @Param("createdAt") Instant createdAt,
                                      @Param("id") UUID id, @Param("limit") int limit);

    /**
     * Posts carrying hashtag {@code tag} (normalised), newest first, strictly after {@code (createdAt, id)}.
     * Pages over {@code post_hashtags (tag, created_at DESC, post_id DESC)} (V17); its {@code created_at} is a
     * copy of the post's, so the order and cursor are the same as for {@link #findPageAfter}.
     */
    @Query(value = """
            SELECT p.* FROM post_hashtags h JOIN posts p ON p.id = h.post_id
            WHERE h.tag = :tag AND p.type <> 'REPOST'
              AND (h.created_at, h.post_id) < (CAST(:createdAt AS timestamptz), CAST(:id AS uuid))
            ORDER BY h.created_at DESC, h.post_id DESC
            LIMIT :limit""", nativeQuery = true)
    List<Post> findHashtagPageAfter(@Param("tag") String tag, @Param("createdAt") Instant createdAt,
                                    @Param("id") UUID id, @Param("limit") int limit);

    // ── Reposts / quotes (stage 2C) ─────────────────────────────────────────

    /** The REPOST of {@code originalPostId} by {@code authorId}, if any (unique, V18 partial index). */
    @Query("select p from Post p where p.authorId = :authorId and p.originalPostId = :originalPostId "
            + "and p.type = com.socialapp.feed.domain.PostType.REPOST")
    Optional<Post> findRepost(@Param("authorId") UUID authorId, @Param("originalPostId") UUID originalPostId);

    /** Deletes the REPOST of {@code originalPostId} by {@code authorId}; returns the number of rows (0 or 1). */
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM posts WHERE author_id = :authorId AND original_post_id = :originalPostId "
            + "AND type = 'REPOST'", nativeQuery = true)
    int deleteRepost(@Param("authorId") UUID authorId, @Param("originalPostId") UUID originalPostId);

    /** Deletes every REPOST of {@code originalPostId} (their comments/reactions/index rows cascade by FK). */
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM posts WHERE original_post_id = :originalPostId AND type = 'REPOST'",
            nativeQuery = true)
    int deleteRepostsOf(@Param("originalPostId") UUID originalPostId);

    /**
     * Clears the snapshot of {@code originalPostId} kept by its QUOTEs ({@code original_content},
     * {@code original_author_username}) before the original is deleted; the FK then nulls their
     * {@code original_post_id}, so they show "original unavailable".
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE posts SET original_content = NULL, original_author_username = NULL "
            + "WHERE original_post_id = :originalPostId AND type = 'QUOTE'", nativeQuery = true)
    int detachQuotesOf(@Param("originalPostId") UUID originalPostId);

    /**
     * {@code [originalPostId (UUID), type (PostType), count (Long)]} of REPOST/QUOTE rows pointing at each of
     * {@code postIds}: ONE grouped query, index-only on {@code (original_post_id, type)} (V18). Posts nobody
     * reposted or quoted are absent.
     */
    @Query("select p.originalPostId, p.type, count(p) from Post p where p.originalPostId in :postIds "
            + "group by p.originalPostId, p.type")
    List<Object[]> countRepostsAndQuotes(@Param("postIds") Collection<UUID> postIds);

    /** Which of {@code postIds} {@code viewerId} has reposted: ONE query on the V18 partial unique index. */
    @Query("select p.originalPostId from Post p where p.authorId = :viewerId "
            + "and p.type = com.socialapp.feed.domain.PostType.REPOST and p.originalPostId in :postIds")
    List<UUID> findRepostedOriginalIds(@Param("viewerId") UUID viewerId, @Param("postIds") Collection<UUID> postIds);

    // ── Legacy hashtag/mention backfill (PostTextIndexBackfill) ─────────────

    /**
     * Ids of up to {@code limit} posts after {@code afterId} (keyset on the primary key, Postgres uuid order) that
     * have their own text (not a REPOST) and no {@code post_hashtags}/{@code post_mentions} rows yet, in id order.
     */
    @Query(value = """
            SELECT p.id FROM posts p
            WHERE p.id > CAST(:afterId AS uuid) AND p.type <> 'REPOST'
              AND NOT EXISTS (SELECT 1 FROM post_hashtags h WHERE h.post_id = p.id)
              AND NOT EXISTS (SELECT 1 FROM post_mentions m WHERE m.post_id = p.id)
            ORDER BY p.id
            LIMIT :limit""", nativeQuery = true)
    List<UUID> findTextIndexBackfillCandidates(@Param("afterId") UUID afterId, @Param("limit") int limit);

    /**
     * Locks the given posts (non-REPOST) for the backfill transaction, SKIPPING rows locked by someone else
     * (an edit, reaction, comment, delete...): the backfill never waits for a row lock while holding others, so it
     * can never deadlock with live traffic. Skipped rows are retried by the caller one at a time.
     */
    @Query(value = """
            SELECT p.* FROM posts p
            WHERE p.id IN (:ids) AND p.type <> 'REPOST'
            ORDER BY p.id
            FOR UPDATE OF p SKIP LOCKED""", nativeQuery = true)
    List<Post> lockForTextIndexBackfill(@Param("ids") Collection<UUID> ids);

    /** Refreshes the denormalised author username after a rename (idempotent). */
    @Modifying
    @Query("update Post p set p.authorUsername = :username "
            + "where p.authorId = :authorId and p.authorUsername <> :username")
    int renameAuthor(@Param("authorId") UUID authorId, @Param("username") String username);

    /** Refreshes {@code original_author_username} of shares of this author's posts (idempotent). */
    @Modifying
    @Query(value = "UPDATE posts SET original_author_username = :username "
            + "WHERE original_post_id IN (SELECT id FROM posts WHERE author_id = :authorId) "
            + "AND original_author_username IS DISTINCT FROM :username", nativeQuery = true)
    int renameOriginalAuthor(@Param("authorId") UUID authorId, @Param("username") String username);
}
