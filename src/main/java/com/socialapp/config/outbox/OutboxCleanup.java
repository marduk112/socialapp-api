package com.socialapp.config.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;

/**
 * Deletes published outbox rows older than {@code app.outbox.retention-days}, in chunks of
 * {@code cleanup-batch-size} (each chunk auto-commits, so no long-running delete). Unpublished and parked
 * rows are never deleted, whatever their age.
 */
@Slf4j
public class OutboxCleanup {

    private final OutboxRepository repository;
    private final OutboxProperties properties;
    private final Clock clock;
    private volatile boolean stopping;

    public OutboxCleanup(OutboxRepository repository, OutboxProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.outbox.cleanup-interval-ms:3600000}", initialDelay = 60_000)
    public void cleanUp() {
        try {
            int total = deleteExpired();
            if (total > 0) log.info("Outbox cleanup: deleted {} published row(s)", total);
        } catch (RuntimeException e) {
            log.warn("Outbox cleanup failed, will retry next run", e);
        }
    }

    /** @return number of rows deleted */
    public int deleteExpired() {
        var cutoff = clock.instant().minus(Duration.ofDays(properties.retentionDays()));
        int total = 0;
        int deleted;
        do {
            deleted = repository.deletePublishedBefore(cutoff, properties.cleanupBatchSize());
            total += deleted;
        } while (deleted == properties.cleanupBatchSize() && !stopping);
        return total;
    }

    @EventListener(ContextClosedEvent.class)
    public void stop() {
        stopping = true;
    }
}
