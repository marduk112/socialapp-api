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
     * transaction.
     *
     * @return the event's occurredAt (epoch millis) if the relationship was created; empty if it already
     *         existed or a node is missing
     */
    @Query("MATCH (a:User {userId: $followerId}), (b:User {userId: $followeeId}) "
            + "SET a._lock = true REMOVE a._lock "
            + "WITH a, b WHERE NOT EXISTS { (a)-[:FOLLOWS]->(b) } "
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
}
