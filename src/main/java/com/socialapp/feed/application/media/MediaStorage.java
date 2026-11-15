package com.socialapp.feed.application.media;

import java.io.IOException;

/**
 * Port: where image files live. Keys are server-generated ({@code <uuid>.<ext>}, see
 * {@code PostMedia.upload}), never client input. Today: local disk ({@code LocalDiskMediaStorage}, served by the app
 * under {@code /uploads/media/**}); an S3/MinIO adapter can replace it (store = PUT object, delete = DELETE object,
 * publicUrl = bucket/CDN URL) without touching the domain or the API.
 */
public interface MediaStorage {

    /** Stores {@code bytes} under {@code key}; never overwrites an existing object. */
    void store(String key, byte[] bytes) throws IOException;

    /** Deletes the object; best effort: never throws, returns false (and logs) if it could not be deleted. */
    boolean delete(String key);

    /** The absolute URL clients load the image from. */
    String publicUrl(String key);
}
