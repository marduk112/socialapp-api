package com.socialapp.feed.infrastructure.media;

import com.socialapp.feed.domain.ImageFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;

/**
 * Serves post images of {@link LocalDiskMediaStorage}: {@code GET /uploads/media/<uuid>.<ext>} (public, like avatars).
 * <ul>
 *   <li>Only keys of the server-generated shape are looked up; anything else is a 404 (no traversal, no listing).</li>
 *   <li>Content-Type comes from the extension the server chose from the file's magic bytes - never sniffed, never
 *       client-supplied; {@code X-Content-Type-Options: nosniff}; {@code Content-Security-Policy: default-src 'none';
 *       sandbox} so a file opened directly can never run script on this origin.</li>
 *   <li>{@code Cache-Control: public, max-age=86400} (keys are immutable; a deleted post's image may stay in caches for
 *       up to a day). Range and conditional (Last-Modified) requests are supported by Spring.</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class MediaFileController {

    private final LocalDiskMediaStorage storage;

    @GetMapping("/uploads/media/{key}")
    public ResponseEntity<Resource> get(@PathVariable String key) {
        var path = storage.resolve(key).filter(Files::isRegularFile);
        if (path.isEmpty()) return ResponseEntity.notFound().build();
        var format = ImageFormat.ofExtension(key.substring(key.lastIndexOf('.') + 1)).orElseThrow();
        java.time.Instant modified;
        try {
            modified = Files.getLastModifiedTime(path.get()).toInstant();
        } catch (IOException e) { // deleted in between
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(format.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .lastModified(modified)
                .body(new FileSystemResource(path.get()));
    }
}
