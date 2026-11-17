package com.socialapp.feed.application.media;

/**
 * An upload that is refused. The web layer maps {@link Reason} to the HTTP status (see {@code MediaController}); the
 * message is English, safe to show and never echoes client input.
 */
public class MediaRejectedException extends RuntimeException {

    public enum Reason {
        /** Missing or empty file: 400. */
        EMPTY,
        /** More bytes or pixels than allowed: 413. */
        TOO_LARGE,
        /** Not a JPEG/PNG/GIF/WebP image, malformed, or carrying markup: 415. */
        UNSUPPORTED,
        /** Too many pending (unattached) uploads: 429. */
        TOO_MANY_PENDING,
        /** All decode slots busy for too long: 503. */
        BUSY
    }

    private final Reason reason;

    public MediaRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    static MediaRejectedException unsupported(String message) {
        return new MediaRejectedException(Reason.UNSUPPORTED, message);
    }

    static MediaRejectedException tooLarge(String message) {
        return new MediaRejectedException(Reason.TOO_LARGE, message);
    }
}
