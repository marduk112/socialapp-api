package com.socialapp.shared.domain;

import org.springframework.data.domain.AbstractAggregateRoot;

/**
 * Base class for all Aggregate Roots in the domain.
 *
 * Aggregates record domain events with {@code registerEvent(...)}; Spring Data publishes them as
 * application events when the aggregate is saved through its repository, inside the caller's transaction.
 * They reach Kafka through the transactional outbox ({@code com.socialapp.config.outbox}): the event is
 * stored in {@code outbox_events} in the same database transaction (atomic with the aggregate change) and
 * relayed to Kafka after commit, at-least-once and in order per key. Domain and application code never
 * talk to Kafka directly.
 */
public abstract class AggregateRoot<T extends AggregateRoot<T>> extends AbstractAggregateRoot<T> {
}
