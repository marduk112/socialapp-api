package com.socialapp.feed.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A resolved {@code @mention}: the mentioned user and their canonical username (from Feed's
 * {@code author_profiles}) at the time the post was saved. JSON shape (API contract):
 * {@code {"userId": "<uuid>", "username": "<name>"}}.
 */
public record Mention(UUID userId, String username) {
    public Mention {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(username, "username");
    }
}
