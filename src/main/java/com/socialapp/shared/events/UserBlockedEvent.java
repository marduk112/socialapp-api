package com.socialapp.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code blockerId} blocked {@code blockedId} (topic {@code social-events}, key blockerId; stage 5A). Emitted by
 * SocialGraph only when the BLOCKS relationship was actually created. A block is mutual in effect (each side's content
 * is hidden from the other, follows in both directions are removed - each removal is its own
 * {@link UserUnfollowedEvent} - and new follows are refused), but it is recorded once, by its blocker.
 *
 * <p>{@code occurredAt} comes from the blocker's logical clock (the same per-user clock as follows), so it is strictly
 * increasing per blocker in graph commit order: a consumer that keeps the event with the newest {@code occurredAt} per
 * (blocker, blocked) ends in the graph's state whatever the delivery order.
 */
public record UserBlockedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID blockerId,
        UUID blockedId
) implements DomainEvent {

    @Override
    public String eventType() {
        return "UserBlockedEvent";
    }
}
