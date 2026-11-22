package com.socialapp.socialgraph.domain;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserNodeRepository extends Neo4jRepository<UserNode, String> {

    Optional<UserNode> findByUserId(String userId);

    @Query("MATCH (u:User {userId: $userId})-[:FOLLOWS]->(f:User) RETURN f")
    List<UserNode> findFollowing(String userId);

    @Query("MATCH (follower:User)-[:FOLLOWS]->(u:User {userId: $userId}) RETURN follower")
    List<UserNode> findFollowers(String userId);

    @Query("RETURN EXISTS((:User {userId: $followerId})-[:FOLLOWS]->(:User {userId: $followeeId}))")
    boolean isFollowing(String followerId, String followeeId);

    // ── Projection of user-events (idempotent, order-tolerant) ─────────────────
    // profileUpdatedAt = occurredAt (epoch millis) of the last applied event; older events are ignored.
    // MERGE on userId is race-safe thanks to the unique constraint (Neo4jSchemaInitializer).

    /** Creates the node if absent; never overwrites an existing node (a late create must not undo an update). */
    @Query("MERGE (u:User {userId: $userId}) "
            + "ON CREATE SET u.username = $username, u.avatarUrl = $avatarUrl, u.profileUpdatedAt = $occurredAt")
    void createIfAbsent(String userId, String username, String avatarUrl, long occurredAt);

    /** Upserts the profile unless a newer event has already been applied. */
    @Query("MERGE (u:User {userId: $userId}) "
            + "WITH u WHERE u.profileUpdatedAt IS NULL OR u.profileUpdatedAt < $occurredAt "
            + "SET u.username = $username, u.avatarUrl = $avatarUrl, u.profileUpdatedAt = $occurredAt")
    void upsertProfile(String userId, String username, String avatarUrl, long occurredAt);

    // ── Follow graph ───────────────────────────────────────────────────────────
    // The follower node is write-locked first (SET/REMOVE of a dummy property, the documented Neo4j
    // locking idiom), so concurrent follow/unfollow by the same user are serialised: the existence check
    // and the CREATE/DELETE cannot interleave, so no duplicate FOLLOWS relationship and no double event.
    //
    // Graph change + event record in ONE Neo4j transaction (Neo4j-side outbox): when the relationship is
    // actually created/deleted, the same statement creates a :SocialOutbox node describing the event.
    // SocialOutboxForwarder later moves it to the Postgres outbox. A no-op (already followed / not
    // followed) matches no row, so it records nothing and returns empty.
    //
    // occurredAt comes from a per-follower logical clock (u.socialClock, epoch millis) advanced under the
    // lock: max(now, previous + 1). It is therefore strictly increasing in commit order per follower even
    // with clock skew or a long lock wait, which is what lets consumers resolve redelivery/reordering with
    // "newest occurredAt wins" and still end in the graph's state.

    /**
     * Creates the FOLLOWS relationship if absent and records a {@code UserFollowedEvent} in the same
     * transaction. Never while a BLOCKS relationship exists in either direction (stage 5A): {@link #block} takes the
     * follower's lock too, so the check cannot interleave with a concurrent block.
     *
     * @return the event's occurredAt (epoch millis) if the relationship was created; empty if it already
     *         existed, a block exists between the two, or a node is missing
     */
    @Query("MATCH (a:User {userId: $followerId}), (b:User {userId: $followeeId}) "
            + "SET a._lock = true REMOVE a._lock "
            + "WITH a, b WHERE NOT EXISTS { (a)-[:FOLLOWS]->(b) } AND NOT EXISTS { (a)-[:BLOCKS]-(b) } "
            + "SET a.socialClock = CASE WHEN a.socialClock IS NULL OR a.socialClock < $now "
            + "                         THEN $now ELSE a.socialClock + 1 END "
            + "CREATE (a)-[:FOLLOWS {since: a.socialClock}]->(b) "
            + "CREATE (o:SocialOutbox {eventId: $eventId, type: 'UserFollowedEvent', "
            + "        followerId: $followerId, followeeId: $followeeId, occurredAt: a.socialClock}) "
            + "RETURN o.occurredAt")
    Optional<Long> follow(String followerId, String followeeId, String eventId, long now);

    /**
     * Deletes the FOLLOWS relationship(s) if present and records a {@code UserUnfollowedEvent} in the same
     * transaction.
     *
     * @return the event's occurredAt (epoch millis) if a relationship was deleted; empty otherwise
     */
    @Query("MATCH (a:User {userId: $followerId}) "
            + "SET a._lock = true REMOVE a._lock "
            + "WITH a MATCH (a)-[r:FOLLOWS]->(:User {userId: $followeeId}) DELETE r "
            + "WITH a, count(r) AS removed "
            + "SET a.socialClock = CASE WHEN a.socialClock IS NULL OR a.socialClock < $now "
            + "                         THEN $now ELSE a.socialClock + 1 END "
            + "CREATE (o:SocialOutbox {eventId: $eventId, type: 'UserUnfollowedEvent', "
            + "        followerId: $followerId, followeeId: $followeeId, occurredAt: a.socialClock}) "
            + "RETURN o.occurredAt")
    Optional<Long> unfollow(String followerId, String followeeId, String eventId, long now);

    // ── Blocks and mutes (stage 5A) ────────────────────────────────────────────
    // Same pattern as follow/unfollow: one statement, the acting user's node write-locked first, the change and its
    // :SocialOutbox node(s) in the same transaction, occurredAt from the per-user socialClock, a no-op returns empty.
    // New outbox nodes name the pair sourceId/targetId (follow events keep followerId/followeeId).

    /**
     * Creates {@code (blocker)-[:BLOCKS]->(blocked)} if absent, deletes the FOLLOWS relationships in BOTH directions and
     * records, in the same transaction, a {@code UserUnfollowedEvent} per deleted direction plus the
     * {@code UserBlockedEvent}.
     *
     * <p>Both nodes are locked, always in userId order (so two crossing blocks cannot deadlock), before anything is
     * read: a concurrent follow/unfollow of either user (which locks its follower) is serialised with the block. Each
     * event takes a tick of its own follower's / blocker's clock: the blocked user's unfollow uses the blocked user's
     * clock (consumers order follow events per follower), the blocker's unfollow and the block take two consecutive
     * ticks of the blocker's clock.
     *
     * @return the block event's occurredAt (epoch millis); empty if already blocked or a node is missing
     */
    @Query("MATCH (a:User {userId: $blockerId}), (b:User {userId: $blockedId}) "
            + "FOREACH (n IN CASE WHEN a.userId < b.userId THEN [a, b] ELSE [b, a] END | "
            + "         SET n._lock = true REMOVE n._lock) "
            + "WITH a, b WHERE NOT EXISTS { (a)-[:BLOCKS]->(b) } "
            + "WITH a, b, COLLECT { MATCH (a)-[r:FOLLOWS]->(b) RETURN r } AS ab, "
            + "     COLLECT { MATCH (b)-[r:FOLLOWS]->(a) RETURN r } AS ba "
            + "WITH a, b, ab, ba, size(ab) > 0 AS hadAb, size(ba) > 0 AS hadBa, "
            + "     CASE WHEN a.socialClock IS NULL OR a.socialClock < $now THEN $now ELSE a.socialClock + 1 END AS ta, "
            + "     CASE WHEN b.socialClock IS NULL OR b.socialClock < $now THEN $now ELSE b.socialClock + 1 END AS tb "
            + "FOREACH (r IN ab | DELETE r) "
            + "FOREACH (r IN ba | DELETE r) "
            + "FOREACH (x IN CASE WHEN hadAb THEN [1] ELSE [] END | "
            + "         CREATE (:SocialOutbox {eventId: $unfollowEventId, type: 'UserUnfollowedEvent', "
            + "                 followerId: $blockerId, followeeId: $blockedId, occurredAt: ta})) "
            + "FOREACH (x IN CASE WHEN hadBa THEN [1] ELSE [] END | "
            + "         SET b.socialClock = tb "
            + "         CREATE (:SocialOutbox {eventId: $reverseUnfollowEventId, type: 'UserUnfollowedEvent', "
            + "                 followerId: $blockedId, followeeId: $blockerId, occurredAt: tb})) "
            + "WITH a, b, CASE WHEN hadAb THEN ta + 1 ELSE ta END AS at "
            + "SET a.socialClock = at "
            + "CREATE (a)-[:BLOCKS {since: at}]->(b) "
            + "CREATE (o:SocialOutbox {eventId: $eventId, type: 'UserBlockedEvent', "
            + "        sourceId: $blockerId, targetId: $blockedId, occurredAt: at}) "
            + "RETURN o.occurredAt")
    Optional<Long> block(String blockerId, String blockedId, String eventId, String unfollowEventId,
                         String reverseUnfollowEventId, long now);

    /**
     * Deletes {@code (blocker)-[:BLOCKS]->(blocked)} if present and records a {@code UserUnblockedEvent}. Follows are
     * not restored.
     *
     * @return the event's occurredAt (epoch millis) if a relationship was deleted; empty otherwise
     */
    @Query("MATCH (a:User {userId: $blockerId}) "
            + "SET a._lock = true REMOVE a._lock "
            + "WITH a MATCH (a)-[r:BLOCKS]->(:User {userId: $blockedId}) DELETE r "
            + "WITH a, count(r) AS removed "
            + "SET a.socialClock = CASE WHEN a.socialClock IS NULL OR a.socialClock < $now "
            + "                         THEN $now ELSE a.socialClock + 1 END "
            + "CREATE (o:SocialOutbox {eventId: $eventId, type: 'UserUnblockedEvent', "
            + "        sourceId: $blockerId, targetId: $blockedId, occurredAt: a.socialClock}) "
            + "RETURN o.occurredAt")
    Optional<Long> unblock(String blockerId, String blockedId, String eventId, long now);

    /**
     * Creates {@code (muter)-[:MUTES]->(muted)} if absent and records a {@code UserMutedEvent}. Follows are untouched.
     *
     * @return the event's occurredAt (epoch millis) if created; empty if already muted or a node is missing
     */
    @Query("MATCH (a:User {userId: $muterId}), (b:User {userId: $mutedId}) "
            + "SET a._lock = true REMOVE a._lock "
            + "WITH a, b WHERE NOT EXISTS { (a)-[:MUTES]->(b) } "
            + "SET a.socialClock = CASE WHEN a.socialClock IS NULL OR a.socialClock < $now "
            + "                         THEN $now ELSE a.socialClock + 1 END "
            + "CREATE (a)-[:MUTES {since: a.socialClock}]->(b) "
            + "CREATE (o:SocialOutbox {eventId: $eventId, type: 'UserMutedEvent', "
            + "        sourceId: $muterId, targetId: $mutedId, occurredAt: a.socialClock}) "
            + "RETURN o.occurredAt")
    Optional<Long> mute(String muterId, String mutedId, String eventId, long now);

    /**
     * Deletes {@code (muter)-[:MUTES]->(muted)} if present and records a {@code UserUnmutedEvent}.
     *
     * @return the event's occurredAt (epoch millis) if deleted; empty otherwise
     */
    @Query("MATCH (a:User {userId: $muterId}) "
            + "SET a._lock = true REMOVE a._lock "
            + "WITH a MATCH (a)-[r:MUTES]->(:User {userId: $mutedId}) DELETE r "
            + "WITH a, count(r) AS removed "
            + "SET a.socialClock = CASE WHEN a.socialClock IS NULL OR a.socialClock < $now "
            + "                         THEN $now ELSE a.socialClock + 1 END "
            + "CREATE (o:SocialOutbox {eventId: $eventId, type: 'UserUnmutedEvent', "
            + "        sourceId: $muterId, targetId: $mutedId, occurredAt: a.socialClock}) "
            + "RETURN o.occurredAt")
    Optional<Long> unmute(String muterId, String mutedId, String eventId, long now);

    /** True if either user blocks the other. */
    @Query("RETURN EXISTS { MATCH (:User {userId: $userId})-[:BLOCKS]-(:User {userId: $otherId}) }")
    boolean isBlockedEitherWay(String userId, String otherId);

    @Query("RETURN EXISTS { MATCH (:User {userId: $blockerId})-[:BLOCKS]->(:User {userId: $blockedId}) }")
    boolean isBlocking(String blockerId, String blockedId);

    @Query("RETURN EXISTS { MATCH (:User {userId: $muterId})-[:MUTES]->(:User {userId: $mutedId}) }")
    boolean isMuting(String muterId, String mutedId);

    /** Users {@code userId} blocks, by username. */
    @Query("MATCH (:User {userId: $userId})-[:BLOCKS]->(b:User) RETURN b ORDER BY b.username, b.userId")
    List<UserNode> findBlocked(String userId);

    /** Users {@code userId} mutes, by username. */
    @Query("MATCH (:User {userId: $userId})-[:MUTES]->(m:User) RETURN m ORDER BY m.username, m.userId")
    List<UserNode> findMuted(String userId);
}
