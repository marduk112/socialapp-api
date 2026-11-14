package com.socialapp.feed.infrastructure.messaging;

import com.socialapp.feed.application.timeline.FolloweeLookup;
import com.socialapp.feed.application.usecase.FollowProjection;
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
 * Feed's single listener on social-events (one consumer group for the BC): a follower's events share a key,
 * hence a partition, so they normally arrive in order - but the projection does not rely on it.
 * Malformed records go to {@code social-events.DLT} (ErrorHandlingDeserializer + DefaultErrorHandler).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SocialEventConsumer {

    private final FollowProjection projection;
    private final FolloweeLookup followeeLookup;

    @KafkaListener(
            topics = "${kafka.topics.social-events}",
            groupId = "feed-follows-group",
            containerFactory = "feedSocialEventListenerContainerFactory"
    )
    public void onSocialEvent(@Payload DomainEvent event) {
        switch (event) {
            case UserFollowedEvent e -> {
                log.debug("UserFollowed {} -> {}", e.followerId(), e.followeeId());
                if (projection.on(e)) followeeLookup.invalidate(e.followerId()); // after commit
            }
            case UserUnfollowedEvent e -> {
                log.debug("UserUnfollowed {} -> {}", e.followerId(), e.followeeId());
                if (projection.on(e)) followeeLookup.invalidate(e.followerId());
            }
            case UserCreatedEvent e -> log.warn("Unexpected UserCreatedEvent on social-events, ignoring: {}", e.eventId());
            case UserUpdatedEvent e -> log.warn("Unexpected UserUpdatedEvent on social-events, ignoring: {}", e.eventId());
            case PostCreatedEvent e -> log.warn("Unexpected PostCreatedEvent on social-events, ignoring: {}", e.eventId());
            case ReactionEvent e -> log.warn("Unexpected ReactionEvent on social-events, ignoring: {}", e.eventId());
        }
    }
}
