package com.socialapp.socialgraph.application.usecase;

/**
 * In-process signal (a Spring application event, never sent to Kafka) that a follow/unfollow changed the
 * graph and recorded a pending social event in Neo4j. Listeners bound to AFTER_COMMIT use it only to
 * forward the pending event without waiting for the next poll; correctness never depends on it.
 */
public record FollowGraphChanged() {
}
