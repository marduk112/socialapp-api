package com.socialapp.feed.domain;

import com.socialapp.shared.domain.AggregateRoot;
import com.socialapp.shared.events.PostCreatedEvent;
import com.socialapp.shared.events.ReactionEvent;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "posts")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class Post extends AggregateRoot<Post> {

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

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
        var post = new Post();
        post.id = UUID.randomUUID();
        post.authorId = authorId;
        post.authorUsername = authorUsername;
        post.content = content;
        post.imageUrl = imageUrl;
        post.createdAt = Instant.now();
        post.updatedAt = post.createdAt;

        post.registerEvent(new PostCreatedEvent(post.id, authorId, authorUsername, content, imageUrl));
        return post;
    }

    public static Post share(UUID authorId, String authorUsername, Post original, String comment) {
        var post = new Post();
        post.id = UUID.randomUUID();
        post.authorId = authorId;
        post.authorUsername = authorUsername;
        post.content = comment != null && !comment.isBlank() ? comment : "";
        post.imageUrl = original.imageUrl;
        post.originalPostId = original.id;
        post.originalAuthorUsername = original.authorUsername;
        post.originalContent = original.content;
        post.createdAt = Instant.now();
        post.updatedAt = post.createdAt;

        post.registerEvent(new PostCreatedEvent(post.id, authorId, authorUsername, post.content, post.imageUrl));
        return post;
    }

    // ── Business methods ─────────────────────────────────────────────────────
    public void edit(String content, String imageUrl) {
        this.content = content;
        this.imageUrl = imageUrl;
        this.updatedAt = Instant.now();
    }

    public void incrementCommentsCount() {
        this.commentsCount++;
    }

    public void decrementCommentsCount() {
        if (this.commentsCount > 0) {
            this.commentsCount--;
        }
    }

    public void addLike(UUID userId) {
        this.likesCount++;
        registerEvent(new ReactionEvent(this.id, userId,
                ReactionEvent.ReactionType.LIKE, ReactionEvent.ReactionAction.ADDED));
    }

    public void removeLike(UUID userId) {
        if (this.likesCount > 0) {
            this.likesCount--;
            registerEvent(new ReactionEvent(this.id, userId,
                    ReactionEvent.ReactionType.LIKE, ReactionEvent.ReactionAction.REMOVED));
        }
    }

    public void addDislike(UUID userId) {
        this.dislikesCount++;
        registerEvent(new ReactionEvent(this.id, userId,
                ReactionEvent.ReactionType.DISLIKE, ReactionEvent.ReactionAction.ADDED));
    }

    public void removeDislike(UUID userId) {
        if (this.dislikesCount > 0) {
            this.dislikesCount--;
            registerEvent(new ReactionEvent(this.id, userId,
                    ReactionEvent.ReactionType.DISLIKE, ReactionEvent.ReactionAction.REMOVED));
        }
    }
}
