package com.socialapp.shared.events;

import java.time.Instant;
import java.util.UUID;

public record PostCreatedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID postId,
        UUID authorId,
        String authorUsername,
        String content,
        String imageUrl
) implements DomainEvent {

    public PostCreatedEvent(UUID postId, UUID authorId, String authorUsername, String content, String imageUrl) {
        this(UUID.randomUUID(), Instant.now(), postId, authorId, authorUsername, content, imageUrl);
    }

    @Override
    public String eventType() {
        return "PostCreatedEvent";
    }
}
