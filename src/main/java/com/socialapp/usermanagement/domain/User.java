package com.socialapp.usermanagement.domain;

import com.socialapp.shared.domain.AggregateRoot;
import com.socialapp.shared.events.UserCreatedEvent;
import com.socialapp.shared.events.UserUpdatedEvent;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * User Aggregate Root — persisted in PostgreSQL.
 * Domain events are registered via AbstractAggregateRoot.registerEvent()
 * and published by Spring Data after repository.save() commits.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class User extends AggregateRoot<User> {

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "bio", length = 500)
    private String bio;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ── Factory method ──────────────────────────────────────────────────────
    public static User register(String username, String email, String passwordHash, String avatarUrl) {
        var user = new User();
        user.id = UUID.randomUUID();
        user.username = username;
        user.email = email;
        user.passwordHash = passwordHash;
        user.avatarUrl = avatarUrl;
        user.createdAt = Instant.now();
        user.updatedAt = user.createdAt;

        // Register domain event — published after transaction commits
        user.registerEvent(new UserCreatedEvent(user.id, username, email, avatarUrl));
        return user;
    }

    // ── Business methods ────────────────────────────────────────────────────
    public void updateProfile(String username, String bio, String avatarUrl) {
        this.username = username;
        this.bio = bio;
        this.avatarUrl = avatarUrl;
        this.updatedAt = Instant.now();

        registerEvent(new UserUpdatedEvent(this.id, username, avatarUrl, bio));
    }
}
