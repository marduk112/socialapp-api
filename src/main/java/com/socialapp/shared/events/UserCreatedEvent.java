package com.socialapp.shared.events;

import java.time.Instant;
import java.util.UUID;

public record UserCreatedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID userId,
        String username,
        String email,
        String avatarUrl
) implements DomainEvent {

    public UserCreatedEvent(UUID userId, String username, String email, String avatarUrl) {
        this(UUID.randomUUID(), Instant.now(), userId, username, email, avatarUrl);
    }

    @Override
    public String eventType() {
        return "UserCreatedEvent";
    }
}
