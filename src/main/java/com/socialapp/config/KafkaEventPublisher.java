package com.socialapp.config;

import com.socialapp.shared.events.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Translates Spring ApplicationEvents (fired by @DomainEvents / AbstractAggregateRoot)
 * into Kafka messages.
 *
 * AFTER_COMMIT phase: Kafka publish happens only after the DB transaction successfully
 * commits, avoiding sending events that will be rolled back.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaEventPublisher {

    private final KafkaTemplate<String, DomainEvent> kafkaTemplate;

    @Value("${kafka.topics.user-events}")
    private String userEventsTopic;

    @Value("${kafka.topics.post-events}")
    private String postEventsTopic;

    @Value("${kafka.topics.reaction-events}")
    private String reactionEventsTopic;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserCreated(UserCreatedEvent event) {
        log.info("Publishing UserCreatedEvent for userId={}", event.userId());
        kafkaTemplate.send(userEventsTopic, event.userId().toString(), event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserUpdated(UserUpdatedEvent event) {
        log.info("Publishing UserUpdatedEvent for userId={}", event.userId());
        kafkaTemplate.send(userEventsTopic, event.userId().toString(), event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPostCreated(PostCreatedEvent event) {
        log.info("Publishing PostCreatedEvent for postId={}", event.postId());
        kafkaTemplate.send(postEventsTopic, event.postId().toString(), event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReaction(ReactionEvent event) {
        log.debug("Publishing ReactionEvent postId={} type={}", event.postId(), event.reactionType());
        kafkaTemplate.send(reactionEventsTopic, event.postId().toString(), event);
    }
}
