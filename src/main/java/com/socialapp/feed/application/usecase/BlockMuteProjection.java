package com.socialapp.feed.application.usecase;

import com.socialapp.feed.domain.BlockMuteRepository;
import com.socialapp.shared.events.UserBlockedEvent;
import com.socialapp.shared.events.UserMutedEvent;
import com.socialapp.shared.events.UserUnblockedEvent;
import com.socialapp.shared.events.UserUnmutedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Maintains Feed's projection of blocks and mutes ({@code feed_blocks}, {@code feed_mutes}) from social-events only
 * (stage 5A). Same principle as {@link FollowProjection}: one atomic "newest occurredAt wins" upsert per event, an undo
 * leaves a tombstone, so redelivered, late and out-of-order events never resurrect or drop a pair wrongly. Follows
 * removed by a block arrive as their own {@code UserUnfollowedEvent}s ({@link FollowProjection}). Each handler returns
 * true if the event changed the projection.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BlockMuteProjection {

    private final BlockMuteRepository repository;

    @Transactional
    public boolean on(UserBlockedEvent e) {
        return apply("block", e.blockerId(), e.blockedId(), true, e.occurredAt(), e.eventId());
    }

    @Transactional
    public boolean on(UserUnblockedEvent e) {
        return apply("block", e.blockerId(), e.blockedId(), false, e.occurredAt(), e.eventId());
    }

    @Transactional
    public boolean on(UserMutedEvent e) {
        return apply("mute", e.muterId(), e.mutedId(), true, e.occurredAt(), e.eventId());
    }

    @Transactional
    public boolean on(UserUnmutedEvent e) {
        return apply("mute", e.muterId(), e.mutedId(), false, e.occurredAt(), e.eventId());
    }

    private boolean apply(String kind, UUID source, UUID target, boolean active, Instant occurredAt, UUID eventId) {
        if (source == null || target == null || occurredAt == null || source.equals(target)) {
            // Cannot be produced by SocialGraph; not retryable, so drop it loudly instead of looping to the DLT.
            log.warn("Ignoring malformed {} event {} ({} -> {}, occurredAt={})", kind, eventId, source, target, occurredAt);
            return false;
        }
        int applied = "block".equals(kind)
                ? repository.applyBlock(source, target, active, occurredAt)
                : repository.applyMute(source, target, active, occurredAt);
        if (applied == 1) {
            log.debug("Feed {} projection: {} -> {} active={} at {}", kind, source, target, active, occurredAt);
            return true;
        }
        log.debug("Ignoring stale/duplicate {} event {} ({} -> {}, occurredAt={})", kind, eventId, source, target,
                occurredAt);
        return false;
    }
}
