package com.socialapp.notification.application;

import com.socialapp.notification.domain.ActorProfile;
import com.socialapp.notification.domain.ActorProfileRepository;
import com.socialapp.shared.events.UserCreatedEvent;
import com.socialapp.shared.events.UserUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains {@code notification_actor_profiles} from user-events only (no dependency on UserManagement). Same rules
 * as Feed's {@code AuthorProfileProjection}: {@code UserCreated} only creates a missing profile (and fills a missing
 * username), {@code UserUpdated} upserts unless it is not newer than the last applied event. Idempotent and
 * order-tolerant. Events without a userId (impossible from UserManagement) are dropped with a warning instead of
 * being retried into the DLT.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActorProfileProjection {

    private final ActorProfileRepository repository;

    @Transactional
    public void on(UserCreatedEvent event) {
        if (event.userId() == null) {
            log.warn("Ignoring UserCreatedEvent {} without userId", event.eventId());
            return;
        }
        repository.findById(event.userId()).ifPresentOrElse(
                existing -> existing.backfillUsername(event.username()),
                () -> repository.save(new ActorProfile(event.userId(), event.username(), event.avatarUrl(),
                        event.occurredAt())));
    }

    @Transactional
    public void on(UserUpdatedEvent event) {
        if (event.userId() == null) {
            log.warn("Ignoring UserUpdatedEvent {} without userId", event.eventId());
            return;
        }
        var existing = repository.findById(event.userId());
        if (existing.isEmpty()) {
            repository.save(new ActorProfile(event.userId(), event.username(), event.avatarUrl(), event.occurredAt()));
        } else if (!existing.get().applyUpdate(event.username(), event.avatarUrl(), event.occurredAt())) {
            log.debug("Ignoring stale UserUpdated for userId={} (occurredAt={})", event.userId(), event.occurredAt());
        }
    }
}
