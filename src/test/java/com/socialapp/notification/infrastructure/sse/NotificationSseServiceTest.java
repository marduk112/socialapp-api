package com.socialapp.notification.infrastructure.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.notification.application.NotificationView;
import com.socialapp.notification.domain.NotificationType;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationSseServiceTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    /** Records event names/data instead of writing to a response. */
    static class RecordingEmitter extends SseEmitter {
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<String> data = new CopyOnWriteArrayList<>();
        boolean completed;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            var parts = builder.build().iterator();
            String raw = parts.next().getData().toString();
            events.add(raw.substring("event:".length(), raw.indexOf('\n')));
            if (parts.hasNext()) data.add(parts.next().getData().toString());
        }

        @Override
        public void complete() {
            completed = true;
        }
    }

    static final class DeadEmitter extends RecordingEmitter {
        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (!events.isEmpty()) throw new IOException("broken pipe");
            super.send(builder);
        }
    }

    private final List<RecordingEmitter> next = new CopyOnWriteArrayList<>();
    private final NotificationSseService service = new NotificationSseService(
            tools.jackson.databind.json.JsonMapper.builderWithJackson2Defaults()
                    .disable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build(),
            () -> next.isEmpty() ? new RecordingEmitter() : next.remove(0));

    private static NotificationView forUser(UUID recipient) {
        return new NotificationView(UUID.randomUUID(), NotificationType.FOLLOW, UUID.randomUUID(), null, null, null,
                null, null, false, Instant.parse("2026-11-15T10:00:00Z"), recipient);
    }

    private RecordingEmitter subscribe(UUID user) {
        var emitter = new RecordingEmitter();
        next.add(emitter);
        service.subscribe(user);
        return emitter;
    }

    @Test
    void subscribeSendsConnected_thenOnlyTheRecipientsOwnNotifications_toAllOfTheirStreams() throws Exception {
        var alice1 = subscribe(ALICE);
        var alice2 = subscribe(ALICE);
        var bob = subscribe(BOB);
        assertThat(alice1.events).containsExactly("connected");
        assertThat(alice1.data).containsExactly("ok");

        var n = forUser(ALICE);
        service.push(List.of(n));

        for (var e : List.of(alice1, alice2)) {
            assertThat(e.events).containsExactly("connected", "notification");
            var json = new ObjectMapper().readTree(e.data.get(1));
            assertThat(json.get("id").asText()).isEqualTo(n.id().toString());
            assertThat(json.get("type").asText()).isEqualTo("FOLLOW");
            assertThat(json.get("actorUsername").asText()).isEqualTo(NotificationView.UNKNOWN_ACTOR);
            assertThat(json.get("read").asBoolean()).isFalse();
            assertThat(json.get("createdAt").asText()).isEqualTo("2026-11-15T10:00:00Z");
            assertThat(json.has("recipientId")).isFalse();
            assertThat(json.get("postId").isNull()).isTrue();
        }
        assertThat(bob.events).containsExactly("connected"); // isolation
    }

    @Test
    void deadStreamsAreRemoved_otherStreamsStillServed() {
        var dead = new DeadEmitter();
        next.add(dead);
        service.subscribe(ALICE);
        var alive = subscribe(ALICE);
        assertThat(service.streamCount(ALICE)).isEqualTo(2);

        service.push(List.of(forUser(ALICE)));

        assertThat(service.streamCount(ALICE)).isEqualTo(1);
        assertThat(alive.events).containsExactly("connected", "notification");
    }

    @Test
    void shutdownCompletesEveryStream() {
        var a = subscribe(ALICE);
        var b = subscribe(BOB);
        service.completeAllOnShutdown();
        assertThat(a.completed).isTrue();
        assertThat(b.completed).isTrue();
        assertThat(service.streamCount(ALICE)).isZero();
        service.push(List.of(forUser(ALICE))); // nobody left: no error
    }
}
