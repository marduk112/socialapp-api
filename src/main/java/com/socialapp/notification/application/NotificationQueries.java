package com.socialapp.notification.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Read side (and bulk read-marking) of notifications; every query is scoped to one recipient or explicit ids. */
public interface NotificationQueries {

    /** Visible notifications of {@code recipientId} strictly after {@code (createdAt, id)}, newest first, with ONE query. */
    List<NotificationView> pageAfter(UUID recipientId, Instant createdAt, UUID id, int limit);

    /** Visible notifications with these ids (any recipient: callers pass ids they just wrote), ONE query. */
    List<NotificationView> findVisibleByIds(Collection<UUID> ids);

    long countUnread(UUID recipientId);

    /** Marks the recipient's unread visible notifications read: all of them when {@code ids} is null. */
    int markRead(UUID recipientId, Collection<UUID> ids, Instant readAt);
}
