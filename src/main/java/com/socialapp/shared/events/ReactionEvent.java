package com.socialapp.shared.events;

import java.time.Instant;
import java.util.UUID;

public record ReactionEvent(
        UUID eventId,
        Instant occurredAt,
        UUID postId,
        UUID userId,
        ReactionType reactionType,
        ReactionAction action
) implements DomainEvent {

    public enum ReactionType { LIKE, DISLIKE }
    public enum ReactionAction { ADDED, REMOVED }

    public ReactionEvent(UUID postId, UUID userId, ReactionType reactionType, ReactionAction action) {
        this(UUID.randomUUID(), Instant.now(), postId, userId, reactionType, action);
    }

    @Override
    public String eventType() {
        return "ReactionEvent";
    }
}
