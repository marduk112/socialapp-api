package com.socialapp.feed.domain;

import com.socialapp.shared.events.ReactionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PostReactionRepository extends JpaRepository<PostReaction, UUID> {
    Optional<PostReaction> findByPostIdAndUserIdAndReactionType(
            UUID postId, UUID userId, ReactionEvent.ReactionType reactionType);
    boolean existsByPostIdAndUserIdAndReactionType(
            UUID postId, UUID userId, ReactionEvent.ReactionType reactionType);
}
