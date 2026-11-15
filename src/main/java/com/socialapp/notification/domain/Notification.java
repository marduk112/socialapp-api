package com.socialapp.notification.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One notification per (recipient, type, actor, target) - the dedupe key (unique in V20).
 *
 * <p>State changes are "newest occurredAt wins" ({@code lastEventAt}), so Kafka redeliveries and out-of-order events
 * are harmless:
 * <ul>
 *   <li>an event not newer than the last applied one is ignored (a redelivery has the same occurredAt);</li>
 *   <li>a newer "added" for a visible notification (read or unread) changes nothing: no second notification for
 *       like/unlike/like, unfollow/follow or undo-repost/repost;</li>
 *   <li>a newer retraction (unlike, unfollow) hides the notification only while it is UNREAD (tombstone,
 *       {@code retracted}); a read one stays, since the recipient has already seen it and the list must not change
 *       under them;</li>
 *   <li>a newer "added" for a tombstone resurrects it as a new unread notification (same id, new createdAt) - so
 *       toggling yields at most one unread notification.</li>
 * </ul>
 * Timestamps are truncated to microseconds (PostgreSQL precision) so that a redelivered event compares equal to the
 * stored value.
 */
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class Notification {

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "recipient_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID recipientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 16)
    private NotificationType type;

    @Column(name = "actor_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID actorId;

    @Column(name = "target_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID targetId;

    @Column(name = "post_id", columnDefinition = "uuid")
    private UUID postId;

    @Column(name = "comment_id", columnDefinition = "uuid")
    private UUID commentId;

    @Column(name = "snippet", length = 160)
    private String snippet;

    @Column(name = "actor_username", length = 50)
    private String actorUsername;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_event_at", nullable = false)
    private Instant lastEventAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "retracted", nullable = false)
    private boolean retracted;

    /** The first event for a key: a visible unread notification, or a tombstone for a retraction seen first. */
    public static Notification from(NotificationIntent intent) {
        var n = new Notification();
        n.id = UUID.randomUUID();
        n.recipientId = intent.recipientId();
        n.type = intent.type();
        n.actorId = intent.actorId();
        n.targetId = intent.targetId();
        n.postId = intent.postId();
        n.commentId = intent.commentId();
        n.snippet = intent.snippet();
        n.actorUsername = limit(intent.actorUsername());
        n.createdAt = micros(intent.occurredAt());
        n.lastEventAt = n.createdAt;
        n.retracted = intent.retraction();
        return n;
    }

    /**
     * Applies a later event for the same key (see class comment).
     *
     * @return true if the notification has just become visible (resurrected) and should be pushed live
     */
    public boolean apply(NotificationIntent intent) {
        Instant at = micros(intent.occurredAt());
        if (!at.isAfter(lastEventAt)) return false; // redelivered or older than what was applied
        lastEventAt = at;
        if (intent.retraction()) {
            if (!retracted && readAt == null) retracted = true;
            return false;
        }
        if (!retracted) return false; // already notified: one notification per key
        retracted = false;
        readAt = null;
        createdAt = at;
        postId = intent.postId();
        commentId = intent.commentId();
        snippet = intent.snippet();
        if (intent.actorUsername() != null) actorUsername = limit(intent.actorUsername());
        return true;
    }

    public boolean isVisible() {
        return !retracted;
    }

    public boolean isRead() {
        return readAt != null;
    }

    static Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static String limit(String username) {
        if (username == null) return null;
        return username.length() <= 50 ? username : username.substring(0, 50);
    }
}
