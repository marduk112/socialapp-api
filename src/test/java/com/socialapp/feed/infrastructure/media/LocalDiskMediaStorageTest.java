package com.socialapp.feed.infrastructure.media;

import com.socialapp.feed.application.media.MediaProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Key validation (no traversal, no foreign extensions), no overwrite, best-effort delete, URL shape. */
class LocalDiskMediaStorageTest {

    @TempDir Path dir;

    private LocalDiskMediaStorage storage(String baseUrl) {
        return new LocalDiskMediaStorage(new MediaProperties(dir.resolve("media").toString(), 5_242_880, 20,
                Duration.ofHours(24), false, 3_600_000, 500), baseUrl);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../x.png", "..%2Fx.png", "/etc/passwd", "a/b.png", "x.png", "x.svg", "x.html",
            "3f2504e0-4f89-11d3-9a0c-0305e82c3301.svg", "3F2504E0-4F89-11D3-9A0C-0305E82C3301.png",
            "3f2504e0-4f89-11d3-9a0c-0305e82c3301.png/..", "3f2504e0-4f89-11d3-9a0c-0305e82c3301.png\u0000.html",
            "..", "", " 3f2504e0-4f89-11d3-9a0c-0305e82c3301.png"})
    void invalidKeysNeverResolveToAPath(String key) {
        var storage = storage("http://h");
        assertThat(storage.resolve(key)).isEmpty();
        assertThat(storage.delete(key)).isFalse();
        assertThatThrownBy(() -> storage.store(key, new byte[] {1})).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storesInsideTheDirectory_neverOverwrites_deletesIdempotently() throws Exception {
        var storage = storage("http://h");
        var key = UUID.randomUUID() + ".webp";

        storage.store(key, new byte[] {1, 2, 3});
        assertThat(Files.readAllBytes(dir.resolve("media").resolve(key))).containsExactly(1, 2, 3);
        if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            assertThat(java.nio.file.attribute.PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(dir.resolve("media").resolve(key)))).isEqualTo("rw-r--r--");
        }
        assertThatThrownBy(() -> storage.store(key, new byte[] {9})).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readAllBytes(dir.resolve("media").resolve(key))).containsExactly(1, 2, 3);
        try (var files = Files.list(dir.resolve("media"))) {
            assertThat(files).hasSize(1); // no temp file left behind
        }

        assertThat(storage.delete(key)).isTrue();
        assertThat(storage.delete(key)).isTrue(); // already gone: fine
        assertThat(dir.resolve("media").resolve(key)).doesNotExist();
    }

    @Test
    void publicUrlIsBaseUrlPlusUploadsMediaPlusKey() {
        var key = UUID.randomUUID() + ".jpg";
        assertThat(storage("http://localhost:8080").publicUrl(key)).isEqualTo("http://localhost:8080/uploads/media/" + key);
        assertThat(storage("https://cdn.example/").publicUrl(key)).isEqualTo("https://cdn.example/uploads/media/" + key);
    }
}
