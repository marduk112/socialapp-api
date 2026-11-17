package com.socialapp.config.outbox;

import com.socialapp.shared.events.DomainEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Imports domain events that were recorded <em>atomically with a change in another store</em> (today:
 * SocialGraph's follow/unfollow, recorded as {@code :SocialOutbox} nodes in the same Neo4j transaction)
 * into {@code outbox_events}, from where {@link OutboxRelay} publishes them like any other event.
 *
 * <p>Contract (what makes the two-store hand-over loss-free):
 * <ul>
 *   <li>The caller reads pending events from its store, calls {@link #importBatch}, and deletes them from
 *       its store <em>only if</em> it returned true (the Postgres transaction committed).</li>
 *   <li>Insertion is idempotent by {@code eventId} ({@code ON CONFLICT DO NOTHING}), so a crash between
 *       this commit and the caller's delete just makes the next run offer the same events again, harmlessly.
 *       (If an event is re-offered after its published row was purged by {@link OutboxCleanup}, i.e. more
 *       than {@code retention-days} later, it is published a second time: still safe for idempotent
 *       consumers.)</li>
 *   <li>Runs are serialised across instances by a Postgres advisory lock; {@code false} means another
 *       instance is importing right now (the caller simply retries on its next run).</li>
 * </ul>
 * Not a {@code @Transactional} bean: it opens its own JPA transaction, so it is never accidentally joined to
 * a caller's (e.g. Neo4j) transaction.
 */
@Slf4j
public class OutboxImporter {

    private final OutboxRepository repository;
    private final OutboxEventWriter writer;
    private final OutboxRelay relay;
    private final TransactionOperations transactions;

    public OutboxImporter(OutboxRepository repository, OutboxEventWriter writer, OutboxRelay relay,
                          TransactionOperations transactions) {
        this.repository = repository;
        this.writer = writer;
        this.relay = relay;
        this.transactions = transactions;
    }

    /**
     * Stores {@code events} in the outbox in the given order, in one Postgres transaction.
     *
     * @return true once committed (every event is now in the outbox, new or already present); false if
     *         another instance holds the import lock (nothing written)
     * @throws RuntimeException if the transaction failed (nothing written; retry later)
     */
    public boolean importBatch(List<? extends DomainEvent> events) {
        if (events.isEmpty()) return true;
        Boolean committed = transactions.execute(status -> {
            if (!repository.tryAcquireImportLock()) {
                log.trace("Outbox import lock held by another instance");
                return false;
            }
            int inserted = 0;
            for (DomainEvent event : events) {
                if (repository.appendIfAbsent(writer.toMessage(event))) inserted++;
            }
            if (inserted > 0 && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        relay.wakeUp();
                    }
                });
            }
            log.debug("Outbox import: {} event(s) offered, {} new", events.size(), inserted);
            return true;
        });
        return Boolean.TRUE.equals(committed);
    }
}
