package com.socialapp.feed.domain;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Feed's local, eventually consistent copy of one follow relationship (table {@code follows}, V15), fed
 * only by social-events. Written exclusively through {@link FollowRepository#applyIfNewer} (an atomic
 * "newest occurredAt wins" upsert); this entity is used for reads.
 */
@Entity
@Table(name = "follows")
@IdClass(Follow.Key.class)
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class Follow {

    @Id
    @Column(name = "follower_id", columnDefinition = "uuid")
    private UUID followerId;

    @Id
    @Column(name = "followee_id", columnDefinition = "uuid")
    private UUID followeeId;

    /** false = tombstone left by an unfollow (blocks older, late follow events). */
    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "followed_at")
    private Instant followedAt;

    @Column(name = "last_event_at", nullable = false)
    private Instant lastEventAt;

    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID followerId;
        private UUID followeeId;

        public Key(UUID followerId, UUID followeeId) {
            this.followerId = followerId;
            this.followeeId = followeeId;
        }
    }
}
