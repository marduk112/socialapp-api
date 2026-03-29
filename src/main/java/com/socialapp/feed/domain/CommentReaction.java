package com.socialapp.feed.domain;

import com.socialapp.shared.events.ReactionEvent;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "comment_reactions",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_comment_user_reaction",
        columnNames = {"comment_id", "user_id", "reaction_type"}
    )
)
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class CommentReaction {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "comment_id", nullable = false, columnDefinition = "uuid")
    private UUID commentId;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reaction_type", nullable = false)
    private ReactionEvent.ReactionType reactionType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static CommentReaction of(UUID commentId, UUID userId, ReactionEvent.ReactionType type) {
        var r = new CommentReaction();
        r.id = UUID.randomUUID();
        r.commentId = commentId;
        r.userId = userId;
        r.reactionType = type;
        r.createdAt = Instant.now();
        return r;
    }
}
