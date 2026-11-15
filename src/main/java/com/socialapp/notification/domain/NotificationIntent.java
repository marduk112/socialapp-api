package com.socialapp.notification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What one event means for one recipient, decided by {@link NotificationPolicy}: create (or keep) the notification
 * identified by (recipient, type, actor, target), or retract it ({@code retraction}: an unlike / unfollow).
 *
 * @param targetId      dedupe target, see {@code V20__notifications.sql}
 * @param postId        the post to open (null for FOLLOW)
 * @param commentId     COMMENT / REPLY / LIKE on a comment
 * @param snippet       already shortened ({@code Snippet.of}); may be null
 * @param actorUsername the actor's name as carried by the event (fallback until the profile is known); may be null
 */
public record NotificationIntent(
        UUID recipientId,
        NotificationType type,
        UUID actorId,
        UUID targetId,
        UUID postId,
        UUID commentId,
        String snippet,
        String actorUsername,
        Instant occurredAt,
        boolean retraction
) {
    public NotificationIntent {
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (recipientId.equals(actorId)) throw new IllegalArgumentException("A user is never notified of own actions");
    }
}
