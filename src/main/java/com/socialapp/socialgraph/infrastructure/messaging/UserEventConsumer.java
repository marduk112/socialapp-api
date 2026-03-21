package com.socialapp.socialgraph.infrastructure.messaging;

import com.socialapp.shared.events.DomainEvent;
import com.socialapp.shared.events.UserCreatedEvent;
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
    public void onUserCreated(@Payload DomainEvent event) {
        if (!(event instanceof UserCreatedEvent e)) return;
        log.info("Received UserCreatedEvent for userId={}", e.userId());
        socialGraphService.syncUserCreated(e);
    }

    @KafkaListener(
            topics = "${kafka.topics.user-events}",
            groupId = "social-graph-update-group",
            containerFactory = "userUpdatedEventListenerContainerFactory"
    )
    public void onUserUpdated(@Payload DomainEvent event) {
        if (!(event instanceof UserUpdatedEvent e)) return;
        log.info("Received UserUpdatedEvent for userId={}", e.userId());
        socialGraphService.syncUserUpdated(e);
    }
}
