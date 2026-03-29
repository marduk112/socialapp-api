package com.socialapp.feed.infrastructure.messaging;

import com.socialapp.feed.domain.AuthorProfile;
import com.socialapp.feed.domain.AuthorProfileRepository;
import com.socialapp.feed.infrastructure.sse.FeedSseService;
import com.socialapp.shared.events.PostCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes PostCreatedEvents from Kafka and fans out to SSE subscribers.
 * Avatar is resolved from author_profiles so it is always current.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PostEventConsumer {

    private final FeedSseService feedSseService;
    private final AuthorProfileRepository authorProfileRepository;

    @KafkaListener(
            topics = "${kafka.topics.post-events}",
            groupId = "feed-sse-group",
            containerFactory = "postEventListenerContainerFactory"
    )
    public void onPostCreated(@Payload PostCreatedEvent event) {
        log.info("Received PostCreatedEvent postId={}, broadcasting via SSE", event.postId());

        String avatarUrl = authorProfileRepository.findById(event.authorId())
                .map(AuthorProfile::getAvatarUrl)
                .orElse(null);

        feedSseService.broadcast(new FeedSseService.PostSsePayload(
                event.postId(),
                event.authorId(),
                event.authorUsername(),
                avatarUrl,
                event.content(),
                event.imageUrl(),
                event.occurredAt()
        ));
    }
}
