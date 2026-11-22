package com.socialapp.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code muterId} muted {@code mutedId} (topic {@code social-events}, key muterId; stage 5A). One-directional and
 * silent: only the muter's views change, follows are untouched. Emitted only when the MUTES relationship was actually
 * created; {@code occurredAt} comes from the muter's logical clock (strictly increasing per muter in graph commit
 * order), so "newest occurredAt wins" per (muter, muted) converges to the graph's state.
 */
public record UserMutedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID muterId,
        UUID mutedId
) implements DomainEvent {

    @Override
    public String eventType() {
        return "UserMutedEvent";
    }
}
