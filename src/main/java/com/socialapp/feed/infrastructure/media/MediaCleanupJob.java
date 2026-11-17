package com.socialapp.feed.infrastructure.media;

import com.socialapp.feed.application.media.MediaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedules {@link MediaService#purgeExpired} every {@code app.media.cleanup-interval-ms} (first run after the same
 * delay, so a short-lived process - e.g. a test context - never runs it). Disabled with
 * {@code app.media.cleanup-enabled=false} (the purge can still be invoked directly). Stops between batches on shutdown.
 */
@Component
@ConditionalOnProperty(name = "app.media.cleanup-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class MediaCleanupJob {

    private final MediaService mediaService;

    @Scheduled(fixedDelayString = "${app.media.cleanup-interval-ms:3600000}",
            initialDelayString = "${app.media.cleanup-interval-ms:3600000}")
    public void run() {
        try {
            int deleted = mediaService.purgeExpired();
            if (deleted > 0) log.info("Media cleanup: deleted {} expired unattached upload(s)", deleted);
        } catch (RuntimeException e) {
            log.warn("Media cleanup failed, will retry next run", e);
        }
    }
}
