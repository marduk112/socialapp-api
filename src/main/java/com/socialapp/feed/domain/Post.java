package com.socialapp.feed.domain;

import com.socialapp.shared.domain.AggregateRoot;
import com.socialapp.shared.domain.ConflictException;
import com.socialapp.shared.domain.ForbiddenOperationException;
import com.socialapp.shared.events.PostCreatedEvent;
import com.socialapp.shared.events.ReactionEvent;
import com.socialapp.shared.events.Snippet;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Concurrency: counters (likes/dislikes/comments) are only changed while the row is locked
 * ({@link PostRepository#findByIdForUpdate}), so concurrent reactions/comments are serialised and
 * never lose updates. {@code @DynamicUpdate} makes other writers (edit, author rename) issue UPDATEs
 * of only the columns they changed, so they cannot overwrite counters with stale values.
 *
 * <p>Content limit: a post's own text ({@code content}; for a QUOTE, its required comment) is at most
 * {@link #MAX_CONTENT_LENGTH} Unicode code points (an emoji counts as 1; whitespace and newlines count; no
 * trimming). Posts stored before the limit existed may be longer: they stay readable, but an edit must bring
 * them within the limit. Hashtags and mention candidates are derived from that text only (a quote's
 * {@code originalContent} is not part of it; a REPOST has no text).
 *
 * <p>Types ({@link PostType}): a REPOST or QUOTE references its original by {@code originalPostId}. The target
 * of a repost/quote is never a REPOST: reposting/quoting a repost item targets that repost's original
 * ({@link #repostTargetId()}, resolved by the service; the factories reject a REPOST target). The type is
 * immutable. A REPOST cannot be edited (409); a QUOTE's comment can. {@code originalPostId} of a QUOTE becomes
 * null when its original is deleted (V3 FK ON DELETE SET NULL); REPOSTs are deleted together with their
 * original ({@code PostService.deletePost}; V18 CHECK forbids a REPOST without original).
 */
@Entity
@Table(name = "posts")
@DynamicUpdate
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class Post extends AggregateRoot<Post> {

    /** Maximum length of a post's own text, in Unicode code points. */
    public static final int MAX_CONTENT_LENGTH = 500;

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 10)
    private PostType type = PostType.POST;

    @Column(name = "author_id", nullable = false, columnDefinition = "uuid")
    private UUID authorId;

    @Column(name = "author_username", nullable = false)
    private String authorUsername;

    @Column(name = "content", nullable = false, length = 2000)
    private String content;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "likes_count", nullable = false)
    private long likesCount = 0;

    @Column(name = "dislikes_count", nullable = false)
    private long dislikesCount = 0;

    @Column(name = "comments_count", nullable = false)
    private long commentsCount = 0;

    @Column(name = "original_post_id", columnDefinition = "uuid")
    private UUID originalPostId;

    @Column(name = "original_author_username")
    private String originalAuthorUsername;

    @Column(name = "original_content", length = 2000)
    private String originalContent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // ── Factory ─────────────────────────────────────────────────────────────
    public static Post create(UUID authorId, String authorUsername, String content, String imageUrl) {
        requireValidContent(content);
        var post = new Post();
        post.id = UUID.randomUUID();
        post.authorId = authorId;
        post.authorUsername = authorUsername;
        post.content = content;
        post.imageUrl = imageUrl;
        post.createdAt = Instant.now();
        post.updatedAt = post.createdAt;

        post.registerCreated(null);
        return post;
    }

    /**
     * A repost of {@code target} by {@code authorId}: no own text, no image of its own (the original's is shown
     * through the nested original). {@code target} must not be a REPOST (resolve it with
     * {@link #repostTargetId()} first). Idempotency per (author, original) is the service's job.
     */
    public static Post repost(UUID authorId, String authorUsername, Post target) {
        var post = derived(authorId, authorUsername, target, PostType.REPOST, "");
        post.registerCreated(target.authorId);
        return post;
    }

    /** A quote of {@code target} with a required comment (1..{@link #MAX_CONTENT_LENGTH} code points). */
    public static Post quote(UUID authorId, String authorUsername, Post target, String comment) {
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("Quote comment must not be blank");
        }
        requireWithinLimit(comment, "Quote comment");
        var post = derived(authorId, authorUsername, target, PostType.QUOTE, comment);
        post.registerCreated(target.authorId);
        return post;
    }

    private static Post derived(UUID authorId, String authorUsername, Post target, PostType type, String content) {
        if (target == null) throw new IllegalArgumentException("Original post is required");
        if (target.type == PostType.REPOST) {
            throw new IllegalArgumentException("A " + type.name().toLowerCase() + " must target an original post, "
                    + "not a repost (resolve it with repostTargetId first)");
        }
        var post = new Post();
        post.id = UUID.randomUUID();
        post.type = type;
        post.authorId = authorId;
        post.authorUsername = authorUsername;
        post.content = content;
        post.imageUrl = null;
        post.originalPostId = target.id;
        post.originalAuthorUsername = target.authorUsername;
        post.originalContent = target.content;
        post.createdAt = Instant.now();
        post.updatedAt = post.createdAt;
        return post;
    }

    private void registerCreated(UUID originalAuthorId) {
        registerEvent(new PostCreatedEvent(id, authorId, authorUsername, content, imageUrl, type.name(),
                originalPostId, originalAuthorId, originalContent, List.of()));
    }

    /**
     * Records the users resolved from this post's {@code @mentions} in its pending {@link PostCreatedEvent}, so
     * consumers (notifications) learn who was mentioned. Must be called BEFORE the post is saved (Spring Data
     * publishes the registered events on {@code save()}); a post without a pending creation event (an edit) is left
     * unchanged: mentions added by an edit are indexed but not announced.
     */
    public void announceMentions(List<Mention> mentions) {
        var pending = List.copyOf(domainEvents());
        if (pending.stream().noneMatch(e -> e instanceof PostCreatedEvent)) return;
        var ids = mentions == null ? List.<UUID>of() : mentions.stream().map(Mention::userId).distinct().toList();
        clearDomainEvents();
        for (Object event : pending) {
            registerEvent(event instanceof PostCreatedEvent created ? created.withMentionedUserIds(ids) : event);
        }
    }

    /**
     * The post a repost/quote of THIS item must point at: a REPOST's original (flattening, so a repost never
     * points at a repost), otherwise this post itself. Empty for a REPOST whose original is gone (cannot exist
     * in the database, see class comment).
     */
    public java.util.Optional<UUID> repostTargetId() {
        return type == PostType.REPOST ? java.util.Optional.ofNullable(originalPostId) : java.util.Optional.of(id);
    }

    public boolean isRepost() {
        return type == PostType.REPOST;
    }

    /** REPOST or QUOTE: an item that embeds another post. */
    public boolean embedsOriginal() {
        return type != PostType.POST;
    }

    // ── Business methods ─────────────────────────────────────────────────────

    /** Throws {@link ForbiddenOperationException} unless {@code requesterId} is the author. */
    public void ensureAuthoredBy(UUID requesterId, String action) {
        if (requesterId == null || !requesterId.equals(this.authorId)) {
            throw new ForbiddenOperationException("Only the author can " + action + " this post");
        }
    }

    /**
     * Replaces the post's own text (a QUOTE: its comment) and image. Ownership is checked first; a REPOST has no
     * text and cannot be edited ({@link ConflictException}, 409: undo it instead).
     */
    public void edit(UUID requesterId, String content, String imageUrl) {
        ensureAuthoredBy(requesterId, "edit");
        if (type == PostType.REPOST) {
            throw new ConflictException("A repost cannot be edited; undo it (DELETE /posts/{id}/repost) instead");
        }
        edit(content, imageUrl);
    }

    private void edit(String content, String imageUrl) {
        requireValidContent(content);
        this.content = content;
        this.imageUrl = imageUrl;
        this.updatedAt = Instant.now();
    }

    /** Normalised hashtags of this post's own text ({@link HashtagParser}). */
    public List<String> hashtags() {
        return HashtagParser.parse(content);
    }

    /** {@code @mention} candidates in this post's own text, still to be resolved ({@link MentionParser}). */
    public List<String> mentionCandidates() {
        return MentionParser.parse(content);
    }

    /** Length of {@code text} as the content limit counts it: Unicode code points. */
    public static int contentLength(String text) {
        return text.codePointCount(0, text.length());
    }

    private static void requireValidContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Post content must not be blank");
        }
        requireWithinLimit(content, "Post content");
    }

    private static void requireWithinLimit(String text, String what) {
        int length = contentLength(text);
        if (length > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException(what + " must be at most " + MAX_CONTENT_LENGTH
                    + " characters (got " + length + ")");
        }
    }

    public void incrementCommentsCount() {
        this.commentsCount++;
    }

    public void decrementCommentsCount() {
        if (this.commentsCount > 0) {
            this.commentsCount--;
        }
    }

    /**
     * Applies a like/dislike toggle for {@code userId}, given the reactions the user currently has
     * on this post. Updates the counters, registers a {@link ReactionEvent} per actual change and
     * returns the change the caller must persist. Idempotent: repeating a request is a no-op.
     * Must be called with the post row locked (see class comment).
     */
    public ReactionChange react(UUID userId, Set<ReactionEvent.ReactionType> current,
                                ReactionEvent.ReactionType type, boolean add) {
        var change = ReactionChange.decide(current, type, add);
        for (var removed : change.removed()) {
            if (removed == ReactionEvent.ReactionType.LIKE) {
                if (likesCount > 0) likesCount--;
            } else if (dislikesCount > 0) {
                dislikesCount--;
            }
            registerEvent(ReactionEvent.onPost(id, authorId, Snippet.of(content), userId, removed,
                    ReactionEvent.ReactionAction.REMOVED));
        }
        if (change.added() != null) {
            if (change.added() == ReactionEvent.ReactionType.LIKE) likesCount++;
            else dislikesCount++;
            registerEvent(ReactionEvent.onPost(id, authorId, Snippet.of(content), userId, change.added(),
                    ReactionEvent.ReactionAction.ADDED));
        }
        return change;
    }
}
