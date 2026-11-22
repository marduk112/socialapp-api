package com.socialapp.socialgraph.application.usecase;

import com.socialapp.shared.domain.ForbiddenOperationException;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SocialGraphService {

    public static final int DEFAULT_SUGGESTIONS = 5;
    public static final int MAX_SUGGESTIONS = 20;

    private final UserNodeRepository userNodeRepository;
    private final ApplicationEventPublisher applicationEvents;
    private final FollowSuggestionQueries suggestionQueries;

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
     * @throws ForbiddenOperationException (403) if either user blocks the other (stage 5A). The same answer for
     *         both sides, so the blocked user does not learn more than "cannot follow".
     */
    @Transactional("neo4jTransactionManager")
    public boolean follow(FollowUserCommand command) {
        var ids = validate(command);
        var occurredAt = userNodeRepository.follow(ids[0], ids[1], UUID.randomUUID().toString(), nowMillis());
        if (occurredAt.isEmpty()) {
            // Same transaction, the follower's lock is still held: no block can appear or vanish in between.
            if (userNodeRepository.isBlockedEitherWay(ids[0], ids[1])) {
                throw new ForbiddenOperationException("You cannot follow this user");
            }
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

    // ── Blocks and mutes (stage 5A) ─────────────────────────────────────────────

    /**
     * Blocks {@code blockedId} for {@code blockerId}: removes the follows in both directions and from then on refuses
     * new ones (see {@link UserNodeRepository#block}). Idempotent: blocking again is a no-op and emits nothing.
     *
     * @return true if the graph changed
     * @throws IllegalArgumentException (400) self-block
     * @throws NotFoundException (404) unknown user
     */
    @Transactional("neo4jTransactionManager")
    public boolean block(UUID blockerId, UUID blockedId) {
        var ids = validateRestriction(blockerId, blockedId, "block");
        var occurredAt = userNodeRepository.block(ids[0], ids[1], UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), nowMillis());
        return changed(occurredAt.isPresent(), "User {} blocked {}", blockerId, blockedId);
    }

    /** Idempotent, never fails for a valid id pair (self or unknown user: nothing to remove). Follows are not restored. */
    @Transactional("neo4jTransactionManager")
    public boolean unblock(UUID blockerId, UUID blockedId) {
        if (blockerId == null || blockedId == null) throw new IllegalArgumentException("userId is required");
        var occurredAt = userNodeRepository.unblock(blockerId.toString(), blockedId.toString(),
                UUID.randomUUID().toString(), nowMillis());
        return changed(occurredAt.isPresent(), "User {} unblocked {}", blockerId, blockedId);
    }

    /**
     * Mutes {@code mutedId} for {@code muterId} (one-directional, silent: follows untouched). Idempotent.
     *
     * @throws IllegalArgumentException (400) self-mute
     * @throws NotFoundException (404) unknown user
     */
    @Transactional("neo4jTransactionManager")
    public boolean mute(UUID muterId, UUID mutedId) {
        var ids = validateRestriction(muterId, mutedId, "mute");
        var occurredAt = userNodeRepository.mute(ids[0], ids[1], UUID.randomUUID().toString(), nowMillis());
        return changed(occurredAt.isPresent(), "User {} muted {}", muterId, mutedId);
    }

    /** Idempotent, never fails for a valid id pair. */
    @Transactional("neo4jTransactionManager")
    public boolean unmute(UUID muterId, UUID mutedId) {
        if (muterId == null || mutedId == null) throw new IllegalArgumentException("userId is required");
        var occurredAt = userNodeRepository.unmute(muterId.toString(), mutedId.toString(),
                UUID.randomUUID().toString(), nowMillis());
        return changed(occurredAt.isPresent(), "User {} unmuted {}", muterId, mutedId);
    }

    /** Users {@code userId} blocks, by username. */
    @Transactional(value = "neo4jTransactionManager", readOnly = true)
    public List<UserNode> getBlocked(UUID userId) {
        return userNodeRepository.findBlocked(userId.toString());
    }

    /** Users {@code userId} mutes, by username. */
    @Transactional(value = "neo4jTransactionManager", readOnly = true)
    public List<UserNode> getMuted(UUID userId) {
        return userNodeRepository.findMuted(userId.toString());
    }

    /**
     * The viewer's relationship to {@code otherId} for rendering profile actions. Deliberately has no "blocked by the
     * other" flag: a blocked user must not be told explicitly (they only notice that following fails and content is
     * gone). Unknown users and the viewer themselves give all-false.
     */
    @Transactional(value = "neo4jTransactionManager", readOnly = true)
    public Relationship getRelationship(UUID viewerId, UUID otherId) {
        String me = viewerId.toString();
        String other = otherId.toString();
        if (me.equals(other)) return new Relationship(false, false, false, false);
        return new Relationship(userNodeRepository.isFollowing(me, other), userNodeRepository.isFollowing(other, me),
                userNodeRepository.isBlocking(me, other), userNodeRepository.isMuting(me, other));
    }

    /** See {@link #getRelationship}. */
    public record Relationship(boolean following, boolean followedBy, boolean blocked, boolean muted) {}

    private boolean changed(boolean changed, String message, UUID actor, UUID target) {
        if (!changed) {
            log.debug("Nothing to do for {} -> {}", actor, target);
            return false;
        }
        applicationEvents.publishEvent(new FollowGraphChanged());
        log.info(message, actor, target);
        return true;
    }

    /** Self -> IllegalArgumentException (400); unknown user -> NotFoundException (404). */
    private String[] validateRestriction(UUID userId, UUID targetId, String action) {
        String user = idOf(userId);
        String target = idOf(targetId);
        UserNode.ensureCanRestrict(user, target, action);
        if (!userNodeRepository.existsById(user)) throw new NotFoundException("User not found: " + user);
        if (!userNodeRepository.existsById(target)) throw new NotFoundException("User not found: " + target);
        return new String[] {user, target};
    }

    // ── Reads ──────────────────────────────────────────────────────────────────

    @Transactional(value = "neo4jTransactionManager", readOnly = true)
    public List<UserNode> getFollowing(String userId) {
        return userNodeRepository.findFollowing(userId);
    }

    @Transactional(value = "neo4jTransactionManager", readOnly = true)
    public List<UserNode> getFollowers(String userId) {
        return userNodeRepository.findFollowers(userId);
    }

    /**
     * "Who to follow": friends of friends ranked by mutual followees, topped up with the most followed users
     * (cold start / small graphs). Never contains the viewer, anyone they already follow, anyone they block or mute,
     * or anyone who blocks them (stage 5A).
     *
     * @param limit clamped to 1..{@value #MAX_SUGGESTIONS}
     */
    @Transactional(value = "neo4jTransactionManager", readOnly = true)
    public List<FollowSuggestion> getSuggestions(UUID viewerId, int limit) {
        if (viewerId == null) {
            throw new IllegalArgumentException("viewerId is required");
        }
        int size = Math.max(1, Math.min(limit, MAX_SUGGESTIONS));
        String viewer = viewerId.toString();
        var result = new java.util.ArrayList<>(suggestionQueries.friendsOfFriends(viewer, size));
        if (result.size() < size) {
            var seen = new LinkedHashSet<String>();
            result.forEach(s -> seen.add(s.userId()));
            result.addAll(suggestionQueries.mostFollowed(viewer, seen, size - result.size()));
        }
        return List.copyOf(result);
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
