package com.socialapp.feed.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface BookmarkRepository extends JpaRepository<Bookmark, Bookmark.Key> {

    /**
     * Bookmarks {@code postId} for {@code userId}; idempotent and race-safe (ON CONFLICT DO NOTHING). Inserting
     * from {@code posts} makes a concurrently deleted post a no-op instead of an FK violation.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO bookmarks (user_id, post_id, created_at) "
            + "SELECT CAST(:userId AS uuid), p.id, now() FROM posts p WHERE p.id = CAST(:postId AS uuid) "
            + "ON CONFLICT (user_id, post_id) DO NOTHING", nativeQuery = true)
    int add(@Param("userId") UUID userId, @Param("postId") UUID postId);

    /** Removes the bookmark; 0 or 1 rows. */
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM bookmarks WHERE user_id = :userId AND post_id = :postId", nativeQuery = true)
    int remove(@Param("userId") UUID userId, @Param("postId") UUID postId);

    /** Which of {@code postIds} {@code userId} has bookmarked: ONE query on the primary key. */
    @Query("select b.postId from Bookmark b where b.userId = :userId and b.postId in :postIds")
    List<UUID> findBookmarkedPostIds(@Param("userId") UUID userId, @Param("postIds") Collection<UUID> postIds);

    /**
     * The user's bookmarks, newest first (created_at DESC, post_id DESC), strictly after
     * {@code (createdAt, postId)}; index V21.
     */
    @Query(value = """
            SELECT b.* FROM bookmarks b
            WHERE b.user_id = :userId
              AND (b.created_at, b.post_id) < (CAST(:createdAt AS timestamptz), CAST(:postId AS uuid))
            ORDER BY b.created_at DESC, b.post_id DESC
            LIMIT :limit""", nativeQuery = true)
    List<Bookmark> findPageAfter(@Param("userId") UUID userId, @Param("createdAt") Instant createdAt,
                                 @Param("postId") UUID postId, @Param("limit") int limit);
}
