package com.socialapp.socialgraph.infrastructure.backfill;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Neo4j side of {@link FollowProjectionBackfill}: the durable {@code :Backfill {name}} marker (unique name, see
 * {@code Neo4jSchemaInitializer}) holding completion flag and keyset cursor, and the per-follower emission statement.
 * Every method is one Neo4j transaction.
 */
@Component
public class FollowBackfillStore {

    /** Marker state: {@code cursor} = userId of the last follower handled ("" = none yet). */
    public record Marker(String cursor, boolean completed, long followers, long events) {}

    private final Neo4jClient neo4j;

    public FollowBackfillStore(Neo4jClient neo4j) {
        this.neo4j = neo4j;
    }

    /**
     * Creates the marker if absent (race-safe: MERGE on a uniquely constrained property) and returns it. Every
     * property is set on creation, so later statements never name a missing property key.
     */
    @Transactional("neo4jTransactionManager")
    public Marker start(String name, long now) {
        return neo4j.query("""
                        MERGE (m:Backfill {name: $name})
                        ON CREATE SET m.startedAt = $now, m.updatedAt = $now, m.cursor = '', m.completed = false,
                                      m.followers = 0, m.events = 0
                        RETURN m""")
                .bindAll(Map.of("name", name, "now", now))
                .fetchAs(Marker.class).mappedBy((types, r) -> marker(r.get("m").asNode()))
                .one().orElseThrow();
    }

    @Transactional(value = "neo4jTransactionManager", readOnly = true)
    public Marker marker(String name) {
        return neo4j.query("MATCH (m:Backfill {name: $name}) RETURN m")
                .bind(name).to("name")
                .fetchAs(Marker.class).mappedBy((types, r) -> marker(r.get("m").asNode()))
                .one().orElseThrow(() -> new IllegalStateException("Backfill marker " + name + " missing"));
    }

    private static Marker marker(org.neo4j.driver.types.Node m) {
        return new Marker(m.get("cursor").asString(""), m.get("completed").asBoolean(false),
                m.get("followers").asLong(0), m.get("events").asLong(0));
    }

    /**
     * Whether the FOLLOWS relationship type exists at all. A database without it has nothing to backfill, and asking
     * for it would only make Neo4j log an UnknownRelationshipTypeWarning (fresh installs, tests).
     */
    @Transactional(value = "neo4jTransactionManager", readOnly = true)
    public boolean followsTypeExists() {
        return neo4j.query("CALL db.relationshipTypes() YIELD relationshipType "
                        + "WHERE relationshipType = 'FOLLOWS' RETURN count(*) > 0 AS present")
                .fetchAs(Boolean.class).one().orElse(false);
    }

    /** UserIds (ascending) of up to {@code limit} users after {@code afterUserId} that follow at least one user. */
    @Transactional(value = "neo4jTransactionManager", readOnly = true)
    public List<String> nextFollowers(String afterUserId, int limit) {
        return List.copyOf(neo4j.query("""
                        MATCH (a:User)
                        WHERE a.userId > $after AND EXISTS { (a)-[:FOLLOWS]->(:User) }
                        RETURN a.userId AS id
                        ORDER BY id
                        LIMIT $limit""")
                .bindAll(Map.of("after", afterUserId, "limit", limit))
                .fetchAs(String.class).all());
    }

    /**
     * Emits one {@code UserFollowedEvent} {@code :SocialOutbox} node per FOLLOWS relationship of {@code followerId}
     * as it is right now, in ONE transaction:
     * <ol>
     *   <li>write-locks the marker (serialises instances) and proceeds only if the backfill is not complete and the
     *       cursor is still before this follower (otherwise another instance already handled it); advances the
     *       cursor;</li>
     *   <li>write-locks the follower node exactly like {@code UserNodeRepository.follow/unfollow} (so no live
     *       follow/unfollow of this follower can interleave), reads its current relationships and takes
     *       {@code k} ticks of the same per-follower clock {@code socialClock = max(now, previous + 1)}: events get
     *       {@code base, base+1, ..., base+k-1}, the clock ends at {@code base+k-1}.</li>
     * </ol>
     * Lock order is always marker -> follower; live follow/unfollow locks follower -> followee and never the marker,
     * and this transaction locks no other user node, so no lock cycle (deadlock) is possible.
     *
     * @return number of events created (0 if skipped or the follower follows nobody any more)
     */
    @Transactional("neo4jTransactionManager")
    public long emitCurrentFollows(String name, String followerId, long now) {
        return neo4j.query("""
                        MATCH (m:Backfill {name: $name})
                        SET m._lock = true REMOVE m._lock
                        WITH m WHERE m.completed = false AND m.cursor < $followerId
                        SET m.cursor = $followerId, m.updatedAt = $now
                        WITH m
                        MATCH (a:User {userId: $followerId})
                        SET a._lock = true REMOVE a._lock
                        WITH m, a
                        MATCH (a)-[:FOLLOWS]->(b:User)
                        WITH m, a, b.userId AS followeeId ORDER BY followeeId
                        WITH m, a, collect(followeeId) AS followees
                        WITH m, a, followees,
                             CASE WHEN a.socialClock IS NULL OR a.socialClock < $now
                                  THEN $now ELSE a.socialClock + 1 END AS base
                        SET a.socialClock = base + size(followees) - 1,
                            m.followers = m.followers + 1, m.events = m.events + size(followees)
                        WITH a, followees, base
                        UNWIND range(0, size(followees) - 1) AS i
                        CREATE (:SocialOutbox {eventId: randomUUID(), type: 'UserFollowedEvent', followerId: a.userId,
                                               followeeId: followees[i], occurredAt: base + i})
                        RETURN count(*) AS created""")
                .bindAll(Map.of("name", name, "followerId", followerId, "now", now))
                .fetchAs(Long.class).one().orElse(0L);
    }

    /** Sets the completion marker (once). */
    @Transactional("neo4jTransactionManager")
    public void complete(String name, long now) {
        neo4j.query("""
                        MATCH (m:Backfill {name: $name})
                        SET m._lock = true REMOVE m._lock
                        WITH m WHERE m.completed = false
                        SET m.completed = true, m.completedAt = $now, m.updatedAt = $now""")
                .bindAll(Map.of("name", name, "now", now))
                .run();
    }
}
