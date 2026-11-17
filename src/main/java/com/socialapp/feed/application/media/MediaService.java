package com.socialapp.feed.application.media;

import com.socialapp.feed.domain.PostMedia;
import com.socialapp.feed.domain.PostMediaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Uploads of post images and the purge of uploads that were never attached. Attaching happens in
 * {@code PostService} (inside the post's create transaction).
 *
 * <p>Upload order (no orphan files on a crash): validate the bytes ({@link ImageInspector}, outside any transaction),
 * then commit the UNATTACHED row (per-user advisory lock + pending cap), then write the file. If the write fails the
 * row is deleted again (500). A crash between commit and write leaves a row without a file, which nobody knows the id
 * of and which the cleanup removes after {@code unattached-ttl}; a missing file never breaks a post list (the URL is
 * built from the row, the browser just gets a 404 for that image).
 */
@Service
@Slf4j
public class MediaService {

    private final PostMediaRepository repository;
    private final MediaStorage storage;
    private final MediaProperties properties;
    private final TransactionTemplate tx;
    private final ImageInspector inspector;
    private final Clock clock;
    private volatile boolean stopping;

    @Autowired
    public MediaService(PostMediaRepository repository, MediaStorage storage, MediaProperties properties,
                        PlatformTransactionManager transactionManager) {
        this(repository, storage, properties, new TransactionTemplate(transactionManager), new ImageInspector(),
                Clock.systemUTC());
    }

    MediaService(PostMediaRepository repository, MediaStorage storage, MediaProperties properties,
                 TransactionTemplate tx, ImageInspector inspector, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.properties = properties;
        this.tx = tx;
        this.inspector = inspector;
        this.clock = clock;
    }

    /**
     * Stores an unattached image owned by {@code ownerId}.
     *
     * @throws MediaRejectedException empty (EMPTY), more than {@code max-bytes} or too many pixels (TOO_LARGE), not an
     *                                accepted image (UNSUPPORTED), more than {@code max-pending-per-user} unattached
     *                                uploads (TOO_MANY_PENDING)
     */
    public MediaView upload(UUID ownerId, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new MediaRejectedException(MediaRejectedException.Reason.EMPTY, "The file is empty");
        }
        if (bytes.length > properties.maxBytes()) {
            throw MediaRejectedException.tooLarge("The file is larger than " + properties.maxBytes() / (1024 * 1024)
                    + " MB");
        }
        var image = inspector.inspect(bytes);
        var media = tx.execute(status -> {
            repository.lockUploadsOf(ownerId);
            if (repository.countUnattached(ownerId) >= properties.maxPendingPerUser()) {
                throw new MediaRejectedException(MediaRejectedException.Reason.TOO_MANY_PENDING,
                        "Too many pending uploads (max " + properties.maxPendingPerUser()
                                + "); attach them to a post or wait until they expire");
            }
            return repository.save(PostMedia.upload(ownerId, image.format(), image.width(), image.height(),
                    image.bytes().length));
        });
        try {
            storage.store(media.getStorageKey(), image.bytes());
        } catch (IOException | RuntimeException e) {
            log.error("Could not store media {} ({})", media.getId(), media.getStorageKey(), e);
            try {
                tx.executeWithoutResult(status -> repository.deleteUnattached(List.of(media.getId())));
            } catch (RuntimeException cleanupFailure) {
                log.warn("Could not delete the row of unstored media {}; the cleanup job will", media.getId(),
                        cleanupFailure);
            }
            throw new IllegalStateException("Could not store the file");
        }
        return view(media);
    }

    public MediaView view(PostMedia media) {
        return new MediaView(media.getId(), storage.publicUrl(media.getStorageKey()), media.getWidth(),
                media.getHeight());
    }

    /**
     * Deletes unattached uploads older than {@code unattached-ttl}: rows in batches of {@code cleanup-batch-size} (each
     * its own transaction, rows locked SKIP LOCKED, so an upload being attached right now is left alone), then their
     * files after the batch committed (best effort; a file that cannot be deleted is logged and left behind).
     *
     * @return number of uploads deleted
     */
    public int purgeExpired() {
        var cutoff = clock.instant().minus(properties.unattachedTtl());
        int total = 0;
        int batch;
        do {
            List<String> keys = tx.execute(status -> {
                var rows = repository.lockExpiredUnattached(cutoff, properties.cleanupBatchSize());
                if (rows.isEmpty()) return List.<String>of();
                repository.deleteUnattached(rows.stream().map(PostMedia::getId).toList());
                return rows.stream().map(PostMedia::getStorageKey).toList();
            });
            batch = keys == null ? 0 : keys.size();
            if (batch > 0) keys.forEach(storage::delete);
            total += batch;
        } while (batch == properties.cleanupBatchSize() && !stopping);
        return total;
    }

    @EventListener(ContextClosedEvent.class)
    public void stop() {
        stopping = true;
    }
}
