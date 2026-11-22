package com.socialapp.notification.application;

import com.socialapp.notification.domain.Notification;
import com.socialapp.notification.domain.NotificationIntent;
import com.socialapp.notification.domain.NotificationPolicy;
import com.socialapp.notification.domain.NotificationRepository;
import com.socialapp.shared.events.CommentCreatedEvent;
import com.socialapp.shared.events.PostCreatedEvent;
import com.socialapp.shared.events.ReactionEvent;
import com.socialapp.shared.events.UserFollowedEvent;
import com.socialapp.shared.events.UserUnfollowedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Turns events into notifications ({@link NotificationPolicy} decides who, {@link Notification} dedupes) and serves
 * the recipient's list, unread count and read marking.
 *
 * <p>Each {@code on(...)} handler is one transaction and returns the notifications that have just become visible
 * (new or resurrected), read back with the actor's display data, so the caller can push them live AFTER commit.
 * Events older than {@link NotificationCutoff#since()} are ignored (no historic notifications).
 *
 * <p>Stage 5A: an action of someone the recipient blocks, is blocked by or mutes creates no notification
 * ({@link NotificationPolicy#withoutSuppressed}, against {@link ActorRestrictions}); existing ones are hidden by the read
 * queries while the block/mute lasts and come back after an unblock/unmute.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    /** Upper bound of ids per mark-read request. */
    public static final int MAX_MARK_READ_IDS = 1000;

    private final NotificationRepository repository;
    private final NotificationQueries queries;
    private final NotificationCutoff cutoff;
    private final ActorRestrictions restrictions;

    // ── Event handlers ─────────────────────────────────────────────────────────

    @Transactional
    public List<NotificationView> on(UserFollowedEvent e) {
        if (isHistoric(e.occurredAt())) return List.of();
        return apply(NotificationPolicy.follow(e.followerId(), e.followeeId(), e.occurredAt(), true));
    }

    @Transactional
    public List<NotificationView> on(UserUnfollowedEvent e) {
        if (isHistoric(e.occurredAt())) return List.of();
        return apply(NotificationPolicy.follow(e.followerId(), e.followeeId(), e.occurredAt(), false));
    }

    @Transactional
    public List<NotificationView> on(PostCreatedEvent e) {
        if (isHistoric(e.occurredAt())) return List.of();
        return apply(NotificationPolicy.postCreated(new NotificationPolicy.PostFact(e.postId(), e.authorId(),
                e.authorUsername(), e.postType(), e.content(), e.originalPostId(), e.originalAuthorId(),
                e.originalContent(), e.mentionedUserIds(), e.occurredAt())));
    }

    @Transactional
    public List<NotificationView> on(CommentCreatedEvent e) {
        if (isHistoric(e.occurredAt())) return List.of();
        return apply(NotificationPolicy.commentCreated(new NotificationPolicy.CommentFact(e.commentId(), e.postId(),
                e.postAuthorId(), e.parentCommentId(), e.parentCommentAuthorId(), e.authorId(), e.authorUsername(),
                e.contentSnippet(), e.occurredAt())));
    }

    @Transactional
    public List<NotificationView> on(ReactionEvent e) {
        if (isHistoric(e.occurredAt())) return List.of();
        boolean onComment = e.target() == ReactionEvent.ReactionTarget.COMMENT;
        if (onComment && e.commentId() == null) return List.of();
        return apply(NotificationPolicy.reaction(new NotificationPolicy.ReactionFact(
                e.reactionType() == ReactionEvent.ReactionType.LIKE,
                e.action() == ReactionEvent.ReactionAction.ADDED,
                e.postId(), onComment ? e.commentId() : null, e.targetOwnerId(), e.userId(), e.targetSnippet(),
                e.occurredAt())));
    }

    private boolean isHistoric(Instant occurredAt) {
        return occurredAt == null || occurredAt.isBefore(cutoff.since());
    }

    private List<NotificationView> apply(List<NotificationIntent> decided) {
        if (decided.isEmpty()) return List.of();
        var intents = NotificationPolicy.withoutSuppressed(decided, restrictions::suppresses);
        if (intents.isEmpty()) return List.of();
        List<UUID> visible = new ArrayList<>();
        for (var intent : intents) {
            apply(intent).ifPresent(visible::add);
        }
        repository.flush(); // entity changes before any JDBC read (below, or by a caller sharing the transaction)
        if (visible.isEmpty()) return List.of();
        return queries.findVisibleByIds(visible);
    }

    /** @return the id of the notification if this intent made it visible (created or resurrected) */
    private Optional<UUID> apply(NotificationIntent intent) {
        var existing = repository.findByKeyForUpdate(intent.recipientId(), intent.type(), intent.actorId(),
                intent.targetId());
        if (existing.isEmpty()) {
            var fresh = Notification.from(intent);
            if (repository.insertIfAbsent(fresh) == 1) {
                log.debug("Notification {} {} -> {} (target {}){}", intent.type(), intent.actorId(),
                        intent.recipientId(), intent.targetId(), fresh.isVisible() ? "" : " retracted before seen");
                return fresh.isVisible() ? Optional.of(fresh.getId()) : Optional.empty();
            }
            // A concurrent delivery for the same key inserted it first: apply on top of that row.
            existing = repository.findByKeyForUpdate(intent.recipientId(), intent.type(), intent.actorId(),
                    intent.targetId());
            if (existing.isEmpty()) return Optional.empty();
        }
        var notification = existing.get();
        return notification.apply(intent) ? Optional.of(notification.getId()) : Optional.empty();
    }

    // ── Reads / commands of the recipient ──────────────────────────────────────

    /**
     * @param size page size; values above {@link #MAX_SIZE} are clamped to it
     * @throws IllegalArgumentException (400) if {@code size < 1}
     */
    @Transactional(readOnly = true)
    public NotificationPage page(UUID recipientId, NotificationCursor cursor, int size) {
        if (size < 1) throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
        int pageSize = Math.min(size, MAX_SIZE);
        var after = cursor == null ? NotificationCursor.START : cursor;
        var rows = queries.pageAfter(recipientId, after.createdAt(), after.id(), pageSize + 1);
        if (rows.size() <= pageSize) return new NotificationPage(List.copyOf(rows), null);
        var items = List.copyOf(rows.subList(0, pageSize));
        var last = items.get(pageSize - 1);
        return new NotificationPage(items, new NotificationCursor(last.createdAt(), last.id()));
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID recipientId) {
        return queries.countUnread(recipientId);
    }

    /**
     * Marks the recipient's notifications read: the given ones (ids of other users' or unknown notifications are
     * silently ignored, nothing leaks), or all of them when {@code ids} is null or empty. Idempotent.
     *
     * @throws IllegalArgumentException (400) more than {@link #MAX_MARK_READ_IDS} ids
     */
    @Transactional
    public void markRead(UUID recipientId, Collection<UUID> ids) {
        if (ids != null && ids.size() > MAX_MARK_READ_IDS) {
            throw new IllegalArgumentException("At most " + MAX_MARK_READ_IDS + " ids per request");
        }
        Set<UUID> wanted = ids == null || ids.isEmpty() ? null : Set.copyOf(ids.stream()
                .filter(java.util.Objects::nonNull).toList());
        if (wanted != null && wanted.isEmpty()) return;
        queries.markRead(recipientId, wanted, Instant.now());
    }
}
