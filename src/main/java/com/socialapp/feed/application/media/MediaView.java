package com.socialapp.feed.application.media;

import java.util.UUID;

/**
 * An image as the API shows it: in {@code PostResponse.media} (ordered by position), in the SSE payload, and as the
 * response of {@code POST /api/v1/media}. {@code width}/{@code height} are display pixels (EXIF orientation applied for
 * JPEG); null when unknown.
 */
public record MediaView(UUID id, String url, Integer width, Integer height) {
}
