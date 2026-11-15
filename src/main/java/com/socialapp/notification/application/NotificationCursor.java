package com.socialapp.notification.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Keyset position in a recipient's notifications ordered by {@code createdAt DESC, id DESC}: the key of the last
 * notification of the previous page. Same opaque format as the feed cursor: unpadded base64url of
 * {@code "v1|<ISO-8601 instant>|<uuid>"} (a separate class: BCs share no code).
 */
public record NotificationCursor(Instant createdAt, UUID id) {

    private static final String VERSION = "v1";
    private static final int MAX_ENCODED_LENGTH = 200;

    /** Key greater than any notification: the "cursor" of the first page. */
    public static final NotificationCursor START = new NotificationCursor(
            Instant.parse("9999-12-31T23:59:59.999999Z"), UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));

    public NotificationCursor {
        if (createdAt == null || id == null) throw new IllegalArgumentException("createdAt and id are required");
    }

    public String encode() {
        String raw = VERSION + "|" + createdAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param encoded the opaque cursor; null/blank means "first page" ({@link #START})
     * @throws IllegalArgumentException (400) "Invalid cursor" if it is not a cursor produced by {@link #encode()}
     */
    public static NotificationCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return START;
        if (encoded.length() > MAX_ENCODED_LENGTH) throw invalid();
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded.trim()), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 3 || !VERSION.equals(parts[0])) throw invalid();
            return new NotificationCursor(Instant.parse(parts[1]), UUID.fromString(parts[2]));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid cursor");
    }
}
