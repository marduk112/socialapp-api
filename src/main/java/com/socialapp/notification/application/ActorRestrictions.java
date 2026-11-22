package com.socialapp.notification.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Notification's projection of blocks and mutes ({@code notification_blocks}, {@code notification_mutes}, V27), fed by
 * social-events (stage 5A). The read side of notifications applies the same rule in SQL.
 */
public interface ActorRestrictions {

    /**
     * Applies a block ({@code active = true}) or unblock that occurred at {@code occurredAt} unless an event at least as
     * new was already applied to this pair (atomic upsert, tombstone on unblock).
     *
     * @return 1 if applied, 0 if ignored as stale/duplicate
     */
    int applyBlock(UUID blockerId, UUID blockedId, boolean active, Instant occurredAt);

    /** Same as {@link #applyBlock} for mutes. */
    int applyMute(UUID muterId, UUID mutedId, boolean active, Instant occurredAt);

    /**
     * True if {@code recipientId} must not hear about {@code actorId}'s actions: one of them blocks the other, or the
     * recipient mutes the actor.
     */
    boolean suppresses(UUID recipientId, UUID actorId);
}
