package com.socialapp.feed.application.usecase;

import com.socialapp.feed.domain.FollowRepository;
import com.socialapp.shared.events.UserFollowedEvent;
import com.socialapp.shared.events.UserUnfollowedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Maintains Feed's projection of the follow graph ({@code follows}) from social-events only (no dependency
 * on SocialGraph). Same principle as {@link AuthorProfileProjection}: idempotent and order-tolerant,
 * "newest occurredAt wins" per (follower, followee):
 * <ul>
 *   <li>a redelivered event (same occurredAt) is ignored,</li>
 *   <li>an older event arriving after a newer one is ignored, so a late follow cannot resurrect an
 *       unfollowed pair and a late unfollow cannot delete a newer follow (unfollow leaves a tombstone).</li>
 * </ul>
 * The upsert is a single atomic statement ({@link FollowRepository#applyIfNewer}), so no read-modify-write
 * race even under concurrent delivery. Each handler returns true if the event changed the projection.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FollowProjection {

    private final FollowRepository followRepository;

    @Transactional
    public boolean on(UserFollowedEvent event) {
        return apply(event.followerId(), event.followeeId(), true, event.occurredAt(), event.eventId());
    }

    @Transactional
    public boolean on(UserUnfollowedEvent event) {
        return apply(event.followerId(), event.followeeId(), false, event.occurredAt(), event.eventId());
    }

    private boolean apply(UUID follower, UUID followee, boolean active, Instant occurredAt, UUID eventId) {
        if (follower == null || followee == null || occurredAt == null || follower.equals(followee)) {
            // Cannot be produced by SocialGraph; not retryable, so drop it loudly instead of looping to the DLT.
            log.warn("Ignoring malformed follow event {} ({} -> {}, occurredAt={})", eventId, follower, followee,
                    occurredAt);
            return false;
        }
        boolean applied = followRepository.applyIfNewer(follower, followee, active, occurredAt) == 1;
        if (applied) {
            log.debug("Follow projection: {} {} {} at {}", follower, active ? "follows" : "unfollowed", followee,
                    occurredAt);
        } else {
            log.debug("Ignoring stale/duplicate follow event {} ({} -> {}, occurredAt={})", eventId, follower,
                    followee, occurredAt);
        }
        return applied;
    }
}
