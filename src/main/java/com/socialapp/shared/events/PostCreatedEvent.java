package com.socialapp.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A post was created (also a repost or quote).
 *
 * <p>Wire format: the first seven fields are unchanged since before stage 2C. {@code postType} ("POST" |
 * "REPOST" | "QUOTE") and {@code originalPostId} (the reposted/quoted post, null for a plain post) were added in
 * stage 2C; events written before that deserialize with both null, which consumers treat as a plain post.
 */
public record PostCreatedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID postId,
        UUID authorId,
        String authorUsername,
        String content,
        String imageUrl,
        String postType,
        UUID originalPostId
) implements DomainEvent {

    public PostCreatedEvent(UUID postId, UUID authorId, String authorUsername, String content, String imageUrl) {
        this(postId, authorId, authorUsername, content, imageUrl, "POST", null);
    }

    public PostCreatedEvent(UUID postId, UUID authorId, String authorUsername, String content, String imageUrl,
                            String postType, UUID originalPostId) {
        this(UUID.randomUUID(), Instant.now(), postId, authorId, authorUsername, content, imageUrl, postType,
                originalPostId);
    }

    /** Pre-2C shape (no type / original), e.g. events still in the outbox from before the upgrade. */
    public PostCreatedEvent(UUID eventId, Instant occurredAt, UUID postId, UUID authorId, String authorUsername,
                            String content, String imageUrl) {
        this(eventId, occurredAt, postId, authorId, authorUsername, content, imageUrl, null, null);
    }

    @Override
    public String eventType() {
        return "PostCreatedEvent";
    }
}
