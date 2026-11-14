package com.socialapp.feed.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CommentReactionRepository extends JpaRepository<CommentReaction, UUID> {
    /** All reactions (normally at most one) of a user on a comment. */
    List<CommentReaction> findByCommentIdAndUserId(UUID commentId, UUID userId);
}
