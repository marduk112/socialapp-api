package com.socialapp.feed.application.usecase;

import com.socialapp.feed.domain.AuthorProfile;
import com.socialapp.feed.domain.AuthorProfileRepository;
import com.socialapp.feed.domain.CommentRepository;
import com.socialapp.feed.domain.PostRepository;
import com.socialapp.shared.events.UserCreatedEvent;
import com.socialapp.shared.events.UserUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains Feed's projection of users ({@code author_profiles}) and the usernames denormalised into
 * posts/comments, from user-events only (no dependency on UserManagement).
 *
 * <p>Idempotent and order-tolerant, because events can be redelivered (at-least-once) and replayed:
 * <ul>
 *   <li>{@code UserCreated} only creates a missing profile; it never overwrites an existing one, so a
 *       late/redelivered create cannot undo a newer update.</li>
 *   <li>{@code UserUpdated} upserts, but is ignored if it is not newer than the last applied event.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthorProfileProjection {

    private final AuthorProfileRepository authorProfileRepository;
    private final PostRepository postRepository;
    private final CommentRepository commentRepository;

    @Transactional
    public void on(UserCreatedEvent event) {
        authorProfileRepository.findById(event.userId()).ifPresentOrElse(
                existing -> {
                    log.debug("Author profile {} already exists, UserCreated is a no-op", event.userId());
                    existing.backfillUsername(event.username());
                },
                () -> authorProfileRepository.save(new AuthorProfile(
                        event.userId(), event.username(), event.avatarUrl(), event.occurredAt())));
    }

    @Transactional
    public void on(UserUpdatedEvent event) {
        var existing = authorProfileRepository.findById(event.userId());
        if (existing.isEmpty()) {
            // Update overtook the create (or the create was lost): upsert.
            authorProfileRepository.save(new AuthorProfile(
                    event.userId(), event.username(), event.avatarUrl(), event.occurredAt()));
        } else if (!existing.get().applyUpdate(event.username(), event.avatarUrl(), event.occurredAt())) {
            log.debug("Ignoring stale UserUpdated for userId={} (occurredAt={})", event.userId(), event.occurredAt());
            return;
        }
        if (event.username() != null && !event.username().isBlank()) {
            int posts = postRepository.renameAuthor(event.userId(), event.username());
            int shares = postRepository.renameOriginalAuthor(event.userId(), event.username());
            int comments = commentRepository.renameAuthor(event.userId(), event.username());
            if (posts + shares + comments > 0) {
                log.info("Refreshed username of userId={} in {} posts, {} shares, {} comments",
                        event.userId(), posts, shares, comments);
            }
        }
    }
}
