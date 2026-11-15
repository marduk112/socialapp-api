package com.socialapp.socialgraph.infrastructure.messaging;

import com.socialapp.shared.events.CommentCreatedEvent;
import com.socialapp.shared.events.DomainEvent;
import com.socialapp.shared.events.PostCreatedEvent;
import com.socialapp.shared.events.ReactionEvent;
import com.socialapp.shared.events.UserCreatedEvent;
import com.socialapp.shared.events.UserFollowedEvent;
import com.socialapp.shared.events.UserUnfollowedEvent;
import com.socialapp.shared.events.UserUpdatedEvent;
import com.socialapp.socialgraph.application.usecase.SocialGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Kafka Consumer — SocialGraph listens to user-events topic.
 * No direct dependency on UserManagement module — purely event-driven.
 *
 * <p>One listener in one consumer group for the whole topic: a user's events share a key (and so a
 * partition) and are therefore handled in publication order.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserEventConsumer {

    private final SocialGraphService socialGraphService;

    @KafkaListener(
            topics = "${kafka.topics.user-events}",
            groupId = "social-graph-group",
            containerFactory = "userEventListenerContainerFactory"
    )
    public void onUserEvent(@Payload DomainEvent event) {
        switch (event) {
            case UserCreatedEvent e -> {
                log.info("Received UserCreatedEvent for userId={}", e.userId());
                socialGraphService.syncUserCreated(e);
            }
            case UserUpdatedEvent e -> {
                log.info("Received UserUpdatedEvent for userId={}", e.userId());
                socialGraphService.syncUserUpdated(e);
            }
            case PostCreatedEvent e -> log.warn("Unexpected PostCreatedEvent on user-events, ignoring: {}", e.eventId());
            case ReactionEvent e -> log.warn("Unexpected ReactionEvent on user-events, ignoring: {}", e.eventId());
            case CommentCreatedEvent e -> log.warn("Unexpected CommentCreatedEvent on user-events, ignoring: {}", e.eventId());
            case UserFollowedEvent e -> log.warn("Unexpected UserFollowedEvent on user-events, ignoring: {}", e.eventId());
            case UserUnfollowedEvent e -> log.warn("Unexpected UserUnfollowedEvent on user-events, ignoring: {}", e.eventId());
        }
    }
}
