package com.socialapp.socialgraph.infrastructure.outbox;

import com.socialapp.config.outbox.OutboxImporter;
import com.socialapp.shared.events.DomainEvent;
import com.socialapp.shared.events.UserFollowedEvent;
import com.socialapp.shared.events.UserUnfollowedEvent;
import com.socialapp.socialgraph.application.usecase.FollowGraphChanged;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Moves SocialGraph's pending events from Neo4j ({@code :SocialOutbox} nodes) to the Postgres outbox, from
 * where {@code OutboxRelay} publishes them to {@code social-events}.
 *
 * <h2>Why this exists (dual write between Neo4j and Postgres)</h2>
 * The follow graph lives in Neo4j, the outbox in Postgres; there is no transaction spanning both. So the
 * event is first recorded <em>in Neo4j, in the same transaction as the graph change</em> (Neo4j-side
 * outbox), and this forwarder hands it over:
 * <ol>
 *   <li>read pending nodes (oldest first),</li>
 *   <li>{@link OutboxImporter#importBatch}: insert them into {@code outbox_events} in one Postgres
 *       transaction, idempotently by eventId,</li>
 *   <li>only after that commit, delete the nodes from Neo4j.</li>
 * </ol>
 * A crash/failure before 2 commits leaves the nodes in Neo4j (retried next run); a crash between 2 and 3
 * re-offers events that are already in the outbox, which {@code ON CONFLICT (event_id) DO NOTHING} ignores.
 * Hence: every committed graph change yields exactly one outbox row (at-least-once on Kafka), and a
 * rolled-back change yields none. The only residual effect of failures is delay.
 *
 * <p>Runs every {@code app.outbox.poll-interval-ms} and right after each graph-changing commit
 * ({@link FollowGraphChanged}, AFTER_COMMIT). Never concurrently with itself in one JVM; across instances
 * the import is serialised by an advisory lock and idempotent anyway.
 */
@Component
@Slf4j
public class SocialOutboxForwarder {

    private final SocialOutboxStore store;
    private final OutboxImporter importer;
    private final Executor wakeUpExecutor;
    private final int batchSize;

    private final ReentrantLock running = new ReentrantLock();
    private final AtomicBoolean wakeUpQueued = new AtomicBoolean();
    private volatile boolean rerunRequested;
    private volatile boolean stopping;

    @Autowired
    public SocialOutboxForwarder(SocialOutboxStore store, OutboxImporter importer, TaskScheduler taskScheduler,
                                 @Value("${app.outbox.batch-size:100}") int batchSize) {
        this(store, importer, task -> taskScheduler.schedule(task, Instant.now()), batchSize);
    }

    SocialOutboxForwarder(SocialOutboxStore store, OutboxImporter importer, Executor wakeUpExecutor, int batchSize) {
        this.store = store;
        this.importer = importer;
        this.wakeUpExecutor = wakeUpExecutor;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:300}",
               initialDelayString = "${app.outbox.poll-interval-ms:300}")
    public void poll() {
        if (stopping) return;
        if (!running.tryLock()) {
            rerunRequested = true;
            return;
        }
        try {
            do {
                rerunRequested = false;
                forwardPending();
            } while (rerunRequested && !stopping);
        } catch (RuntimeException e) {
            // Neo4j or Postgres unavailable: events stay in Neo4j and are forwarded by a later run.
            if (stopping) log.debug("Social outbox forwarding aborted by shutdown", e);
            else log.warn("Social outbox forwarding failed, will retry: {}", e.toString());
        } finally {
            running.unlock();
        }
    }

    /** Latency optimisation: forward right after a graph change commits instead of at the next poll. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGraphChanged(FollowGraphChanged signal) {
        wakeUp();
    }

    void wakeUp() {
        if (stopping || !wakeUpQueued.compareAndSet(false, true)) return;
        try {
            wakeUpExecutor.execute(() -> {
                wakeUpQueued.set(false);
                poll();
            });
        } catch (RejectedExecutionException | IllegalStateException e) {
            wakeUpQueued.set(false);
            log.debug("Social outbox wake-up rejected (shutting down?)", e);
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void stop() {
        stopping = true;
    }

    /**
     * Forwards pending events batch by batch until none are left (or another instance is importing).
     *
     * @return number of events handed over to the Postgres outbox
     */
    int forwardPending() {
        int total = 0;
        while (!stopping) {
            var pending = store.findPending(batchSize);
            if (pending.isEmpty()) break;

            List<DomainEvent> events = new ArrayList<>(pending.size());
            List<String> ids = new ArrayList<>(pending.size());
            for (var p : pending) {
                DomainEvent event = toEvent(p);
                if (event != null) {
                    events.add(event);
                    ids.add(p.eventId());
                }
            }
            if (events.isEmpty()) break; // only unreadable rows left (logged); nothing more to do now
            if (!importer.importBatch(events)) break; // another instance is importing; retry next run
            store.delete(ids);
            total += events.size();
            log.debug("Forwarded {} social event(s) to the outbox", events.size());
            if (pending.size() < batchSize) break;
        }
        return total;
    }

    /** @return the event, or null (logged at ERROR, left in Neo4j for repair) if the node is malformed */
    static DomainEvent toEvent(SocialOutboxStore.PendingSocialEvent p) {
        try {
            var eventId = UUID.fromString(p.eventId());
            var occurredAt = Instant.ofEpochMilli(p.occurredAt());
            var follower = UUID.fromString(p.followerId());
            var followee = UUID.fromString(p.followeeId());
            return switch (p.type()) {
                case "UserFollowedEvent" -> new UserFollowedEvent(eventId, occurredAt, follower, followee);
                case "UserUnfollowedEvent" -> new UserUnfollowedEvent(eventId, occurredAt, follower, followee);
                default -> throw new IllegalArgumentException("unknown type " + p.type());
            };
        } catch (RuntimeException e) { // incl. NPE for missing properties
            log.error("Unreadable :SocialOutbox node {} left in Neo4j for repair: {}", p, e.toString());
            return null;
        }
    }
}
