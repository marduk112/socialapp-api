package com.socialapp.config.outbox;

import com.socialapp.shared.events.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Write side of the transactional outbox: every {@link DomainEvent} registered by an aggregate
 * ({@code registerEvent(...)}, published by Spring Data on {@code save()}) is stored in
 * {@code outbox_events} in the BEFORE_COMMIT phase, i.e. inside the same database transaction as the
 * aggregate change. Commit -> both are durable; rollback (or a failing insert) -> neither is, and no
 * Kafka message can ever be produced for it. Kafka is only touched later by {@link OutboxRelay}.
 *
 * <p>Routing is identical to the former {@code KafkaEventPublisher}: user events -> user-events keyed
 * by userId, post events -> post-events keyed by postId, reactions -> reaction-events keyed by postId.
 * Follow events -> social-events keyed by followerId; they do not come from a JPA aggregate but are
 * imported from SocialGraph's Neo4j outbox by {@link OutboxImporter} (which reuses {@link #routeOf}).
 * The sealed {@link DomainEvent} makes the switch exhaustive, so a new event type cannot be forgotten.
 *
 * <p>{@code fallbackExecution = true}: an event raised outside any transaction (no current caller
 * does this) is still written, in auto-commit, rather than silently dropped - logged at WARN because it
 * is then not atomic with the change that raised it.
 */
@Component
@Slf4j
public class OutboxEventWriter {

    private final OutboxRepository repository;
    private final OutboxRelay relay;
    private final DomainEventCodec codec;
    private final String userEventsTopic;
    private final String postEventsTopic;
    private final String reactionEventsTopic;
    private final String socialEventsTopic;

    public OutboxEventWriter(OutboxRepository repository, OutboxRelay relay, DomainEventCodec codec,
                             @Value("${kafka.topics.user-events}") String userEventsTopic,
                             @Value("${kafka.topics.post-events}") String postEventsTopic,
                             @Value("${kafka.topics.reaction-events}") String reactionEventsTopic,
                             @Value("${kafka.topics.social-events}") String socialEventsTopic) {
        this.repository = repository;
        this.relay = relay;
        this.codec = codec;
        this.userEventsTopic = userEventsTopic;
        this.postEventsTopic = postEventsTopic;
        this.reactionEventsTopic = reactionEventsTopic;
        this.socialEventsTopic = socialEventsTopic;
    }

    /** Where an event goes on Kafka. */
    record Route(String aggregateType, String topic, String key) {}

    Route routeOf(DomainEvent event) {
        return switch (event) {
            case UserCreatedEvent e -> new Route("User", userEventsTopic, e.userId().toString());
            case UserUpdatedEvent e -> new Route("User", userEventsTopic, e.userId().toString());
            case PostCreatedEvent e -> new Route("Post", postEventsTopic, e.postId().toString());
            case ReactionEvent e -> new Route("Post", reactionEventsTopic, e.postId().toString());
            case UserFollowedEvent e -> new Route("Follow", socialEventsTopic, e.followerId().toString());
            case UserUnfollowedEvent e -> new Route("Follow", socialEventsTopic, e.followerId().toString());
        };
    }

    @Order(0)
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void append(DomainEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("{} eventId={} raised outside a transaction; writing it to the outbox non-atomically",
                    event.eventType(), event.eventId());
        }
        var message = toMessage(event);
        repository.append(message);
        log.debug("Outbox: stored {} eventId={} for {} key={}", event.eventType(), event.eventId(),
                message.topic(), message.messageKey());
    }

    /** The outbox row for {@code event}: its own eventId and type, routed topic/key, JSON payload. */
    OutboxRepository.NewMessage toMessage(DomainEvent event) {
        var route = routeOf(event);
        return new OutboxRepository.NewMessage(event.eventId(), route.aggregateType(), event.eventType(),
                route.topic(), route.key(), codec.toJson(event));
    }

    /** Latency optimisation only: publish right after commit instead of waiting for the next poll. */
    @Order(1)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void wakeRelay(DomainEvent event) {
        relay.wakeUp();
    }
}
