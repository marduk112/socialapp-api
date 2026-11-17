package com.socialapp.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code followerId} started following {@code followeeId} (topic {@code social-events}, key followerId).
 * Emitted by SocialGraph only when the FOLLOWS relationship was actually created.
 *
 * <p>{@code occurredAt} is strictly increasing per follower in graph commit order (it is assigned under
 * the follower node's write lock, see {@code UserNodeRepository#follow}), so a consumer that keeps the
 * event with the newest {@code occurredAt} per (follower, followee) ends in the same state as the graph,
 * whatever the delivery order or number of redeliveries.
 */
public record UserFollowedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID followerId,
        UUID followeeId
) implements DomainEvent {

    @Override
    public String eventType() {
        return "UserFollowedEvent";
    }
}
