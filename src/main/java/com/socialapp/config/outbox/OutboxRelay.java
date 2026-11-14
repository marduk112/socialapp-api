package com.socialapp.config.outbox;

import com.socialapp.shared.events.DomainEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.InvalidRecordException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionOperations;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Read side of the transactional outbox: publishes {@code outbox_events} rows to Kafka.
 *
 * <h2>Ordering</h2>
 * Each run is one DB transaction that first takes a Postgres transaction-scoped advisory lock
 * ({@code pg_try_advisory_xact_lock}); if another instance holds it, this run does nothing. So exactly one
 * relay publishes at a time, strictly in {@code id} order, which preserves per-key (in fact global) order.
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} was rejected: it lets several instances publish disjoint
 * batches concurrently, so a later event of a key can overtake an earlier one held by another instance
 * (or one that is being retried). Ids reflect per-aggregate commit order because the writer flushes the
 * aggregate (taking its row lock) before drawing the id; see {@link OutboxRepository#append}.
 *
 * <h2>Delivery</h2>
 * Synchronous send (bounded by {@code app.outbox.send-timeout-ms}), then {@code published_at} is stamped.
 * The first failure stops the batch (nothing after it is sent in that run) and is recorded in
 * {@code attempts}/{@code last_error}; the row is retried next run. Published stamps of the batch commit
 * together, so a crash mid-batch re-sends those rows: delivery is at-least-once (consumers are idempotent /
 * order-tolerant, and every message keeps the eventId assigned by the aggregate for de-duplication).
 *
 * <h2>Failures and poison rows</h2>
 * <ul>
 *   <li>Infrastructure failures (broker down, timeouts, authorization, unknown topic, anything not
 *       listed below) are not the row's fault: the relay blocks (keeps retrying the head row every poll,
 *       backlog preserved, order intact) and alerts at ERROR every {@code max-attempts} failed attempts.
 *       Such rows are never parked - parking on an outage would park healthy events.</li>
 *   <li>Row-specific failures (payload unreadable, serialization error, record too large / invalid) can
 *       never succeed. After {@code max-attempts} the row is <em>parked</em> ({@code parked_at}, logged at
 *       ERROR). A parked row blocks only rows with the same topic+key (they wait behind it, so per-key
 *       order is never broken); all other keys keep flowing. Repair by fixing the row and resetting
 *       {@code parked_at = NULL} (it is then retried), or by setting {@code published_at} to skip it
 *       deliberately (accepting the gap for that key).</li>
 * </ul>
 *
 * <h2>Latency and shutdown</h2>
 * Runs every {@code app.outbox.poll-interval-ms} on the Spring scheduler, and additionally right after any
 * commit that wrote an outbox row ({@link #wakeUp()}), so events are normally published within
 * milliseconds. On {@link ContextClosedEvent} (published before lifecycle beans and the scheduler stop) the
 * relay stops taking new work and interrupts a run in progress (see {@link #stop()}).
 */
@Slf4j
public class OutboxRelay {

    private static final int MAX_ERROR_LENGTH = 2000;

    private final OutboxRepository repository;
    private final KafkaTemplate<String, DomainEvent> kafkaTemplate;
    private final TransactionOperations transactions;
    private final DomainEventCodec codec;
    private final OutboxProperties properties;
    private final Executor wakeUpExecutor;

    private final ReentrantLock running = new ReentrantLock();
    private final AtomicBoolean wakeUpQueued = new AtomicBoolean();
    private volatile boolean rerunRequested;
    private volatile boolean failing;
    private volatile boolean stopping;
    private final Object threadMonitor = new Object();
    private Thread pollThread; // guarded by threadMonitor; interrupted only while a run is in progress

    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, DomainEvent> kafkaTemplate,
                       TransactionOperations transactions, DomainEventCodec codec,
                       OutboxProperties properties, Executor wakeUpExecutor) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactions = transactions;
        this.codec = codec;
        this.properties = properties;
        this.wakeUpExecutor = wakeUpExecutor;
    }

    /** Scheduled run. Never runs concurrently with itself in one JVM. */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:300}",
               initialDelayString = "${app.outbox.poll-interval-ms:300}")
    public void poll() {
        if (stopping) return;
        if (!running.tryLock()) {
            rerunRequested = true; // the running poll will go round once more
            return;
        }
        synchronized (threadMonitor) {
            pollThread = Thread.currentThread();
        }
        try {
            Outcome outcome;
            do {
                rerunRequested = false;
                outcome = drain();
            } while (rerunRequested && outcome != Outcome.FAILED && !stopping);
            failing = outcome == Outcome.FAILED;
        } catch (RuntimeException e) {
            // e.g. database unavailable: keep the scheduled task alive, retry next poll
            failing = true;
            if (stopping) log.debug("Outbox relay run aborted by shutdown", e);
            else log.error("Outbox relay run failed, will retry", e);
        } finally {
            synchronized (threadMonitor) {
                pollThread = null;
            }
            if (stopping) Thread.interrupted(); // clear our own shutdown interrupt before returning the thread
            running.unlock();
        }
    }

    /**
     * Asks for a run as soon as possible (called after a commit that wrote outbox rows). Coalesced: at most
     * one queued wake-up at a time. Ignored while the relay is failing (retries then happen only at the poll
     * interval, not at the commit rate). A rejected submission (scheduler shutting down) is ignored too -
     * the row is durable and will be published by the next run/instance.
     */
    public void wakeUp() {
        if (stopping || failing || !wakeUpQueued.compareAndSet(false, true)) return;
        try {
            wakeUpExecutor.execute(() -> {
                wakeUpQueued.set(false);
                if (!failing) poll();
            });
        } catch (RejectedExecutionException | IllegalStateException e) { // incl. TaskRejectedException
            wakeUpQueued.set(false);
            log.debug("Outbox wake-up rejected (shutting down?)", e);
        }
    }

    /**
     * Stops taking work and interrupts a run in progress, so shutdown never waits for a blocked send or
     * connection acquisition (Kafka or DB down). An interrupted run rolls back or commits what it already
     * stamped; unstamped rows are simply published again later (at-least-once).
     */
    @EventListener(ContextClosedEvent.class)
    public void stop() {
        if (stopping) return;
        stopping = true;
        log.info("Outbox relay stopping");
        synchronized (threadMonitor) {
            if (pollThread != null) pollThread.interrupt();
        }
    }

    private enum Outcome { MORE, DONE, FAILED }

    /** Publishes batches until the backlog is empty, a failure occurs, or the lock is held elsewhere. */
    private Outcome drain() {
        Outcome outcome = Outcome.DONE;
        while (!stopping) {
            outcome = transactions.execute(status -> publishBatch());
            if (outcome != Outcome.MORE) return outcome;
        }
        return outcome == Outcome.FAILED ? outcome : Outcome.DONE;
    }

    /** One transaction: lock, read a batch in id order, send one by one, stamp. */
    private Outcome publishBatch() {
        if (!repository.tryAcquireRelayLock()) {
            log.trace("Outbox relay lock held by another instance");
            return Outcome.DONE;
        }
        var rows = repository.findPublishable(properties.batchSize());
        for (var row : rows) {
            if (stopping) return Outcome.DONE;
            try {
                publish(row);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Outcome.DONE; // shutting down; the row stays unpublished and is retried later
            } catch (Exception e) {
                if (stopping) return Outcome.DONE; // failure caused by the shutdown interrupt
                recordFailure(row, e);
                return Outcome.FAILED; // stop at the first failure: nothing may overtake this row
            }
            repository.markPublished(row.id());
        }
        return rows.size() == properties.batchSize() ? Outcome.MORE : Outcome.DONE;
    }

    private void publish(OutboxRepository.PendingMessage row) throws Exception {
        DomainEvent event = codec.fromJson(row.payload());
        try {
            var result = kafkaTemplate.send(row.topic(), row.messageKey(), event)
                    .get(properties.sendTimeoutMs() + 1_000, TimeUnit.MILLISECONDS);
            if (result != null && log.isDebugEnabled()) {
                var md = result.getRecordMetadata();
                log.debug("Published outbox id={} {} eventId={} to {}-{}@{}", row.id(), row.eventType(),
                        row.eventId(), md.topic(), md.partition(), md.offset());
            }
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    private void recordFailure(OutboxRepository.PendingMessage row, Exception e) {
        int attempts = row.attempts() + 1;
        boolean poison = isRowSpecific(e);
        boolean park = poison && attempts >= properties.maxAttempts();
        repository.markFailed(row.id(), attempts, describe(e), park);

        if (park) {
            log.error("Outbox row PARKED after {} attempts (unpublishable): id={} topic={} key={} type={} "
                            + "eventId={}. Later events with this key are held back until it is resolved.",
                    attempts, row.id(), row.topic(), row.messageKey(), row.eventType(), row.eventId(), e);
        } else if (!poison && attempts % properties.maxAttempts() == 0) {
            log.error("Kafka publish FAILING for {} attempts, outbox relay BLOCKED (events kept, order "
                            + "preserved): id={} topic={} key={} type={} eventId={}",
                    attempts, row.id(), row.topic(), row.messageKey(), row.eventType(), row.eventId(), e);
        } else {
            log.warn("Kafka publish failed (attempt {}), will retry: id={} topic={} key={} type={} eventId={}: {}",
                    attempts, row.id(), row.topic(), row.messageKey(), row.eventType(), row.eventId(), describe(e));
        }
    }

    /** Failures that will recur for this row no matter how often it is retried. */
    static boolean isRowSpecific(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof DomainEventCodec.OutboxPayloadException
                    || t instanceof SerializationException
                    || t instanceof RecordTooLargeException
                    || t instanceof InvalidRecordException) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String text = root == e ? e.toString() : e + " <- " + root;
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
