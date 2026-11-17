package com.socialapp.notification.infrastructure.web;

import com.socialapp.notification.application.NotificationView;
import com.socialapp.notification.domain.NotificationType;

import java.time.Instant;
import java.util.UUID;

/**
 * API shape of a notification (REST list items and the SSE {@code notification} event's data):
 * {@code {id, type, actorId, actorUsername, actorAvatarUrl, postId, commentId, snippet, read, createdAt}}.
 */
public record NotificationResponse(
        UUID id,
        NotificationType type,
        UUID actorId,
        String actorUsername,
        String actorAvatarUrl,
        UUID postId,
        UUID commentId,
        String snippet,
        boolean read,
        Instant createdAt
) {
    public static NotificationResponse from(NotificationView v) {
        return new NotificationResponse(v.id(), v.type(), v.actorId(), v.actorUsername(), v.actorAvatarUrl(),
                v.postId(), v.commentId(), v.snippet(), v.read(), v.createdAt());
    }
}
