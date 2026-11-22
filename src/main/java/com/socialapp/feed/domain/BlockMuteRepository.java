package com.socialapp.feed.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Feed's projection of blocks and mutes ({@code feed_blocks}, {@code feed_mutes}, V26), fed only by social-events
 * (stage 5A). A block hides each side's content from the other (either direction counts); a mute hides the muted
 * user's content from the muter only. List queries apply the same rules in SQL ({@link PostRepository#HIDDEN_FROM_VIEWER},
 * {@link PostRepository#BLOCKED_WITH_VIEWER}); this port serves the writes, point checks and the SSE filter.
 */
public interface BlockMuteRepository {

    /**
     * Applies a block ({@code active = true}) or unblock that occurred at {@code occurredAt}, unless an event at least as
     * new was already applied to this pair (atomic upsert, tombstone on unblock).
     *
     * @return 1 if applied, 0 if ignored as stale/duplicate
     */
    int applyBlock(UUID blockerId, UUID blockedId, boolean active, Instant occurredAt);

    /** Same as {@link #applyBlock} for mutes. */
    int applyMute(UUID muterId, UUID mutedId, boolean active, Instant occurredAt);

    /** Those of {@code userIds} that block {@code viewerId} or are blocked by them, with ONE query. */
    Set<UUID> blockedAmong(UUID viewerId, Collection<UUID> userIds);

    /** Everyone {@code viewerId} blocks or is blocked by. */
    Set<UUID> blockedEitherWay(UUID viewerId);

    /** Everyone {@code viewerId} mutes. */
    Set<UUID> mutedBy(UUID viewerId);
}
