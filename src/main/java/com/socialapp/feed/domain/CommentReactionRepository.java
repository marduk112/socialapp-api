package com.socialapp.feed.domain;

import com.socialapp.shared.events.ReactionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommentReactionRepository extends JpaRepository<CommentReaction, UUID> {
    Optional<CommentReaction> findByCommentIdAndUserIdAndReactionType(
            UUID commentId, UUID userId, ReactionEvent.ReactionType reactionType);
    boolean existsByCommentIdAndUserIdAndReactionType(
            UUID commentId, UUID userId, ReactionEvent.ReactionType reactionType);
}
