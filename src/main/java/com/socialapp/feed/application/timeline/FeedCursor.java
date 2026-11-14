package com.socialapp.feed.application.timeline;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Keyset position in a timeline ordered by {@code createdAt DESC, id DESC}: the key of the last post of the
 * previous page. Clients see it only as an opaque string ({@link #encode()}): unpadded base64url of
 * {@code "v1|<ISO-8601 instant>|<uuid>"}. The version prefix lets the format change later.
 */
public record FeedCursor(Instant createdAt, UUID id) {

    private static final String VERSION = "v1";
    /** Far longer than any valid cursor (~80 chars); bounds the work done on hostile input. */
    private static final int MAX_ENCODED_LENGTH = 200;

    /** Key greater than any post: the "cursor" of the first page. */
    public static final FeedCursor START = new FeedCursor(Instant.parse("9999-12-31T23:59:59.999999Z"),
            UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));

    public FeedCursor {
        if (createdAt == null || id == null) throw new IllegalArgumentException("createdAt and id are required");
    }

    public String encode() {
        String raw = VERSION + "|" + createdAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param encoded the opaque cursor; null/blank means "first page" ({@link #START})
     * @throws InvalidCursorException (400) if it is not a cursor produced by {@link #encode()}
     */
    public static FeedCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return START;
        if (encoded.length() > MAX_ENCODED_LENGTH) throw new InvalidCursorException();
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded.trim()), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 3 || !VERSION.equals(parts[0])) throw new InvalidCursorException();
            return new FeedCursor(Instant.parse(parts[1]), UUID.fromString(parts[2]));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new InvalidCursorException();
        }
    }

    /** Malformed {@code cursor} parameter; an {@link IllegalArgumentException}, so it maps to 400. */
    public static final class InvalidCursorException extends IllegalArgumentException {
        public InvalidCursorException() {
            super("Invalid cursor");
        }
    }
}
