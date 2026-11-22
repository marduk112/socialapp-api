package com.socialapp.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code muterId} unmuted {@code mutedId} (topic {@code social-events}, key muterId; stage 5A). Emitted only when a
 * MUTES relationship was actually deleted. Same {@code occurredAt} ordering guarantee as {@link UserMutedEvent}.
 */
public record UserUnmutedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID muterId,
        UUID mutedId
) implements DomainEvent {

    @Override
    public String eventType() {
        return "UserUnmutedEvent";
    }
}
