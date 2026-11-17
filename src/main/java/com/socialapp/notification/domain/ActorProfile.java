package com.socialapp.notification.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Notification's own, eventually consistent projection of a user's display data (fed only by user-events), so
 * notifications can show actor names/avatars without importing UserManagement. Same order-tolerance rules as Feed's
 * {@code AuthorProfile}: {@code lastEventAt} is the occurredAt of the last applied event; older events are ignored.
 */
@Entity
@Table(name = "notification_actor_profiles")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class ActorProfile {

    @Id
    @Column(name = "user_id", columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "username", length = 50)
    private String username;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "last_event_at")
    private Instant lastEventAt;

    public ActorProfile(UUID userId, String username, String avatarUrl, Instant lastEventAt) {
        this.userId = userId;
        this.username = username;
        this.avatarUrl = avatarUrl;
        this.lastEventAt = lastEventAt;
    }

    /** @return false (and changes nothing) if the change is not newer than the last one applied */
    public boolean applyUpdate(String username, String avatarUrl, Instant occurredAt) {
        if (lastEventAt != null && occurredAt != null && !occurredAt.isAfter(lastEventAt)) {
            return false;
        }
        if (username != null) this.username = username;
        this.avatarUrl = avatarUrl;
        if (occurredAt != null) this.lastEventAt = occurredAt;
        return true;
    }

    /** Fills a missing username; never overwrites. */
    public void backfillUsername(String username) {
        if (this.username == null && username != null) {
            this.username = username;
        }
    }
}
