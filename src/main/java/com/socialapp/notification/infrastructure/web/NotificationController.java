package com.socialapp.notification.infrastructure.web;

import com.socialapp.notification.application.NotificationCursor;
import com.socialapp.notification.application.NotificationService;
import com.socialapp.notification.infrastructure.sse.NotificationSseService;
import com.socialapp.shared.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

/** The caller's own notifications (the recipient is always the authenticated user, never a parameter). */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;
    private final NotificationSseService sseService;

    /**
     * {@code {"items": [Notification...], "nextCursor": "<opaque>" | null}}, newest first (createdAt DESC, id DESC).
     * {@code size} default 20, clamped to 100, &lt; 1 or non-numeric -> 400; malformed {@code cursor} -> 400.
     */
    @GetMapping
    public ResponseEntity<NotificationPageResponse> list(
            @AuthenticationPrincipal AuthenticatedUser me,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + NotificationService.DEFAULT_SIZE) int size) {
        var page = service.page(me.id(), NotificationCursor.decode(cursor), size);
        return ResponseEntity.ok(new NotificationPageResponse(
                page.items().stream().map(NotificationResponse::from).toList(),
                page.next() == null ? null : page.next().encode()));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCountResponse> unreadCount(@AuthenticationPrincipal AuthenticatedUser me) {
        return ResponseEntity.ok(new UnreadCountResponse(service.unreadCount(me.id())));
    }

    /**
     * Marks the given notifications read, or ALL of the caller's when the body or {@code ids} is missing/empty.
     * Always 204 (idempotent); ids that are not the caller's are ignored.
     */
    @PostMapping("/read")
    public ResponseEntity<Void> markRead(
            @AuthenticationPrincipal AuthenticatedUser me,
            @RequestBody(required = false) MarkReadRequest request) {
        service.markRead(me.id(), request == null ? null : request.ids());
        return ResponseEntity.noContent().build();
    }

    /** SSE: {@code connected} (data "ok"), then {@code notification} (data = Notification JSON) for the caller only. */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal AuthenticatedUser me) {
        return sseService.subscribe(me.id());
    }

    record NotificationPageResponse(List<NotificationResponse> items, String nextCursor) {}

    record UnreadCountResponse(long count) {}

    record MarkReadRequest(List<UUID> ids) {}
}
