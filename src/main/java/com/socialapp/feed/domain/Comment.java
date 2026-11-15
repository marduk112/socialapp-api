package com.socialapp.feed.domain;

import com.socialapp.shared.domain.AggregateRoot;
import com.socialapp.shared.domain.ForbiddenOperationException;
import com.socialapp.shared.events.CommentCreatedEvent;
import com.socialapp.shared.events.ReactionEvent;
import com.socialapp.shared.events.Snippet;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Same concurrency model as {@link Post}: counters change only under a row lock
 * ({@link CommentRepository#findByIdForUpdate}); {@code @DynamicUpdate} keeps other writers
 * (edit, author rename) from overwriting them.
 *
 * <p>Events (published through the outbox on {@code save()}): {@link CommentCreatedEvent} on creation (with the
 * post's author and, for a reply, the parent comment's author, so consumers need no lookup) and a
 * {@link ReactionEvent} with {@code target = COMMENT} per actual like/dislike change.
 */
@Entity
@Table(name = "comments")
@DynamicUpdate
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class Comment extends AggregateRoot<Comment> {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "post_id", nullable = false, columnDefinition = "uuid")
    private UUID postId;

    @Column(name = "parent_comment_id", columnDefinition = "uuid")
    private UUID parentCommentId;

    @Column(name = "author_id", nullable = false, columnDefinition = "uuid")
    private UUID authorId;

    @Column(name = "author_username", nullable = false)
    private String authorUsername;

    @Column(name = "content", nullable = false, length = 2000)
    private String content;

    @Column(name = "likes_count", nullable = false)
    private int likesCount;

    @Column(name = "dislikes_count", nullable = false)
    private int dislikesCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * A comment on {@code post} by {@code authorId}; a reply when {@code parent} is given (it must belong to the same
     * post). Registers a {@link CommentCreatedEvent}.
     */
    public static Comment create(Post post, Comment parent, UUID authorId, String authorUsername, String content) {
        if (post == null) throw new IllegalArgumentException("Post is required");
        if (parent != null && !parent.belongsTo(post.getId())) {
            throw new IllegalArgumentException("Parent comment " + parent.getId()
                    + " does not belong to post " + post.getId());
        }
        var c = new Comment();
        c.id = UUID.randomUUID();
        c.postId = post.getId();
        c.parentCommentId = parent == null ? null : parent.getId();
        c.authorId = authorId;
        c.authorUsername = authorUsername;
        c.content = content;
        c.likesCount = 0;
        c.dislikesCount = 0;
        c.createdAt = Instant.now();
        c.updatedAt = c.createdAt;
        c.registerEvent(new CommentCreatedEvent(c.id, c.postId, post.getAuthorId(), c.parentCommentId,
                parent == null ? null : parent.getAuthorId(), authorId, authorUsername, Snippet.of(content)));
        return c;
    }

    /** Throws {@link ForbiddenOperationException} unless {@code requesterId} is the author. */
    public void ensureAuthoredBy(UUID requesterId, String action) {
        if (requesterId == null || !requesterId.equals(this.authorId)) {
            throw new ForbiddenOperationException("Only the author can " + action + " this comment");
        }
    }

    public void edit(UUID requesterId, String content) {
        ensureAuthoredBy(requesterId, "edit");
        edit(content);
    }

    private void edit(String content) {
        this.content = content;
        this.updatedAt = Instant.now();
    }

    /** True if this comment belongs to {@code postId} (used to validate replies). */
    public boolean belongsTo(UUID postId) {
        return this.postId.equals(postId);
    }

    /**
     * Applies a like/dislike toggle given the user's current reactions on this comment; returns the
     * change the caller must persist and registers a {@link ReactionEvent} (target COMMENT) per actual change.
     * Idempotent. Must be called with the comment row locked.
     */
    public ReactionChange react(UUID userId, Set<ReactionEvent.ReactionType> current, ReactionEvent.ReactionType type,
                                boolean add) {
        var change = ReactionChange.decide(current, type, add);
        for (var removed : change.removed()) {
            if (removed == ReactionEvent.ReactionType.LIKE) {
                if (likesCount > 0) likesCount--;
            } else if (dislikesCount > 0) {
                dislikesCount--;
            }
            registerEvent(ReactionEvent.onComment(postId, id, authorId, Snippet.of(content), userId, removed,
                    ReactionEvent.ReactionAction.REMOVED));
        }
        if (change.added() != null) {
            if (change.added() == ReactionEvent.ReactionType.LIKE) likesCount++;
            else dislikesCount++;
            registerEvent(ReactionEvent.onComment(postId, id, authorId, Snippet.of(content), userId, change.added(),
                    ReactionEvent.ReactionAction.ADDED));
        }
        return change;
    }
}
