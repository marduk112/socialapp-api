package com.socialapp.feed.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "comments")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class Comment {

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

    public static Comment create(UUID postId, UUID authorId, String authorUsername, String content, UUID parentCommentId) {
        var c = new Comment();
        c.id = UUID.randomUUID();
        c.postId = postId;
        c.parentCommentId = parentCommentId;
        c.authorId = authorId;
        c.authorUsername = authorUsername;
        c.content = content;
        c.likesCount = 0;
        c.dislikesCount = 0;
        c.createdAt = Instant.now();
        c.updatedAt = c.createdAt;
        return c;
    }

    public void edit(String content) {
        this.content = content;
        this.updatedAt = Instant.now();
    }

    public void addLike() { this.likesCount++; }
    public void removeLike() { if (this.likesCount > 0) this.likesCount--; }
    public void addDislike() { this.dislikesCount++; }
    public void removeDislike() { if (this.dislikesCount > 0) this.dislikesCount--; }
}
