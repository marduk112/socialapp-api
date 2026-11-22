package com.socialapp.feed.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommentRepository extends JpaRepository<Comment, UUID> {
    /**
     * One page of a post's comments (oldest first) without those whose author blocks or is blocked by
     * {@code viewerId} (stage 5A; mutes do not hide comments). Filtered in SQL before LIMIT/OFFSET, so pages are exact.
     */
    @Query(value = """
            SELECT c.* FROM comments c
            WHERE c.post_id = :postId
              AND c.author_id NOT IN """ + PostRepository.BLOCKED_WITH_VIEWER + """

            ORDER BY c.created_at ASC, c.id ASC
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<Comment> findVisiblePage(@Param("postId") UUID postId, @Param("viewerId") UUID viewerId,
                                  @Param("limit") int limit, @Param("offset") long offset);
    long countByPostId(UUID postId);

    /** Loads the comment with a row lock held until the transaction ends (serialises reactions). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Comment c where c.id = :id")
    Optional<Comment> findByIdForUpdate(@Param("id") UUID id);

    /** Refreshes the denormalised author username after a rename (idempotent). */
    @Modifying
    @Query("update Comment c set c.authorUsername = :username "
            + "where c.authorId = :authorId and c.authorUsername <> :username")
    int renameAuthor(@Param("authorId") UUID authorId, @Param("username") String username);
}
