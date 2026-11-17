package com.socialapp.socialgraph.infrastructure.backfill;

import com.socialapp.config.backfill.StartupBackfill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.function.BooleanSupplier;

/**
 * One-time backfill of Feed's {@code follows} projection for FOLLOWS relationships created in Neo4j before stage 2A
 * (they never produced a {@code UserFollowedEvent}, so they are missing from the Following timeline).
 *
 * <p>For each follower (keyset over {@code userId}, {@code batch-size} per read) one Neo4j transaction takes the same
 * follower-node lock and per-follower clock as the live path and records one {@code UserFollowedEvent}
 * {@code :SocialOutbox} node per current FOLLOWS relationship ({@link FollowBackfillStore#emitCurrentFollows}).
 * From there the normal path delivers them: {@code SocialOutboxForwarder} -> Postgres outbox -> {@code OutboxRelay} ->
 * {@code social-events} -> {@code FollowProjection} (newest {@code occurredAt} wins per pair).
 *
 * <h2>Why this can never resurrect an unfollow (or lose a follow)</h2>
 * An emitted event says "active at tick t", where t is taken from the follower's {@code socialClock} while the
 * follower node is write-locked and the relationship is read under that lock. Every live follow/unfollow of that
 * follower takes the same lock and the next tick, so:
 * <ul>
 *   <li>an unfollow committed before our transaction: the relationship is gone, nothing is emitted for it;</li>
 *   <li>an unfollow committed after it gets a tick &gt; t, so the projection keeps the unfollow whatever the delivery
 *       order (Kafka redelivery, reordering, the backfill event arriving last);</li>
 *   <li>a follow made through the API before or after: both events say "active"; a later unfollow again gets a
 *       larger tick.</li>
 * </ul>
 * The projection therefore converges to the graph's current state for every pair the backfill touched, and pairs it
 * did not touch are handled by the live events. Running it again (marker reset) only re-emits "active" events at
 * newer ticks for relationships that exist at that moment: harmless.
 *
 * <h2>Completion, resumption, several instances</h2>
 * Durable marker {@code (:Backfill {name})} in Neo4j with {@code completed} and a {@code cursor} (last follower
 * handled), advanced in the same transaction as that follower's events: restarts resume, a completed backfill only
 * costs one lookup. The emission statement locks the marker first and skips a follower the cursor has already passed,
 * so two instances running at once interleave followers without duplicating work (lock order marker -> follower,
 * never the reverse: no deadlock with each other or with live traffic). Re-run: {@code MATCH (m:Backfill {name:
 * 'follows-projection-stage2a'}) DETACH DELETE m} and restart.
 */
@Component
@Slf4j
public class FollowProjectionBackfill implements StartupBackfill {

    public static final String NAME = "follows-projection-stage2a";

    private final FollowBackfillStore store;
    private final int batchSize;

    public FollowProjectionBackfill(FollowBackfillStore store,
                                    @Value("${app.backfill.follows.batch-size:500}") int batchSize) {
        if (batchSize < 1) throw new IllegalArgumentException("batch size must be >= 1");
        this.store = store;
        this.batchSize = batchSize;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Outcome run(BooleanSupplier stopRequested) {
        if (store.start(NAME, System.currentTimeMillis()).completed()) return Outcome.done();
        long followers = 0, events = 0;
        while (store.followsTypeExists()) { // no FOLLOWS type at all (fresh database): nothing to backfill
            if (stopRequested.getAsBoolean()) return new Outcome(false, true, followers, events);
            var marker = store.marker(NAME); // shared cursor: another instance may have advanced it
            if (marker.completed()) break;
            var batch = store.nextFollowers(marker.cursor(), batchSize);
            for (String followerId : batch) {
                if (stopRequested.getAsBoolean()) return new Outcome(false, true, followers, events);
                long created = store.emitCurrentFollows(NAME, followerId, System.currentTimeMillis());
                if (created > 0) {
                    followers++;
                    events += created;
                }
            }
            if (batch.size() < batchSize) break;
        }
        store.complete(NAME, System.currentTimeMillis());
        log.info("Follow backfill: {} follower(s), {} UserFollowedEvent(s) recorded in the social outbox",
                followers, events);
        return new Outcome(false, false, followers, events);
    }
}
