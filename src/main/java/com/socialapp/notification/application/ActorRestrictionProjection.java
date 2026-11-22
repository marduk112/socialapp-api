package com.socialapp.notification.application;

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
 * Keeps Notification's block/mute projection ({@link ActorRestrictions}) from social-events (stage 5A): idempotent and
 * order-tolerant, newest occurredAt per pair wins, undo = tombstone. Unlike notifications themselves, there is no cutoff:
 * a block from before the Notification BC existed still has to hide what it hides.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActorRestrictionProjection {

    private final ActorRestrictions restrictions;

    @Transactional
    public boolean on(UserBlockedEvent e) {
        return apply(true, e.blockerId(), e.blockedId(), true, e.occurredAt(), e.eventId());
    }

    @Transactional
    public boolean on(UserUnblockedEvent e) {
        return apply(true, e.blockerId(), e.blockedId(), false, e.occurredAt(), e.eventId());
    }

    @Transactional
    public boolean on(UserMutedEvent e) {
        return apply(false, e.muterId(), e.mutedId(), true, e.occurredAt(), e.eventId());
    }

    @Transactional
    public boolean on(UserUnmutedEvent e) {
        return apply(false, e.muterId(), e.mutedId(), false, e.occurredAt(), e.eventId());
    }

    private boolean apply(boolean block, UUID source, UUID target, boolean active, Instant occurredAt, UUID eventId) {
        if (source == null || target == null || occurredAt == null || source.equals(target)) {
            log.warn("Ignoring malformed {} event {} ({} -> {}, occurredAt={})", block ? "block" : "mute", eventId,
                    source, target, occurredAt);
            return false;
        }
        int applied = block ? restrictions.applyBlock(source, target, active, occurredAt)
                : restrictions.applyMute(source, target, active, occurredAt);
        return applied == 1;
    }
}
