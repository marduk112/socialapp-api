package com.socialapp.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A comment (or a reply to a comment) was created (topic {@code comment-events}, key = postId, so all comments of a
 * post are ordered). Published by Feed through the transactional outbox.
 *
 * <ul>
 *   <li>{@code postAuthorId}: author of the commented post (for a comment on a REPOST item: the reposter).</li>
 *   <li>{@code parentCommentId} / {@code parentCommentAuthorId}: set only for a reply.</li>
 *   <li>{@code contentSnippet}: {@link Snippet#of} of the comment text at creation (not updated on edit).</li>
 * </ul>
 */
public record CommentCreatedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID commentId,
        UUID postId,
        UUID postAuthorId,
        UUID parentCommentId,
        UUID parentCommentAuthorId,
        UUID authorId,
        String authorUsername,
        String contentSnippet
) implements DomainEvent {

    public CommentCreatedEvent(UUID commentId, UUID postId, UUID postAuthorId, UUID parentCommentId,
                               UUID parentCommentAuthorId, UUID authorId, String authorUsername, String contentSnippet) {
        this(UUID.randomUUID(), Instant.now(), commentId, postId, postAuthorId, parentCommentId, parentCommentAuthorId,
                authorId, authorUsername, contentSnippet);
    }

    @Override
    public String eventType() {
        return "CommentCreatedEvent";
    }
}
