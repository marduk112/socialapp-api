package com.socialapp.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code blockerId} lifted its block of {@code blockedId} (topic {@code social-events}, key blockerId; stage 5A).
 * Emitted only when a BLOCKS relationship was actually deleted; follows removed by the block are not restored. Same
 * {@code occurredAt} ordering guarantee as {@link UserBlockedEvent}.
 */
public record UserUnblockedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID blockerId,
        UUID blockedId
) implements DomainEvent {

    @Override
    public String eventType() {
        return "UserUnblockedEvent";
    }
}
