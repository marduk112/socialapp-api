package com.socialapp.feed.domain;

import com.socialapp.shared.domain.NotFoundException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * An image of a post (V22). Life cycle: {@link #upload} stores it UNATTACHED and owned by the uploader; creating a
 * post or quote attaches it ({@link #attachTo}, in the create transaction, row locked); afterwards it is immutable and
 * lives as long as its post (FK ON DELETE CASCADE). Unattached uploads expire (MediaCleanup).
 *
 * <p>Attach rules: only the owner can attach, and only once. An id that does not exist or belongs to someone else is
 * reported as "not found" (404) either way, so a caller learns nothing about other users' uploads; the caller's own
 * already-attached upload is a bad request (400).
 */
@Entity
@Table(name = "post_media")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class PostMedia {

    /** Maximum number of images per post. */
    public static final int MAX_PER_POST = 4;

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID ownerId;

    @Column(name = "post_id", columnDefinition = "uuid")
    private UUID postId;

    @Column(name = "position")
    private Short position;

    @Column(name = "content_type", nullable = false, updatable = false, length = 20)
    private String contentType;

    @Column(name = "width", updatable = false)
    private Integer width;

    @Column(name = "height", updatable = false)
    private Integer height;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "storage_key", nullable = false, updatable = false, length = 64)
    private String storageKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * A new unattached upload. The storage key is random and server-generated ({@code <uuid>.<ext>}, the extension from
     * the detected format), unrelated to the media id and never derived from client input.
     */
    public static PostMedia upload(UUID ownerId, ImageFormat format, Integer width, Integer height, long sizeBytes) {
        if (ownerId == null || format == null) throw new IllegalArgumentException("Owner and format are required");
        if (sizeBytes <= 0) throw new IllegalArgumentException("Empty file");
        var media = new PostMedia();
        media.id = UUID.randomUUID();
        media.ownerId = ownerId;
        media.contentType = format.contentType();
        media.width = width;
        media.height = height;
        media.sizeBytes = sizeBytes;
        media.storageKey = UUID.randomUUID() + "." + format.extension();
        media.createdAt = Instant.now();
        return media;
    }

    /**
     * Checks that {@code requesterId} may attach this upload. Must be called with the row locked.
     *
     * @throws NotFoundException  {@code requesterId} is not the owner (indistinguishable from an unknown id)
     * @throws IllegalArgumentException already attached to a post (400: the id is not an unattached upload)
     */
    public void ensureAttachableBy(UUID requesterId) {
        if (requesterId == null || !requesterId.equals(ownerId)) throw notFound(id);
        if (this.postId != null) throw new IllegalArgumentException("Media is already attached to a post: " + id);
    }

    /** Attaches this upload to {@code postId} at {@code position} (0-based). Row locked; same checks as above. */
    public void attachTo(UUID postId, int position, UUID requesterId) {
        ensureAttachableBy(requesterId);
        if (postId == null) throw new IllegalArgumentException("Post is required");
        if (position < 0 || position >= MAX_PER_POST) throw new IllegalArgumentException("Invalid media position");
        this.postId = postId;
        this.position = (short) position;
    }

    public boolean isAttached() {
        return postId != null;
    }

    public ImageFormat format() {
        return ImageFormat.ofContentType(contentType)
                .orElseThrow(() -> new IllegalStateException("Unknown content type " + contentType));
    }

    public static NotFoundException notFound(UUID id) {
        return new NotFoundException("Media not found: " + id);
    }
}
