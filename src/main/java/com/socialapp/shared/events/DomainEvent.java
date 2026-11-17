package com.socialapp.shared.events;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "eventType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = UserCreatedEvent.class, name = "UserCreatedEvent"),
        @JsonSubTypes.Type(value = UserUpdatedEvent.class, name = "UserUpdatedEvent"),
        @JsonSubTypes.Type(value = PostCreatedEvent.class, name = "PostCreatedEvent"),
        @JsonSubTypes.Type(value = ReactionEvent.class,    name = "ReactionEvent"),
        @JsonSubTypes.Type(value = UserFollowedEvent.class,   name = "UserFollowedEvent"),
        @JsonSubTypes.Type(value = UserUnfollowedEvent.class, name = "UserUnfollowedEvent"),
        @JsonSubTypes.Type(value = CommentCreatedEvent.class, name = "CommentCreatedEvent")
})
public sealed interface DomainEvent permits UserCreatedEvent, UserUpdatedEvent, PostCreatedEvent, ReactionEvent,
        UserFollowedEvent, UserUnfollowedEvent, CommentCreatedEvent {
    UUID eventId();
    Instant occurredAt();
    String eventType();
}
