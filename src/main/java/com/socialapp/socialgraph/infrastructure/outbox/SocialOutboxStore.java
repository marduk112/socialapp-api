package com.socialapp.socialgraph.infrastructure.outbox;

import org.neo4j.driver.Value;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Access to the Neo4j side of SocialGraph's outbox: {@code :SocialOutbox} nodes, created by
 * {@code UserNodeRepository} (follow/unfollow, block/unblock, mute/unmute) in the same transaction as the graph change. Each statement
 * runs in its own auto-commit transaction (no Spring transaction is involved).
 */
@Component
public class SocialOutboxStore {

    /**
     * A pending event as recorded in Neo4j (raw values; validated by the forwarder). {@code sourceId} / {@code targetId}
     * are the pair of the relationship: follower / followee for follow events (node properties {@code followerId} /
     * {@code followeeId}), blocker / blocked and muter / muted for the stage 5A events (properties {@code sourceId} /
     * {@code targetId}).
     */
    public record PendingSocialEvent(String eventId, String type, String sourceId, String targetId,
                                     Long occurredAt) {}

    private final Neo4jClient neo4j;

    public SocialOutboxStore(Neo4jClient neo4j) {
        this.neo4j = neo4j;
    }

    /**
     * Oldest first (occurredAt is strictly increasing per follower in graph commit order). Returns whole
     * nodes rather than named properties: on a database where no follow happened yet, naming property keys
     * that do not exist makes Neo4j log an UnknownPropertyKeyWarning on every poll.
     */
    public List<PendingSocialEvent> findPending(int limit) {
        return List.copyOf(neo4j.query("""
                        MATCH (o:SocialOutbox)
                        RETURN o
                        ORDER BY o.occurredAt, o.eventId
                        LIMIT $limit""")
                .bind(limit).to("limit")
                .fetchAs(PendingSocialEvent.class)
                .mappedBy((types, r) -> {
                    var o = r.get("o").asNode();
                    return new PendingSocialEvent(text(o.get("eventId")), text(o.get("type")),
                            o.containsKey("sourceId") ? text(o.get("sourceId")) : text(o.get("followerId")),
                            o.containsKey("targetId") ? text(o.get("targetId")) : text(o.get("followeeId")),
                            o.get("occurredAt").asObject() instanceof Long at ? at : null);
                })
                .all());
    }

    /** Lenient on purpose: a malformed node must be skipped by the forwarder, not fail the whole batch. */
    private static String text(Value value) {
        Object raw = value.asObject();
        return raw == null ? null : raw.toString();
    }

    /** Removes forwarded events. Idempotent. */
    public void delete(Collection<String> eventIds) {
        if (eventIds.isEmpty()) return;
        neo4j.query("MATCH (o:SocialOutbox) WHERE o.eventId IN $ids DELETE o")
                .bindAll(Map.of("ids", List.copyOf(eventIds)))
                .run();
    }

    /** Number of events not yet forwarded (monitoring/tests). */
    public long countPending() {
        return neo4j.query("MATCH (o:SocialOutbox) RETURN count(o) AS n")
                .fetchAs(Long.class).one().orElse(0L);
    }
}
