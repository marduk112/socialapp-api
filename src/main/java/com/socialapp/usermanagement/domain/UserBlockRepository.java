package com.socialapp.usermanagement.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Port: UserManagement's projection of blocks ({@code user_blocks}, V28), fed only by social-events (stage 5A) and used
 * by people search ({@link UserRepository#searchByUsername}).
 */
public interface UserBlockRepository {

    /**
     * Applies a block ({@code active = true}) or unblock that occurred at {@code occurredAt}, unless an event at least as
     * new was already applied to this pair (atomic upsert, tombstone on unblock).
     *
     * @return 1 if applied, 0 if ignored as stale/duplicate
     */
    int apply(UUID blockerId, UUID blockedId, boolean active, Instant occurredAt);
}
