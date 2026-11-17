package com.socialapp.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A like/dislike was added to or removed from a post or a comment (topic {@code reaction-events}, key = postId).
 *
 * <p>Wire format: the first six fields are unchanged since before stage 3A. Stage 3A added (all optional for
 * readers; events written before deserialize with {@code target = POST} and nulls):
 * <ul>
 *   <li>{@code target}: POST (default) or COMMENT. Comment reactions are published since 3A; for them
 *       {@code postId} is the post the comment belongs to (so the key still groups a post's reactions);</li>
 *   <li>{@code commentId}: the reacted comment (COMMENT only);</li>
 *   <li>{@code targetOwnerId}: author of the reacted post/comment;</li>
 *   <li>{@code targetSnippet}: {@link Snippet#of} of the reacted text at the time of the reaction (null for a
 *       REPOST item, which has no text).</li>
 * </ul>
 * Consumers that only care about post reactions must check {@code target}.
 */
public record ReactionEvent(
        UUID eventId,
        Instant occurredAt,
        UUID postId,
        UUID userId,
        ReactionType reactionType,
        ReactionAction action,
        ReactionTarget target,
        UUID commentId,
        UUID targetOwnerId,
        String targetSnippet
) implements DomainEvent {

    public enum ReactionType { LIKE, DISLIKE }
    public enum ReactionAction { ADDED, REMOVED }
    public enum ReactionTarget { POST, COMMENT }

    public ReactionEvent {
        if (target == null) target = ReactionTarget.POST;
    }

    /** Pre-3A shape: a post reaction without owner/snippet. */
    public ReactionEvent(UUID eventId, Instant occurredAt, UUID postId, UUID userId, ReactionType reactionType,
                         ReactionAction action) {
        this(eventId, occurredAt, postId, userId, reactionType, action, ReactionTarget.POST, null, null, null);
    }

    /** Pre-3A shape with a fresh id and timestamp. */
    public ReactionEvent(UUID postId, UUID userId, ReactionType reactionType, ReactionAction action) {
        this(UUID.randomUUID(), Instant.now(), postId, userId, reactionType, action);
    }

    public static ReactionEvent onPost(UUID postId, UUID postAuthorId, String snippet, UUID userId,
                                       ReactionType type, ReactionAction action) {
        return new ReactionEvent(UUID.randomUUID(), Instant.now(), postId, userId, type, action, ReactionTarget.POST,
                null, postAuthorId, snippet);
    }

    public static ReactionEvent onComment(UUID postId, UUID commentId, UUID commentAuthorId, String snippet,
                                          UUID userId, ReactionType type, ReactionAction action) {
        return new ReactionEvent(UUID.randomUUID(), Instant.now(), postId, userId, type, action,
                ReactionTarget.COMMENT, commentId, commentAuthorId, snippet);
    }

    @Override
    public String eventType() {
        return "ReactionEvent";
    }
}
