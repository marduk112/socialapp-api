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

    @Column(name = "author_id", nullable = false, columnDefinition = "uuid")
    private UUID authorId;

    @Column(name = "author_username", nullable = false)
    private String authorUsername;

    @Column(name = "content", nullable = false, length = 2000)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public static Comment create(UUID postId, UUID authorId, String authorUsername, String content) {
        var c = new Comment();
        c.id = UUID.randomUUID();
        c.postId = postId;
        c.authorId = authorId;
        c.authorUsername = authorUsername;
        c.content = content;
        c.createdAt = Instant.now();
        c.updatedAt = c.createdAt;
        return c;
    }

    public void edit(String content) {
        this.content = content;
        this.updatedAt = Instant.now();
    }
}
