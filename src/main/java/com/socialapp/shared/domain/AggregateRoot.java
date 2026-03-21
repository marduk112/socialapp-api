package com.socialapp.shared.domain;

import org.springframework.data.domain.AbstractAggregateRoot;

/**
 * Base class for all Aggregate Roots in the domain.
 *
 * Design choice - Spring @DomainEvents vs Outbox Pattern:
 *
 * We use Spring Data's AbstractAggregateRoot (@DomainEvents mechanism) which publishes
 * domain events via ApplicationEventPublisher immediately after the repository.save() call,
 * still within the same transaction. Combined with @TransactionalEventListener(phase=AFTER_COMMIT)
 * on the Kafka publisher, we get at-least-once delivery semantics with low complexity.
 *
 * Trade-offs vs Outbox Pattern:
 * - SIMPLER: No extra outbox table, no polling scheduler needed.
 * - LIMITATION: If the Kafka publish fails after DB commit, the event is lost (no durable log).
 * - OUTBOX advantage: Writes event to DB table in the same transaction → guaranteed delivery
 *   even if Kafka is temporarily down, at the cost of a background polling job.
 *
 * For production systems with strict "at-least-once" guarantees across failures, the Outbox
 * pattern (Debezium CDC or a scheduled poller) is preferred. For this project, the
 * @DomainEvents approach is sufficient and keeps the codebase clean.
 */
public abstract class AggregateRoot<T extends AggregateRoot<T>> extends AbstractAggregateRoot<T> {
}
