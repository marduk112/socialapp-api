package com.socialapp.notification.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Write side of notifications (the read side is {@code application.NotificationQueries}). */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** The notification with this dedupe key, row-locked until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Notification n where n.recipientId = :recipientId and n.type = :type "
            + "and n.actorId = :actorId and n.targetId = :targetId")
    Optional<Notification> findByKeyForUpdate(@Param("recipientId") UUID recipientId,
                                              @Param("type") NotificationType type,
                                              @Param("actorId") UUID actorId,
                                              @Param("targetId") UUID targetId);

    /**
     * Inserts {@code n} unless a row with its dedupe key exists (a concurrent delivery of an event for the same key
     * waits for the other transaction, then inserts nothing).
     *
     * @return 1 if inserted, 0 if the key already existed
     */
    @Modifying
    @Query(value = """
            INSERT INTO notifications (id, recipient_id, type, actor_id, target_id, post_id, comment_id, snippet,
                                       actor_username, created_at, last_event_at, read_at, retracted)
            VALUES (:id, :recipientId, :type, :actorId, :targetId, :postId, :commentId, :snippet, :actorUsername,
                    CAST(:createdAt AS timestamptz), CAST(:createdAt AS timestamptz), NULL, :retracted)
            ON CONFLICT ON CONSTRAINT ux_notifications_key DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("recipientId") UUID recipientId, @Param("type") String type,
                       @Param("actorId") UUID actorId, @Param("targetId") UUID targetId,
                       @Param("postId") UUID postId, @Param("commentId") UUID commentId,
                       @Param("snippet") String snippet, @Param("actorUsername") String actorUsername,
                       @Param("createdAt") Instant createdAt, @Param("retracted") boolean retracted);

    default int insertIfAbsent(Notification n) {
        return insertIfAbsent(n.getId(), n.getRecipientId(), n.getType().name(), n.getActorId(), n.getTargetId(),
                n.getPostId(), n.getCommentId(), n.getSnippet(), n.getActorUsername(), n.getCreatedAt(),
                n.isRetracted());
    }
}
