package com.socialapp.shared.events;

import java.time.Instant;
import java.util.UUID;

public record UserUpdatedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID userId,
        String username,
        String avatarUrl,
        String bio
) implements DomainEvent {

    public UserUpdatedEvent(UUID userId, String username, String avatarUrl, String bio) {
        this(UUID.randomUUID(), Instant.now(), userId, username, avatarUrl, bio);
    }

    @Override
    public String eventType() {
        return "UserUpdatedEvent";
    }
}
