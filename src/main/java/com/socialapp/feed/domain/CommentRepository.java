package com.socialapp.feed.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommentRepository extends JpaRepository<Comment, UUID> {
    Page<Comment> findByPostIdOrderByCreatedAtAsc(UUID postId, Pageable pageable);
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
