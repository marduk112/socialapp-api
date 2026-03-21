package com.socialapp.feed.infrastructure.messaging;

import com.socialapp.feed.infrastructure.sse.FeedSseService;
import com.socialapp.shared.events.PostCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes PostCreatedEvents from Kafka and fans out to SSE subscribers.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PostEventConsumer {

    private final FeedSseService feedSseService;

    @KafkaListener(
            topics = "${kafka.topics.post-events}",
            groupId = "feed-sse-group",
            containerFactory = "postEventListenerContainerFactory"
    )
    public void onPostCreated(@Payload PostCreatedEvent event) {
        log.info("Received PostCreatedEvent postId={}, broadcasting via SSE", event.postId());
        feedSseService.broadcast(event);
    }
}
