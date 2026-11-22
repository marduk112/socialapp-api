package com.socialapp.usermanagement.application.usecase;

import com.socialapp.shared.events.UserBlockedEvent;
import com.socialapp.shared.events.UserUnblockedEvent;
import com.socialapp.usermanagement.domain.UserBlockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Keeps UserManagement's block projection from social-events (stage 5A) so people search can leave out users the
 * searcher blocks or is blocked by, without asking SocialGraph. Idempotent and order-tolerant: newest occurredAt per
 * (blocker, blocked) wins, an unblock leaves a tombstone.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserBlockProjection {

    private final UserBlockRepository repository;

    @Transactional
    public boolean on(UserBlockedEvent e) {
        return apply(e.blockerId(), e.blockedId(), true, e.occurredAt(), e.eventId());
    }

    @Transactional
    public boolean on(UserUnblockedEvent e) {
        return apply(e.blockerId(), e.blockedId(), false, e.occurredAt(), e.eventId());
    }

    private boolean apply(UUID blocker, UUID blocked, boolean active, Instant occurredAt, UUID eventId) {
        if (blocker == null || blocked == null || occurredAt == null || blocker.equals(blocked)) {
            log.warn("Ignoring malformed block event {} ({} -> {}, occurredAt={})", eventId, blocker, blocked, occurredAt);
            return false;
        }
        return repository.apply(blocker, blocked, active, occurredAt) == 1;
    }
}
