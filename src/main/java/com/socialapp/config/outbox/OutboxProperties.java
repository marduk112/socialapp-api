package com.socialapp.config.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code app.outbox.*} settings. The two intervals are also read directly by the {@code @Scheduled}
 * placeholders in {@link OutboxRelay} / {@link OutboxCleanup} (same keys, same defaults).
 *
 * @param pollIntervalMs     delay between relay runs (fixed delay); commits also wake the relay at once
 * @param batchSize          max rows published per relay transaction
 * @param maxAttempts        failed attempts after which a row-specific (poison) failure parks the row;
 *                           also the period of ERROR alerts while the relay is blocked by Kafka being down
 * @param sendTimeoutMs      bound of one synchronous Kafka send (also applied to the producer's
 *                           max.block.ms / request.timeout.ms / delivery.timeout.ms in KafkaConfig)
 * @param retentionDays      published rows older than this are deleted by {@link OutboxCleanup}
 * @param cleanupIntervalMs  delay between cleanup runs
 * @param cleanupBatchSize   rows deleted per cleanup statement
 */
@ConfigurationProperties("app.outbox")
public record OutboxProperties(
        @DefaultValue("300") long pollIntervalMs,
        @DefaultValue("100") int batchSize,
        @DefaultValue("10") int maxAttempts,
        @DefaultValue("5000") long sendTimeoutMs,
        @DefaultValue("7") int retentionDays,
        @DefaultValue("3600000") long cleanupIntervalMs,
        @DefaultValue("10000") int cleanupBatchSize
) {
    public OutboxProperties {
        if (batchSize < 1 || maxAttempts < 1 || sendTimeoutMs < 1 || retentionDays < 0 || cleanupBatchSize < 1) {
            throw new IllegalArgumentException("Invalid app.outbox settings");
        }
    }

    /** Defaults, for tests and manual wiring. */
    public static OutboxProperties defaults() {
        return new OutboxProperties(300, 100, 10, 5000, 7, 3_600_000, 10_000);
    }
}
