package com.socialapp.shared.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A post was created (also a repost or quote).
 *
 * <p>Wire format (append-only, every addition optional for readers):
 * <ul>
 *   <li>the first seven fields are unchanged since before stage 2C;</li>
 *   <li>stage 2C: {@code postType} ("POST" | "REPOST" | "QUOTE") and {@code originalPostId} (the reposted/quoted
 *       post, null for a plain post); events written before that deserialize with both null, which consumers treat
 *       as a plain post;</li>
 *   <li>stage 3A: {@code originalAuthorId} (author of the reposted/quoted post, null for a plain post),
 *       {@code originalContent} (the reposted/quoted post's text at creation, null for a plain post) and
 *       {@code mentionedUserIds} (users resolved from {@code @mentions} of the post's own text when it was created,
 *       never the author; empty for a REPOST). Older events deserialize with nulls / an empty list.</li>
 * </ul>
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
        UUID originalPostId,
        UUID originalAuthorId,
        String originalContent,
        List<UUID> mentionedUserIds
) implements DomainEvent {

    public PostCreatedEvent {
        mentionedUserIds = mentionedUserIds == null ? List.of() : List.copyOf(mentionedUserIds);
    }

    public PostCreatedEvent(UUID postId, UUID authorId, String authorUsername, String content, String imageUrl) {
        this(postId, authorId, authorUsername, content, imageUrl, "POST", null);
    }

    public PostCreatedEvent(UUID postId, UUID authorId, String authorUsername, String content, String imageUrl,
                            String postType, UUID originalPostId) {
        this(postId, authorId, authorUsername, content, imageUrl, postType, originalPostId, null, null, List.of());
    }

    public PostCreatedEvent(UUID postId, UUID authorId, String authorUsername, String content, String imageUrl,
                            String postType, UUID originalPostId, UUID originalAuthorId, String originalContent,
                            List<UUID> mentionedUserIds) {
        this(UUID.randomUUID(), Instant.now(), postId, authorId, authorUsername, content, imageUrl, postType,
                originalPostId, originalAuthorId, originalContent, mentionedUserIds);
    }

    /** Pre-2C shape (no type / original), e.g. events still in the outbox from before the upgrade. */
    public PostCreatedEvent(UUID eventId, Instant occurredAt, UUID postId, UUID authorId, String authorUsername,
                            String content, String imageUrl) {
        this(eventId, occurredAt, postId, authorId, authorUsername, content, imageUrl, null, null);
    }

    /** Stage 2C shape (no original author / content, no mentions). */
    public PostCreatedEvent(UUID eventId, Instant occurredAt, UUID postId, UUID authorId, String authorUsername,
                            String content, String imageUrl, String postType, UUID originalPostId) {
        this(eventId, occurredAt, postId, authorId, authorUsername, content, imageUrl, postType, originalPostId,
                null, null, List.of());
    }

    /** The same event with {@code mentionedUserIds} replaced (same eventId and occurredAt). */
    public PostCreatedEvent withMentionedUserIds(List<UUID> userIds) {
        return new PostCreatedEvent(eventId, occurredAt, postId, authorId, authorUsername, content, imageUrl, postType,
                originalPostId, originalAuthorId, originalContent, userIds);
    }

    @Override
    public String eventType() {
        return "PostCreatedEvent";
    }
}
