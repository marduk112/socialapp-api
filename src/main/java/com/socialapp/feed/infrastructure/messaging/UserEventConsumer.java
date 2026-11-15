package com.socialapp.feed.infrastructure.messaging;

import com.socialapp.feed.application.usecase.AuthorProfileProjection;
import com.socialapp.shared.events.CommentCreatedEvent;
import com.socialapp.shared.events.DomainEvent;
import com.socialapp.shared.events.PostCreatedEvent;
import com.socialapp.shared.events.ReactionEvent;
import com.socialapp.shared.events.UserCreatedEvent;
import com.socialapp.shared.events.UserFollowedEvent;
import com.socialapp.shared.events.UserUnfollowedEvent;
import com.socialapp.shared.events.UserUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Feed's single listener on user-events. One listener in one consumer group per BC: events of a user
 * share a key, hence a partition, so they are processed in the order they were published
 * (separate groups for create/update would race each other).
 */
@Component("feedUserEventConsumer")
@RequiredArgsConstructor
@Slf4j
public class UserEventConsumer {

    private final AuthorProfileProjection projection;

    @KafkaListener(
            topics = "${kafka.topics.user-events}",
            groupId = "feed-avatar-group",
            containerFactory = "feedUserEventListenerContainerFactory"
    )
    public void onUserEvent(@Payload DomainEvent event) {
        switch (event) {
            case UserCreatedEvent e -> {
                log.debug("UserCreated userId={}", e.userId());
                projection.on(e);
            }
            case UserUpdatedEvent e -> {
                log.debug("UserUpdated userId={}", e.userId());
                projection.on(e);
            }
            case PostCreatedEvent e -> log.warn("Unexpected PostCreatedEvent on user-events, ignoring: {}", e.eventId());
            case ReactionEvent e -> log.warn("Unexpected ReactionEvent on user-events, ignoring: {}", e.eventId());
            case CommentCreatedEvent e -> log.warn("Unexpected CommentCreatedEvent on user-events, ignoring: {}", e.eventId());
            case UserFollowedEvent e -> log.warn("Unexpected UserFollowedEvent on user-events, ignoring: {}", e.eventId());
            case UserUnfollowedEvent e -> log.warn("Unexpected UserUnfollowedEvent on user-events, ignoring: {}", e.eventId());
        }
    }
}
