package com.socialapp.feed.application.media;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code app.media.*} settings (images in posts, stage 3C).
 *
 * @param dir                directory of the local-disk storage ({@code LocalDiskMediaStorage}); files are served under
 *                           {@code /uploads/media/<key>}
 * @param maxBytes           largest accepted upload (413 above it). {@code spring.servlet.multipart.max-file-size} must
 *                           be at least this big, otherwise the multipart parser rejects first (also 413)
 * @param maxPendingPerUser  unattached uploads a user may hold at once (429 above it)
 * @param unattachedTtl      unattached uploads older than this are deleted (row + file) by the cleanup job
 * @param cleanupEnabled     run the cleanup job on a schedule ({@code MediaCleanupJob}); {@code MediaService.purgeExpired}
 *                           can always be called directly
 * @param cleanupIntervalMs  delay between cleanup runs; also read by the {@code @Scheduled} placeholder
 * @param cleanupBatchSize   rows deleted per cleanup transaction
 */
@ConfigurationProperties("app.media")
public record MediaProperties(
        @DefaultValue("./uploads/media") String dir,
        @DefaultValue("5242880") long maxBytes,
        @DefaultValue("20") int maxPendingPerUser,
        @DefaultValue("24h") Duration unattachedTtl,
        @DefaultValue("true") boolean cleanupEnabled,
        @DefaultValue("3600000") long cleanupIntervalMs,
        @DefaultValue("500") int cleanupBatchSize
) {
    public MediaProperties {
        if (dir == null || dir.isBlank() || maxBytes < 1 || maxPendingPerUser < 1 || unattachedTtl == null
                || unattachedTtl.isNegative() || cleanupBatchSize < 1) {
            throw new IllegalArgumentException("Invalid app.media settings");
        }
    }

    /** Defaults, for tests and manual wiring. */
    public static MediaProperties defaults() {
        return new MediaProperties("./uploads/media", 5L * 1024 * 1024, 20, Duration.ofHours(24), true, 3_600_000, 500);
    }
}
