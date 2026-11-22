package com.socialapp.usermanagement.infrastructure.messaging;

import com.socialapp.shared.events.CommentCreatedEvent;
import com.socialapp.shared.events.DomainEvent;
import com.socialapp.shared.events.PostCreatedEvent;
import com.socialapp.shared.events.ReactionEvent;
import com.socialapp.shared.events.UserBlockedEvent;
import com.socialapp.shared.events.UserCreatedEvent;
import com.socialapp.shared.events.UserFollowedEvent;
import com.socialapp.shared.events.UserMutedEvent;
import com.socialapp.shared.events.UserUnblockedEvent;
import com.socialapp.shared.events.UserUnfollowedEvent;
import com.socialapp.shared.events.UserUnmutedEvent;
import com.socialapp.shared.events.UserUpdatedEvent;
import com.socialapp.usermanagement.application.usecase.UserBlockProjection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * UserManagement's listener on social-events (stage 5A, group {@code user-blocks-group}): only blocks matter here (people
 * search); follows and mutes are ignored on purpose. Malformed records go to {@code social-events.DLT}
 * (ErrorHandlingDeserializer + DefaultErrorHandler of the container factory).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BlockEventConsumer {

    private final UserBlockProjection projection;

    @KafkaListener(
            topics = "${kafka.topics.social-events}",
            groupId = "user-blocks-group",
            containerFactory = "userBlocksSocialEventListenerContainerFactory"
    )
    public void onSocialEvent(@Payload DomainEvent event) {
        switch (event) {
            case UserBlockedEvent e -> projection.on(e);
            case UserUnblockedEvent e -> projection.on(e);
            case UserFollowedEvent e -> log.trace("Follow event {} not needed by UserManagement", e.eventId());
            case UserUnfollowedEvent e -> log.trace("Unfollow event {} not needed by UserManagement", e.eventId());
            case UserMutedEvent e -> log.trace("Mute event {} not needed by UserManagement", e.eventId());
            case UserUnmutedEvent e -> log.trace("Unmute event {} not needed by UserManagement", e.eventId());
            case UserCreatedEvent e -> log.warn("Unexpected UserCreatedEvent on social-events, ignoring: {}", e.eventId());
            case UserUpdatedEvent e -> log.warn("Unexpected UserUpdatedEvent on social-events, ignoring: {}", e.eventId());
            case PostCreatedEvent e -> log.warn("Unexpected PostCreatedEvent on social-events, ignoring: {}", e.eventId());
            case ReactionEvent e -> log.warn("Unexpected ReactionEvent on social-events, ignoring: {}", e.eventId());
            case CommentCreatedEvent e -> log.warn("Unexpected CommentCreatedEvent on social-events, ignoring: {}", e.eventId());
        }
    }
}
