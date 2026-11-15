package com.socialapp.notification.application;

import com.socialapp.notification.domain.NotificationType;

import java.time.Instant;
import java.util.UUID;

/**
 * A notification as the recipient sees it. {@code actorUsername} is the actor's current name from the profile
 * projection, else the name carried by the event, else {@link #UNKNOWN_ACTOR} (never null); it is resolved at read
 * time, so a profile that arrives later refreshes existing notifications.
 */
public record NotificationView(
        UUID id,
        NotificationType type,
        UUID actorId,
        String actorUsername,
        String actorAvatarUrl,
        UUID postId,
        UUID commentId,
        String snippet,
        boolean read,
        Instant createdAt,
        UUID recipientId
) {
    /** Placeholder name for an actor whose profile Notification does not know (yet). */
    public static final String UNKNOWN_ACTOR = "unknown";

    public NotificationView {
        if (actorUsername == null || actorUsername.isBlank()) actorUsername = UNKNOWN_ACTOR;
    }
}
