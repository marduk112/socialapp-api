package com.socialapp.feed.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Feed's local, eventually consistent projection of a user (fed only by user-events).
 *
 * <p>Order tolerance: {@code lastEventAt} is the {@code occurredAt} of the last user event applied.
 * An event that is not newer than that is ignored, so redeliveries and late events never overwrite
 * newer data. Rows created before this column existed have {@code lastEventAt == null} and accept
 * the next update.
 */
@Entity
@Table(name = "author_profiles")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class AuthorProfile {

    @Id
    @Column(name = "author_id", columnDefinition = "uuid")
    private UUID authorId;

    @Column(name = "username", length = 50)
    private String username;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "last_event_at")
    private Instant lastEventAt;

    public AuthorProfile(UUID authorId, String username, String avatarUrl, Instant lastEventAt) {
        this.authorId = authorId;
        this.username = username;
        this.avatarUrl = avatarUrl;
        this.lastEventAt = lastEventAt;
    }

    /**
     * Applies a profile change that occurred at {@code occurredAt}.
     *
     * @return false (and changes nothing) if the change is not newer than the last one applied
     */
    public boolean applyUpdate(String username, String avatarUrl, Instant occurredAt) {
        if (lastEventAt != null && occurredAt != null && !occurredAt.isAfter(lastEventAt)) {
            return false;
        }
        if (username != null) this.username = username;
        this.avatarUrl = avatarUrl;
        if (occurredAt != null) this.lastEventAt = occurredAt;
        return true;
    }

    /** Fills a missing username (e.g. a row that predates stored usernames); never overwrites. */
    public void backfillUsername(String username) {
        if (this.username == null && username != null) {
            this.username = username;
        }
    }
}
