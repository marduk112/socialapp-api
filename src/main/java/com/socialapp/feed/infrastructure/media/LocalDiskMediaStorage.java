package com.socialapp.feed.infrastructure.media;

import com.socialapp.feed.application.media.MediaProperties;
import com.socialapp.feed.application.media.MediaStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@link MediaStorage} on the local disk: one flat directory ({@code app.media.dir}, default {@code ./uploads/media}),
 * file name = storage key. Keys are validated against the exact server-generated shape ({@code <uuid>.<ext>}, lower
 * case) before any file system access, and the resolved path must stay inside the directory, so no request can reach
 * another file (no traversal, no other extension). Served by {@link MediaFileController}.
 */
@Component
@Slf4j
public class LocalDiskMediaStorage implements MediaStorage {

    static final Pattern KEY =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|gif|webp)");
    public static final String PUBLIC_PATH = "/uploads/media/";
    private static final java.util.Set<java.nio.file.attribute.PosixFilePermission> PUBLIC_FILE =
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--");

    private final Path root;
    private final String baseUrl;

    public LocalDiskMediaStorage(MediaProperties properties, @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.root = Path.of(properties.dir()).toAbsolutePath().normalize();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public void store(String key, byte[] bytes) throws IOException {
        Path target = resolve(key).orElseThrow(() -> new IllegalArgumentException("Invalid storage key"));
        Files.createDirectories(root);
        // Written to a temp file first and renamed, so a half-written file is never served; never replaces a file.
        Path temp = Files.createTempFile(root, ".upload-", ".tmp");
        try {
            Files.write(temp, bytes);
            // createTempFile makes the file owner-only (0600); the images are public, so a static server or CDN sync
            // running as another user must be able to read them.
            if (Files.getFileStore(temp).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(temp, PUBLIC_FILE);
            }
            Files.move(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public boolean delete(String key) {
        var path = resolve(key);
        if (path.isEmpty()) return false;
        try {
            Files.deleteIfExists(path.get());
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not delete media file {}", key, e);
            return false;
        }
    }

    @Override
    public String publicUrl(String key) {
        return baseUrl + PUBLIC_PATH + key;
    }

    /** The file of {@code key}, or empty if the key does not have the server-generated shape. */
    public Optional<Path> resolve(String key) {
        if (key == null || !KEY.matcher(key).matches()) return Optional.empty();
        Path path = root.resolve(key).normalize();
        return path.getParent() != null && path.getParent().equals(root) ? Optional.of(path) : Optional.empty();
    }

    Path root() {
        return root;
    }
}
