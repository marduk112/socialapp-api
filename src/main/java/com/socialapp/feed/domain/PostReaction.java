package com.socialapp.feed.domain;

import com.socialapp.shared.events.ReactionEvent;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks which user reacted to which post to prevent duplicate reactions.
 */
@Entity
@Table(
    name = "post_reactions",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_post_user_reaction",
        columnNames = {"post_id", "user_id", "reaction_type"}
    )
)
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class PostReaction {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "post_id", nullable = false, columnDefinition = "uuid")
    private UUID postId;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reaction_type", nullable = false)
    private ReactionEvent.ReactionType reactionType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static PostReaction of(UUID postId, UUID userId, ReactionEvent.ReactionType type) {
        var r = new PostReaction();
        r.id = UUID.randomUUID();
        r.postId = postId;
        r.userId = userId;
        r.reactionType = type;
        r.createdAt = Instant.now();
        return r;
    }
}
