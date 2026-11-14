package com.socialapp.socialgraph.application.usecase;

import com.socialapp.shared.domain.NotFoundException;
import com.socialapp.shared.events.UserCreatedEvent;
import com.socialapp.shared.events.UserUpdatedEvent;
import com.socialapp.socialgraph.application.command.FollowUserCommand;
import com.socialapp.socialgraph.domain.UserNode;
import com.socialapp.socialgraph.domain.UserNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SocialGraphService {

    private final UserNodeRepository userNodeRepository;
    private final ApplicationEventPublisher applicationEvents;

    /** Idempotent; never overwrites an existing node (it may already hold a newer update). */
    @Transactional("neo4jTransactionManager")
    public void syncUserCreated(UserCreatedEvent event) {
        userNodeRepository.createIfAbsent(event.userId().toString(), event.username(), event.avatarUrl(),
                epochMillis(event.occurredAt()));
        log.info("Synced UserCreated to Neo4j for userId={}", event.userId());
    }

    /** Upsert: an update arriving before the create creates the node; stale updates are ignored. */
    @Transactional("neo4jTransactionManager")
    public void syncUserUpdated(UserUpdatedEvent event) {
        userNodeRepository.upsertProfile(event.userId().toString(), event.username(), event.avatarUrl(),
                epochMillis(event.occurredAt()));
        log.info("Synced UserUpdated to Neo4j for userId={}", event.userId());
    }

    /**
     * Idempotent: following someone already followed is a no-op and emits nothing. When the relationship is
     * created, a {@code UserFollowedEvent} is recorded in the same Neo4j transaction (see
     * {@link UserNodeRepository#follow}) and later relayed to {@code social-events} via the Postgres outbox.
     *
     * @return true if the graph changed
     */
    @Transactional("neo4jTransactionManager")
    public boolean follow(FollowUserCommand command) {
        var ids = validate(command);
        var occurredAt = userNodeRepository.follow(ids[0], ids[1], UUID.randomUUID().toString(), nowMillis());
        if (occurredAt.isEmpty()) {
            log.debug("User {} already follows {}, nothing to do", command.followerId(), command.followeeId());
            return false;
        }
        applicationEvents.publishEvent(new FollowGraphChanged());
        log.info("User {} now follows {}", command.followerId(), command.followeeId());
        return true;
    }

    /**
     * Idempotent: unfollowing someone not followed is a no-op and emits nothing. Otherwise records a
     * {@code UserUnfollowedEvent} atomically with the deletion.
     *
     * @return true if the graph changed
     */
    @Transactional("neo4jTransactionManager")
    public boolean unfollow(FollowUserCommand command) {
        var ids = validate(command);
        var occurredAt = userNodeRepository.unfollow(ids[0], ids[1], UUID.randomUUID().toString(), nowMillis());
        if (occurredAt.isEmpty()) {
            log.debug("User {} does not follow {}, nothing to do", command.followerId(), command.followeeId());
            return false;
        }
        applicationEvents.publishEvent(new FollowGraphChanged());
        log.info("User {} unfollowed {}", command.followerId(), command.followeeId());
        return true;
    }

    @Transactional(value = "neo4jTransactionManager", readOnly = true)
    public List<UserNode> getFollowing(String userId) {
        return userNodeRepository.findFollowing(userId);
    }

    @Transactional(value = "neo4jTransactionManager", readOnly = true)
    public List<UserNode> getFollowers(String userId) {
        return userNodeRepository.findFollowers(userId);
    }

    @Transactional(value = "neo4jTransactionManager", readOnly = true)
    public boolean isFollowing(String followerId, String followeeId) {
        return userNodeRepository.isFollowing(followerId, followeeId);
    }

    /** Self-follow -> IllegalArgumentException (400); unknown user -> NotFoundException (404). */
    private String[] validate(FollowUserCommand command) {
        String followerId = idOf(command.followerId());
        String followeeId = idOf(command.followeeId());
        UserNode.ensureCanFollow(followerId, followeeId);
        if (!userNodeRepository.existsById(followerId)) {
            throw new NotFoundException("Follower not found: " + followerId);
        }
        if (!userNodeRepository.existsById(followeeId)) {
            throw new NotFoundException("Followee not found: " + followeeId);
        }
        return new String[] {followerId, followeeId};
    }

    private static String idOf(UUID id) {
        return id == null ? null : id.toString();
    }

    private static long nowMillis() {
        return System.currentTimeMillis();
    }

    private static long epochMillis(Instant occurredAt) {
        return occurredAt == null ? 0L : occurredAt.toEpochMilli();
    }
}
