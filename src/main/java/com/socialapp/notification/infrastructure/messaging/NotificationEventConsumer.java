package com.socialapp.notification.infrastructure.messaging;

import com.socialapp.notification.application.ActorProfileProjection;
import com.socialapp.notification.application.NotificationService;
import com.socialapp.notification.application.NotificationView;
import com.socialapp.notification.infrastructure.sse.NotificationSseService;
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

import java.util.List;

/**
 * Notification's Kafka listeners: ONE listener and consumer group per topic (ErrorHandlingDeserializer + DLT via the
 * container factories in {@code KafkaConfig}), all dispatching through one exhaustive switch on the sealed
 * {@link DomainEvent}. Handlers are idempotent and order-tolerant (see {@code Notification}); the notifications that
 * became visible are pushed over SSE after the handler's transaction committed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationEventConsumer {

    private final NotificationService notifications;
    private final ActorProfileProjection profiles;
    private final NotificationSseService sse;

    @KafkaListener(topics = "${kafka.topics.user-events}", groupId = "notification-users-group",
            containerFactory = "notificationUserEventListenerContainerFactory")
    public void onUserEvent(@Payload DomainEvent event) {
        handle(event);
    }

    @KafkaListener(topics = "${kafka.topics.social-events}", groupId = "notification-social-group",
            containerFactory = "notificationSocialEventListenerContainerFactory")
    public void onSocialEvent(@Payload DomainEvent event) {
        handle(event);
    }

    @KafkaListener(topics = "${kafka.topics.post-events}", groupId = "notification-posts-group",
            containerFactory = "notificationPostEventListenerContainerFactory")
    public void onPostEvent(@Payload DomainEvent event) {
        handle(event);
    }

    @KafkaListener(topics = "${kafka.topics.reaction-events}", groupId = "notification-reactions-group",
            containerFactory = "notificationReactionEventListenerContainerFactory")
    public void onReactionEvent(@Payload DomainEvent event) {
        handle(event);
    }

    @KafkaListener(topics = "${kafka.topics.comment-events}", groupId = "notification-comments-group",
            containerFactory = "notificationCommentEventListenerContainerFactory")
    public void onCommentEvent(@Payload DomainEvent event) {
        handle(event);
    }

    /** An event means the same whatever topic it came from. */
    void handle(DomainEvent event) {
        List<NotificationView> visible = switch (event) {
            case UserCreatedEvent e -> {
                profiles.on(e);
                yield List.of();
            }
            case UserUpdatedEvent e -> {
                profiles.on(e);
                yield List.of();
            }
            case UserFollowedEvent e -> notifications.on(e);
            case UserUnfollowedEvent e -> notifications.on(e);
            case PostCreatedEvent e -> notifications.on(e);
            case ReactionEvent e -> notifications.on(e);
            case CommentCreatedEvent e -> notifications.on(e);
        };
        if (!visible.isEmpty()) {
            log.debug("{} notification(s) from {} {}", visible.size(), event.eventType(), event.eventId());
            sse.push(visible); // after commit: the handler's transaction has ended
        }
    }
}
