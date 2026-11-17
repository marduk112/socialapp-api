package com.socialapp.notification.domain;

import com.socialapp.shared.events.Snippet;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure rules deciding who is notified of an action (no I/O).
 *
 * <ul>
 *   <li>Never notify yourself.</li>
 *   <li>One action notifies each recipient at most once, by precedence: REPLY &gt; COMMENT (a reply to your comment on
 *       your own post is only a REPLY), QUOTE / REPOST &gt; MENTION (a quote that @mentions the quoted author is only
 *       a QUOTE).</li>
 *   <li>Dislikes never notify; an unlike / unfollow becomes a retraction of the LIKE / FOLLOW.</li>
 *   <li>Facts with missing ids (impossible for events produced by this system) yield nothing.</li>
 * </ul>
 * Deduplication across events (redelivery, like/unlike toggling, re-reposting) is the job of {@link Notification}.
 */
public final class NotificationPolicy {

    private NotificationPolicy() {
    }

    /** A follow ({@code followed = true}) or unfollow of {@code followee} by {@code follower}. */
    public static List<NotificationIntent> follow(UUID follower, UUID followee, Instant occurredAt, boolean followed) {
        var c = new Collector(follower, null, occurredAt);
        c.add(followee, NotificationType.FOLLOW, followee, null, null, null, !followed);
        return c.result();
    }

    /** A post, repost or quote was created. */
    public record PostFact(UUID postId, UUID authorId, String authorUsername, String postType, String content,
                           UUID originalPostId, UUID originalAuthorId, String originalContent,
                           List<UUID> mentionedUserIds, Instant occurredAt) {}

    public static List<NotificationIntent> postCreated(PostFact f) {
        var c = new Collector(f.authorId(), f.authorUsername(), f.occurredAt());
        if (f.postId() == null) return List.of();
        String type = f.postType() == null ? "POST" : f.postType();
        switch (type) {
            // REPOST: the target is the original, so undo + repost again never notifies twice; no own text.
            case "REPOST" -> {
                c.add(f.originalAuthorId(), NotificationType.REPOST, f.originalPostId(), f.originalPostId(), null,
                        f.originalContent(), false);
                return c.result();
            }
            case "QUOTE" -> c.add(f.originalAuthorId(), NotificationType.QUOTE, f.postId(), f.postId(), null,
                    f.content(), false);
            default -> { /* plain post: mentions only */ }
        }
        if (f.mentionedUserIds() != null) {
            for (UUID mentioned : f.mentionedUserIds()) {
                c.add(mentioned, NotificationType.MENTION, f.postId(), f.postId(), null, f.content(), false);
            }
        }
        return c.result();
    }

    /** A comment or (with a parent) a reply was created. */
    public record CommentFact(UUID commentId, UUID postId, UUID postAuthorId, UUID parentCommentId,
                              UUID parentCommentAuthorId, UUID authorId, String authorUsername, String snippet,
                              Instant occurredAt) {}

    public static List<NotificationIntent> commentCreated(CommentFact f) {
        var c = new Collector(f.authorId(), f.authorUsername(), f.occurredAt());
        if (f.commentId() == null || f.postId() == null) return List.of();
        if (f.parentCommentId() != null) {
            c.add(f.parentCommentAuthorId(), NotificationType.REPLY, f.commentId(), f.postId(), f.commentId(),
                    f.snippet(), false);
        }
        c.add(f.postAuthorId(), NotificationType.COMMENT, f.commentId(), f.postId(), f.commentId(), f.snippet(), false);
        return c.result();
    }

    /** A like/dislike was added or removed on a post ({@code commentId == null}) or a comment. */
    public record ReactionFact(boolean like, boolean added, UUID postId, UUID commentId, UUID targetOwnerId,
                               UUID actorId, String snippet, Instant occurredAt) {}

    public static List<NotificationIntent> reaction(ReactionFact f) {
        if (!f.like()) return List.of(); // dislikes never notify
        var c = new Collector(f.actorId(), null, f.occurredAt());
        UUID target = f.commentId() != null ? f.commentId() : f.postId();
        if (f.postId() == null) return List.of();
        c.add(f.targetOwnerId(), NotificationType.LIKE, target, f.postId(), f.commentId(), f.snippet(), !f.added());
        return c.result();
    }

    /** Collects at most one intent per recipient, in precedence order (first added wins), skipping the actor. */
    private static final class Collector {
        private final UUID actor;
        private final String actorUsername;
        private final Instant occurredAt;
        private final Map<UUID, NotificationIntent> byRecipient = new LinkedHashMap<>();

        Collector(UUID actor, String actorUsername, Instant occurredAt) {
            this.actor = actor;
            this.actorUsername = actorUsername == null || actorUsername.isBlank() ? null : actorUsername;
            this.occurredAt = occurredAt;
        }

        void add(UUID recipient, NotificationType type, UUID target, UUID postId, UUID commentId, String text,
                 boolean retraction) {
            if (actor == null || occurredAt == null || recipient == null || target == null) return;
            if (recipient.equals(actor)) return;
            byRecipient.putIfAbsent(recipient, new NotificationIntent(recipient, type, actor, target, postId,
                    commentId, Snippet.of(text), actorUsername, occurredAt, retraction));
        }

        List<NotificationIntent> result() {
            return new ArrayList<>(byRecipient.values());
        }
    }
}
