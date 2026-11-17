package com.socialapp.config.outbox;

import com.socialapp.shared.events.DomainEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON form of a {@link DomainEvent} in the outbox. Uses the same mapper as the Kafka producer
 * ({@link #wireMapper()}, see {@code KafkaConfig}), so the stored payload is the JSON that goes on the wire, and
 * {@code fromJson(toJson(e))} yields an equal event (incl. eventId and occurredAt with full precision). The relay
 * re-sends that event through the regular {@code KafkaTemplate<String, DomainEvent>}, so the bytes on the wire are
 * produced exactly as before the outbox existed.
 */
public final class DomainEventCodec {

    private final JsonMapper mapper = wireMapper();

    /**
     * The one JSON mapping of domain events (outbox payload, Kafka record values, re-serialised dead letters).
     *
     * <p>Jackson 3 configured to write exactly what Spring Kafka's Jackson 2 {@code JsonSerializer} wrote before
     * Spring Boot 4 ({@code JacksonUtils.enhancedObjectMapper()}: Jackson 2 defaults, unknown properties ignored, no
     * default view inclusion). The Jackson 2 defaults matter for the wire format: properties in declaration order (not
     * alphabetical) and {@code occurredAt} as decimal epoch seconds with nanoseconds (not an ISO string).
     * {@code DomainEventWireFormatTest} pins the bytes.
     */
    public static JsonMapper wireMapper() {
        return JsonMapper.builderWithJackson2Defaults()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
                .build();
    }

    public String toJson(DomainEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize " + event.eventType() + " " + event.eventId(), e);
        }
    }

    /** @throws OutboxPayloadException if the payload is not a readable DomainEvent (a poison row) */
    public DomainEvent fromJson(String json) {
        try {
            return mapper.readValue(json, DomainEvent.class);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new OutboxPayloadException("Unreadable outbox payload: " + originalMessage(e), e);
        }
    }

    private static String originalMessage(Exception e) {
        return e instanceof JacksonException j ? j.getOriginalMessage() : e.getMessage();
    }

    /** A stored payload that can never be published as is. Row-specific, so it may get the row parked. */
    public static final class OutboxPayloadException extends RuntimeException {
        OutboxPayloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
