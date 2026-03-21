package com.socialapp.feed.infrastructure.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.shared.events.PostCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE broker. Uses CopyOnWriteArrayList for thread-safe emitter management.
 * When a PostCreatedEvent arrives from Kafka, it broadcasts to all connected clients.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FeedSseService {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper;

    public SseEmitter subscribe() {
        var emitter = new SseEmitter(Long.MAX_VALUE);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(e -> emitters.remove(emitter));

        emitters.add(emitter);
        log.debug("New SSE subscriber. Total: {}", emitters.size());

        // Send a heartbeat immediately so the browser knows the connection is alive
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            emitters.remove(emitter);
        }

        return emitter;
    }

    @Async
    public void broadcast(PostCreatedEvent event) {
        List<SseEmitter> dead = new CopyOnWriteArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("new-post")
                        .data(objectMapper.writeValueAsString(event)));
            } catch (IOException e) {
                dead.add(emitter);
                log.debug("SSE emitter dead, removing. Remaining: {}", emitters.size() - dead.size());
            }
        }
        emitters.removeAll(dead);
    }
}
