package com.socialapp.feed.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * A user's private bookmark of a post (V21). Rows are only written by {@link BookmarkRepository#add} (idempotent
 * insert), the entity is the read model of the bookmark list.
 */
@Entity
@Table(name = "bookmarks")
@IdClass(Bookmark.Key.class)
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class Bookmark {

    @Id
    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Id
    @Column(name = "post_id", nullable = false, columnDefinition = "uuid")
    private UUID postId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    public record Key(UUID userId, UUID postId) implements Serializable {}
}
