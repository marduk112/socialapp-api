package com.socialapp.feed.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * The image formats a post may carry. The format is always decided by the server from the file's bytes (magic number
 * and structure, see {@code ImageInspector}), never from the client's Content-Type or file name; the stored file's
 * extension and the Content-Type it is served with both come from here.
 */
public enum ImageFormat {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    GIF("image/gif", "gif"),
    WEBP("image/webp", "webp");

    private final String contentType;
    private final String extension;

    ImageFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() {
        return contentType;
    }

    /** File extension without the dot, lower case. */
    public String extension() {
        return extension;
    }

    public static Optional<ImageFormat> ofExtension(String extension) {
        return Arrays.stream(values()).filter(f -> f.extension.equals(extension)).findFirst();
    }

    public static Optional<ImageFormat> ofContentType(String contentType) {
        return Arrays.stream(values()).filter(f -> f.contentType.equals(contentType)).findFirst();
    }
}
