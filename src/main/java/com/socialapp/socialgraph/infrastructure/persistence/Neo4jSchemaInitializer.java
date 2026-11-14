package com.socialapp.socialgraph.infrastructure.persistence;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Component;

/**
 * Spring Data Neo4j does not create constraints. Without a uniqueness constraint a concurrent
 * MERGE of the same userId (e.g. Kafka redelivery racing another consumer) can create duplicate
 * :User nodes, after which findByUserId fails with IncorrectResultSizeDataAccessException.
 *
 * Runs at bean initialisation, i.e. before Kafka listener containers start.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class Neo4jSchemaInitializer {

    private final Driver driver;

    @PostConstruct
    void createConstraints() {
        run("CREATE CONSTRAINT user_user_id_unique IF NOT EXISTS FOR (u:User) REQUIRE u.userId IS UNIQUE",
                "Could not create unique constraint on :User(userId) - clean up duplicate nodes and restart.");
        // SocialGraph's Neo4j-side outbox (see SocialOutboxForwarder): unique event ids, ordered scan.
        run("CREATE CONSTRAINT social_outbox_event_id_unique IF NOT EXISTS "
                        + "FOR (o:SocialOutbox) REQUIRE o.eventId IS UNIQUE",
                "Could not create unique constraint on :SocialOutbox(eventId).");
        run("CREATE INDEX social_outbox_occurred_at IF NOT EXISTS FOR (o:SocialOutbox) ON (o.occurredAt)",
                "Could not create index on :SocialOutbox(occurredAt).");
        // Completion markers of one-time backfills (FollowProjectionBackfill): MERGE on name must be race-safe.
        run("CREATE CONSTRAINT backfill_name_unique IF NOT EXISTS FOR (b:Backfill) REQUIRE b.name IS UNIQUE",
                "Could not create unique constraint on :Backfill(name).");
    }

    private void run(String cypher, String failureHint) {
        try (var session = driver.session()) {
            session.run(cypher).consume();
        } catch (Exception e) {
            // Typically: duplicate nodes already exist in a long-lived dev database.
            log.warn("{} Cause: {}", failureHint, e.getMessage());
        }
    }
}
