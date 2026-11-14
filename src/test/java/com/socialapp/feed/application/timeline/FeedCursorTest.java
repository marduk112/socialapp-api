package com.socialapp.feed.application.timeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedCursorTest {

    @Test
    void roundTripKeepsMicrosecondPrecisionAndIsUrlSafeWithoutPadding() {
        var cursor = new FeedCursor(Instant.parse("2026-11-14T10:15:30.123456Z"), UUID.randomUUID());

        String encoded = cursor.encode();

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        assertThat(FeedCursor.decode(encoded)).isEqualTo(cursor);
    }

    @Test
    void roundTripOfWholeSecondsAndNanos() {
        var whole = new FeedCursor(Instant.parse("2026-01-01T00:00:00Z"), UUID.randomUUID());
        var nanos = new FeedCursor(Instant.parse("2026-01-01T00:00:00.123456789Z"), UUID.randomUUID());

        assertThat(FeedCursor.decode(whole.encode())).isEqualTo(whole);
        assertThat(FeedCursor.decode(nanos.encode())).isEqualTo(nanos);
    }

    @Test
    void missingCursorMeansFirstPage() {
        assertThat(FeedCursor.decode(null)).isEqualTo(FeedCursor.START);
        assertThat(FeedCursor.decode("")).isEqualTo(FeedCursor.START);
        assertThat(FeedCursor.decode("  ")).isEqualTo(FeedCursor.START);
    }

    @ParameterizedTest
    @ValueSource(strings = {"!!!", "abc$", "dGVzdA", "djF8bm90LWEtZGF0ZXwx"})
    void garbageIsRejected(String encoded) {
        assertThatThrownBy(() -> FeedCursor.decode(encoded))
                .isInstanceOf(FeedCursor.InvalidCursorException.class)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid cursor");
    }

    @Test
    void wrongVersionBadUuidExtraPartsAndOversizedInputAreRejected() {
        String now = Instant.now().toString();
        String id = UUID.randomUUID().toString();
        for (String raw : new String[] {"v2|" + now + "|" + id, "v1|" + now + "|not-a-uuid",
                "v1|" + now + "|" + id + "|x", "v1|" + now}) {
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> FeedCursor.decode(encoded)).isInstanceOf(FeedCursor.InvalidCursorException.class);
        }
        assertThatThrownBy(() -> FeedCursor.decode("A".repeat(201)))
                .isInstanceOf(FeedCursor.InvalidCursorException.class);
    }

    @Test
    void scopeParsing() {
        assertThat(FeedScope.parse(null)).isEqualTo(FeedScope.ALL);
        assertThat(FeedScope.parse("")).isEqualTo(FeedScope.ALL);
        assertThat(FeedScope.parse("all")).isEqualTo(FeedScope.ALL);
        assertThat(FeedScope.parse("Following")).isEqualTo(FeedScope.FOLLOWING);
        assertThatThrownBy(() -> FeedScope.parse("friends")).isInstanceOf(IllegalArgumentException.class);
    }
}
