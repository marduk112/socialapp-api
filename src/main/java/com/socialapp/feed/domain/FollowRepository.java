package com.socialapp.feed.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface FollowRepository extends JpaRepository<Follow, Follow.Key> {

    /**
     * Applies a follow ({@code active = true}) or unfollow ({@code active = false}) that occurred at
     * {@code occurredAt}, unless an event at least as new has already been applied to this pair.
     * Atomic (single upsert statement), so it is also safe under concurrent delivery. Equal timestamps
     * mean a redelivery of the same event (SocialGraph's clock is strictly increasing per follower).
     *
     * @return 1 if applied, 0 if ignored as stale/duplicate
     */
    @Modifying
    @Query(value = """
            INSERT INTO follows (follower_id, followee_id, active, followed_at, last_event_at)
            VALUES (:followerId, :followeeId, :active,
                    CASE WHEN :active THEN CAST(:occurredAt AS timestamptz) END, CAST(:occurredAt AS timestamptz))
            ON CONFLICT (follower_id, followee_id) DO UPDATE
               SET active        = EXCLUDED.active,
                   followed_at   = EXCLUDED.followed_at,
                   last_event_at = EXCLUDED.last_event_at
             WHERE follows.last_event_at < EXCLUDED.last_event_at""", nativeQuery = true)
    int applyIfNewer(@Param("followerId") UUID followerId, @Param("followeeId") UUID followeeId,
                     @Param("active") boolean active, @Param("occurredAt") Instant occurredAt);

    /** Ids of the users {@code followerId} currently follows (tombstones excluded). */
    @Query("select f.followeeId from Follow f where f.followerId = :followerId and f.active = true")
    List<UUID> findActiveFolloweeIds(@Param("followerId") UUID followerId);
}
