package com.socialapp.config.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.shared.events.DomainEvent;
import org.springframework.kafka.support.JacksonUtils;

/**
 * JSON form of a {@link DomainEvent} in the outbox. Uses the same mapper as Spring Kafka's
 * {@code JsonSerializer} default ({@link JacksonUtils#enhancedObjectMapper()}), so the stored payload is
 * the JSON that goes on the wire, and {@code fromJson(toJson(e))} yields an equal event (incl. eventId and
 * occurredAt with full precision). The relay re-sends that event through the regular
 * {@code KafkaTemplate<String, DomainEvent>}, so the bytes on the wire are produced exactly as before the
 * outbox existed.
 */
public final class DomainEventCodec {

    private final ObjectMapper mapper = JacksonUtils.enhancedObjectMapper();

    public String toJson(DomainEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize " + event.eventType() + " " + event.eventId(), e);
        }
    }

    /** @throws OutboxPayloadException if the payload is not a readable DomainEvent (a poison row) */
    public DomainEvent fromJson(String json) {
        try {
            return mapper.readValue(json, DomainEvent.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new OutboxPayloadException("Unreadable outbox payload: " + originalMessage(e), e);
        }
    }

    private static String originalMessage(Exception e) {
        return e instanceof JsonProcessingException j ? j.getOriginalMessage() : e.getMessage();
    }

    /** A stored payload that can never be published as is. Row-specific, so it may get the row parked. */
    public static final class OutboxPayloadException extends RuntimeException {
        OutboxPayloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
