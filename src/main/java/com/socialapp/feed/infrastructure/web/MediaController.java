package com.socialapp.feed.infrastructure.web;

import com.socialapp.feed.application.media.MediaRejectedException;
import com.socialapp.feed.application.media.MediaService;
import com.socialapp.feed.application.media.MediaView;
import com.socialapp.shared.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * {@code POST /api/v1/media} (authenticated, multipart field {@code file}) -> 201 {@code {id, url, width, height}}: an
 * UNATTACHED image owned by the caller, to be attached with {@code mediaIds} on {@code POST /posts} or
 * {@code POST /posts/{id}/quote}. The type is decided from the bytes only (the part's Content-Type and file name are
 * ignored). Errors (JSON {@code {error, timestamp}}): missing/empty file or not multipart 400, larger than 5 MB or more
 * than 8000x8000 px 413, not an accepted JPEG/PNG/GIF/WebP 415, more than 20 pending uploads 429, no token 401.
 */
@RestController
@RequestMapping("/api/v1/media")
@RequiredArgsConstructor
public class MediaController {

    private final MediaService mediaService;

    @PostMapping
    public ResponseEntity<MediaView> upload(@AuthenticationPrincipal AuthenticatedUser me,
                                            @RequestParam(value = "file", required = false) MultipartFile file)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new MediaRejectedException(MediaRejectedException.Reason.EMPTY, "A non-empty file is required");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(mediaService.upload(me.id(), file.getBytes()));
    }

    @ExceptionHandler(MediaRejectedException.class)
    public ResponseEntity<Map<String, Object>> rejected(MediaRejectedException ex) {
        var status = switch (ex.reason()) {
            case EMPTY -> HttpStatus.BAD_REQUEST;
            case TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case UNSUPPORTED -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case TOO_MANY_PENDING -> HttpStatus.TOO_MANY_REQUESTS;
            case BUSY -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(Map.of("error", ex.getMessage(), "timestamp", Instant.now().toString()));
    }
}
