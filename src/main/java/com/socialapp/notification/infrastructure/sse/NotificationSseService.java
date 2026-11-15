package com.socialapp.notification.infrastructure.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.notification.application.NotificationView;
import com.socialapp.notification.infrastructure.web.NotificationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Per-user SSE streams of notifications ({@code GET /api/v1/notifications/stream}). A user may have several streams
 * (tabs); a notification is sent only to its recipient's streams, event name {@code notification}, data = the
 * {@link NotificationResponse} JSON. {@code connected} (data "ok") is sent on subscribe.
 *
 * <p>Like the feed stream, live delivery reaches streams connected to the instance that consumed the event (single
 * instance today); the REST list is the source of truth. On {@link ContextClosedEvent} every stream is completed so
 * Tomcat's graceful shutdown does not wait for them.
 */
@Service
@Slf4j
public class NotificationSseService {

    public static final String EVENT_CONNECTED = "connected";
    public static final String EVENT_NOTIFICATION = "notification";

    private final Map<UUID, Set<SseEmitter>> streams = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final Supplier<SseEmitter> emitterFactory;

    @Autowired
    public NotificationSseService(ObjectMapper objectMapper) {
        this(objectMapper, () -> new SseEmitter(Long.MAX_VALUE));
    }

    NotificationSseService(ObjectMapper objectMapper, Supplier<SseEmitter> emitterFactory) {
        this.objectMapper = objectMapper;
        this.emitterFactory = emitterFactory;
    }

    public SseEmitter subscribe(UUID userId) {
        var emitter = emitterFactory.get();
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> {
            remove(userId, emitter);
            emitter.complete();
        });
        emitter.onError(e -> remove(userId, emitter));
        streams.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        log.debug("New notification stream for {}. Users connected: {}", userId, streams.size());
        try {
            emitter.send(SseEmitter.event().name(EVENT_CONNECTED).data("ok"));
        } catch (IOException e) {
            remove(userId, emitter);
        }
        return emitter;
    }

    /** Sends each notification to its recipient's streams (off the Kafka listener thread). */
    @Async
    public void push(List<NotificationView> notifications) {
        for (var n : notifications) send(n);
    }

    void send(NotificationView notification) {
        var targets = streams.get(notification.recipientId());
        if (targets == null || targets.isEmpty()) return;
        String json;
        try {
            json = objectMapper.writeValueAsString(NotificationResponse.from(notification));
        } catch (IOException e) {
            log.error("Cannot serialize notification {}", notification.id(), e);
            return;
        }
        for (var emitter : targets) {
            try {
                emitter.send(SseEmitter.event().name(EVENT_NOTIFICATION).data(json));
            } catch (IOException | IllegalStateException e) {
                remove(notification.recipientId(), emitter);
                log.debug("Notification stream of {} dead, removed", notification.recipientId());
            }
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void completeAllOnShutdown() {
        int count = streams.values().stream().mapToInt(Set::size).sum();
        log.info("Shutting down: completing {} notification SSE stream(s)", count);
        for (var set : streams.values()) {
            for (var emitter : set) {
                try {
                    emitter.complete();
                } catch (RuntimeException e) {
                    log.debug("Notification SSE emitter already closed during shutdown", e);
                }
            }
        }
        streams.clear();
    }

    int streamCount(UUID userId) {
        var set = streams.get(userId);
        return set == null ? 0 : set.size();
    }

    private void remove(UUID userId, SseEmitter emitter) {
        streams.computeIfPresent(userId, (k, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
    }
}
