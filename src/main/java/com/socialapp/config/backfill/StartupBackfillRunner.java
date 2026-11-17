package com.socialapp.config.backfill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs every {@link StartupBackfill} once per application start, each on its own daemon thread, starting at
 * {@link ApplicationReadyEvent} (all beans initialised, incl. the Neo4j schema/constraints; web server and Kafka
 * listeners already running). Startup is therefore never delayed, and a completed backfill costs one marker lookup.
 *
 * <p>A failing run (e.g. a database not reachable yet) is retried every {@code app.backfill.retry-delay-ms} until it
 * completes or the application stops; progress is kept by the backfill itself. On {@link ContextClosedEvent} (before
 * any bean is destroyed) the runner asks the backfills to stop and waits briefly for them, so shutdown is not delayed
 * and no batch is cut off by a closing connection pool.
 *
 * <p>{@code app.backfill.enabled=false} disables the automatic run (the backfills stay callable).
 */
@Component
@Slf4j
public class StartupBackfillRunner {

    private static final long JOIN_TIMEOUT_MS = 5_000;

    private final List<StartupBackfill> backfills;
    private final boolean enabled;
    private final long retryDelayMs;

    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean stopping;

    public StartupBackfillRunner(List<StartupBackfill> backfills,
                                 @Value("${app.backfill.enabled:true}") boolean enabled,
                                 @Value("${app.backfill.retry-delay-ms:30000}") long retryDelayMs) {
        this.backfills = List.copyOf(backfills);
        this.enabled = enabled;
        this.retryDelayMs = retryDelayMs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!enabled) {
            log.info("Startup backfills disabled (app.backfill.enabled=false): {}", names());
            return;
        }
        if (stopping || !threads.isEmpty()) return;
        for (StartupBackfill backfill : backfills) {
            threads.add(Thread.ofPlatform().daemon().name("backfill-" + backfill.name())
                    .start(() -> runUntilDone(backfill)));
        }
    }

    private void runUntilDone(StartupBackfill backfill) {
        int failures = 0;
        while (!stopping) {
            long started = System.nanoTime();
            try {
                var outcome = backfill.run(() -> stopping);
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                if (outcome.alreadyDone()) log.debug("Backfill {}: {}", backfill.name(), outcome);
                else log.info("Backfill {} {} in {} ms", backfill.name(), outcome, ms);
                return;
            } catch (RuntimeException e) {
                if (stopping) {
                    log.debug("Backfill {} interrupted by shutdown", backfill.name(), e);
                    return;
                }
                failures++;
                // Progress is persisted per batch: the retry resumes, it does not start over.
                if (failures % 10 == 0) {
                    log.error("Backfill {} has failed {} times, retrying in {} ms", backfill.name(), failures,
                            retryDelayMs, e);
                } else {
                    log.warn("Backfill {} failed (attempt {}), retrying in {} ms: {}", backfill.name(), failures,
                            retryDelayMs, e.toString());
                }
                if (!sleep(retryDelayMs)) return;
            }
        }
    }

    /** @return false if interrupted / stopping */
    private boolean sleep(long ms) {
        long deadline = System.currentTimeMillis() + ms;
        try {
            while (!stopping && System.currentTimeMillis() < deadline) {
                Thread.sleep(Math.min(200, Math.max(1, deadline - System.currentTimeMillis())));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !stopping;
    }

    @EventListener(ContextClosedEvent.class)
    public void stop() {
        List<Thread> running;
        synchronized (this) {
            stopping = true;
            running = List.copyOf(threads);
        }
        long deadline = System.currentTimeMillis() + JOIN_TIMEOUT_MS;
        for (Thread t : running) {
            try {
                t.join(Math.max(1, deadline - System.currentTimeMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (t.isAlive()) {
                log.warn("Backfill thread {} did not stop within {} ms, interrupting it", t.getName(), JOIN_TIMEOUT_MS);
                t.interrupt();
            }
        }
    }

    private List<String> names() {
        return backfills.stream().map(StartupBackfill::name).toList();
    }
}
