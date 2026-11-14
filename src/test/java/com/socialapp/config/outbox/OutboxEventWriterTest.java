package com.socialapp.config.outbox;

import com.socialapp.shared.events.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Routing and wire format of the outbox write side; no infrastructure. */
class OutboxEventWriterTest {

    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final OutboxRelay relay = mock(OutboxRelay.class);
    private final DomainEventCodec codec = new DomainEventCodec();
    private final OutboxEventWriter writer = new OutboxEventWriter(repository, relay, codec,
            "user-events", "post-events", "reaction-events", "social-events");

    private static final UUID USER = UUID.randomUUID();
    private static final UUID POST = UUID.randomUUID();
    private static final UUID FOLLOWEE = UUID.randomUUID();

    private static List<DomainEvent> allEventTypes() {
        return List.of(
                new UserCreatedEvent(USER, "alice", "alice@test.com", "a.png"),
                new UserUpdatedEvent(USER, "alice2", null, "bio"),
                new PostCreatedEvent(POST, USER, "alice", "hello \"world\" ✓", null),
                new ReactionEvent(POST, USER, ReactionEvent.ReactionType.LIKE, ReactionEvent.ReactionAction.ADDED),
                new UserFollowedEvent(UUID.randomUUID(), Instant.parse("2026-01-02T03:04:05.006Z"), USER, FOLLOWEE),
                new UserUnfollowedEvent(UUID.randomUUID(), Instant.parse("2026-01-02T03:04:05.007Z"), USER, FOLLOWEE));
    }

    @Test
    void routesEveryEventTypeToTheSameTopicAndKeyAsBefore() {
        var routes = allEventTypes().stream().map(writer::routeOf).toList();

        assertThat(routes).containsExactly(
                new OutboxEventWriter.Route("User", "user-events", USER.toString()),
                new OutboxEventWriter.Route("User", "user-events", USER.toString()),
                new OutboxEventWriter.Route("Post", "post-events", POST.toString()),
                new OutboxEventWriter.Route("Post", "reaction-events", POST.toString()),
                new OutboxEventWriter.Route("Follow", "social-events", USER.toString()),
                new OutboxEventWriter.Route("Follow", "social-events", USER.toString()));
    }

    @Test
    void appendStoresTheEventWithItsOwnIdAndTypeAsJson() {
        var event = new PostCreatedEvent(POST, USER, "alice", "hi", "img.png");

        writer.append(event);

        var captor = ArgumentCaptor.forClass(OutboxRepository.NewMessage.class);
        verify(repository).append(captor.capture());
        var stored = captor.getValue();
        assertThat(stored.eventId()).isEqualTo(event.eventId());
        assertThat(stored.eventType()).isEqualTo("PostCreatedEvent");
        assertThat(stored.aggregateType()).isEqualTo("Post");
        assertThat(stored.topic()).isEqualTo("post-events");
        assertThat(stored.messageKey()).isEqualTo(POST.toString());
        assertThat(codec.fromJson(stored.payload())).isEqualTo(event);
        verifyNoInteractions(relay);
    }

    @Test
    void wakeRelayOnlyNudgesTheRelay() {
        writer.wakeRelay(allEventTypes().get(0));

        verify(relay).wakeUp();
        verifyNoInteractions(repository);
    }

    /**
     * The relay sends {@code codec.fromJson(payload)} through the same JsonSerializer as before, so the
     * bytes on the wire must equal those of the original event (eventId, occurredAt with nanos, type id).
     */
    @Test
    void roundTripThroughTheOutboxKeepsTheWireBytesIdentical() {
        try (var wire = new JsonSerializer<DomainEvent>()) {
            wire.configure(Map.of(JsonSerializer.ADD_TYPE_INFO_HEADERS, false), false); // as in KafkaConfig
            var events = new java.util.ArrayList<>(allEventTypes());
            events.add(new UserUpdatedEvent(UUID.randomUUID(), Instant.parse("2026-01-02T03:04:05.123456789Z"),
                    USER, "n", null, null));

            for (DomainEvent original : events) {
                String payload = codec.toJson(original);
                DomainEvent relayed = codec.fromJson(payload);

                assertThat(relayed).isEqualTo(original);
                assertThat(wire.serialize("t", relayed)).isEqualTo(wire.serialize("t", original));
                assertThat(payload).isEqualTo(new String(wire.serialize("t", original), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void unreadablePayloadIsReportedAsRowSpecific() {
        assertThatThrownBy(() -> codec.fromJson("{\"eventType\":\"NoSuchEvent\"}"))
                .isInstanceOf(DomainEventCodec.OutboxPayloadException.class)
                .satisfies(e -> assertThat(OutboxRelay.isRowSpecific(e)).isTrue());
        assertThat(OutboxRelay.isRowSpecific(new org.apache.kafka.common.errors.TimeoutException("down"))).isFalse();
        assertThat(OutboxRelay.isRowSpecific(new RuntimeException(
                new org.apache.kafka.common.errors.RecordTooLargeException("big")))).isTrue();
    }
}
