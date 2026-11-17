package com.socialapp.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code followerId} stopped following {@code followeeId} (topic {@code social-events}, key followerId).
 * Emitted by SocialGraph only when a FOLLOWS relationship was actually deleted. Same {@code occurredAt}
 * ordering guarantee as {@link UserFollowedEvent}.
 */
public record UserUnfollowedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID followerId,
        UUID followeeId
) implements DomainEvent {

    @Override
    public String eventType() {
        return "UserUnfollowedEvent";
    }
}
