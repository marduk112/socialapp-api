package com.socialapp.feed.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface PostReactionRepository extends JpaRepository<PostReaction, UUID> {
    /** All reactions (normally at most one) of a user on a post. */
    List<PostReaction> findByPostIdAndUserId(UUID postId, UUID userId);

    /** The reactions of {@code userId} on any of {@code postIds}, with ONE query (read model). */
    List<PostReaction> findByUserIdAndPostIdIn(UUID userId, Collection<UUID> postIds);
}
