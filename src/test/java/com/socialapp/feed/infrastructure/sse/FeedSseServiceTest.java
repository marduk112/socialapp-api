package com.socialapp.feed.infrastructure.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.feed.application.timeline.FeedScope;
import com.socialapp.feed.application.timeline.FolloweeLookup;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FeedSseServiceTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();

    /** Captures the event names sent to it instead of writing to a response. */
    static final class RecordingEmitter extends SseEmitter {
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<String> data = new CopyOnWriteArrayList<>();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            var parts = builder.build().iterator();
            String raw = parts.next().getData().toString();
            events.add(raw.substring("event:".length(), raw.indexOf('\n')));
            if (parts.hasNext()) data.add(parts.next().getData().toString()); // the JSON payload
        }

        /** Data of the last event sent (the "connected" heartbeat's is "ok"). */
        String lastData() {
            return data.get(data.size() - 1);
        }

        long newPosts() {
            return events.stream().filter("new-post"::equals).count();
        }
    }

    private final FolloweeLookup followees = mock(FolloweeLookup.class);
    private final List<RecordingEmitter> created = new CopyOnWriteArrayList<>();
    private final FeedSseService service = new FeedSseService(new ObjectMapper().findAndRegisterModules(), followees,
            () -> {
                var e = new RecordingEmitter();
                created.add(e);
                return e;
            });

    private static FeedSseService.PostSsePayload postBy(UUID author) {
        return new FeedSseService.PostSsePayload(UUID.randomUUID(), author, "u", null, "hi", null, Instant.now(),
                null, null, null, List.of(), List.of());
    }

    @Test
    void newPostPayloadCarriesHashtagsAndMentionsInTheRestShape() throws Exception {
        service.subscribe(ALICE, FeedScope.ALL);
        var payload = new FeedSseService.PostSsePayload(UUID.randomUUID(), BOB, "bob", null,
                "#Hello @alice", null, Instant.now(), null, null, null,
                List.of("hello"), List.of(new com.socialapp.feed.domain.Mention(ALICE, "alice")));

        service.broadcast(payload);

        var json = new ObjectMapper().readTree(created.get(0).lastData());
        assertThat(json.get("hashtags").toString()).isEqualTo("[\"hello\"]");
        assertThat(json.get("mentions").toString())
                .isEqualTo("[{\"userId\":\"" + ALICE + "\",\"username\":\"alice\"}]");
    }

    @Test
    void payloadWithoutTagsSerialisesEmptyArraysNotNull() throws Exception {
        service.subscribe(ALICE, FeedScope.ALL);

        service.broadcast(new FeedSseService.PostSsePayload(UUID.randomUUID(), BOB, "bob", null, "x", null,
                Instant.now(), null, null, null, null, null));

        var json = new ObjectMapper().readTree(created.get(0).lastData());
        assertThat(json.get("hashtags").isArray()).isTrue();
        assertThat(json.get("hashtags")).isEmpty();
        assertThat(json.get("mentions").isArray()).isTrue();
        assertThat(json.get("mentions")).isEmpty();
    }

    @Test
    void shutdownCompletesAndDropsAllStreams() {
        service.subscribe(ALICE, FeedScope.ALL);
        service.subscribe(BOB, FeedScope.FOLLOWING);
        assertThat(service.subscriberCount()).isEqualTo(2);

        service.completeAllOnShutdown();

        assertThat(service.subscriberCount()).isZero();
    }

    @Test
    void shutdownWithoutSubscribersIsNoOp() {
        service.completeAllOnShutdown();

        assertThat(service.subscriberCount()).isZero();
    }

    @Test
    void subscribeSendsConnectedForBothScopes() {
        service.subscribe(ALICE, FeedScope.ALL);
        service.subscribe(ALICE, FeedScope.FOLLOWING);

        assertThat(created).allSatisfy(e -> assertThat(e.events).containsExactly("connected"));
    }

    @Test
    void allScopeReceivesEveryPostWithoutConsultingTheProjection() {
        service.subscribe(ALICE, FeedScope.ALL);

        service.broadcast(postBy(BOB));
        service.broadcast(postBy(CAROL));

        assertThat(created.get(0).newPosts()).isEqualTo(2);
        verifyNoInteractions(followees);
    }

    @Test
    void followingScopeReceivesOnlyPostsTheProjectionAdmits() {
        when(followees.isVisibleInFollowing(ALICE, BOB)).thenReturn(true);
        when(followees.isVisibleInFollowing(ALICE, CAROL)).thenReturn(false);
        service.subscribe(ALICE, FeedScope.FOLLOWING);
        service.subscribe(CAROL, FeedScope.ALL);

        service.broadcast(postBy(BOB));
        service.broadcast(postBy(CAROL));

        assertThat(created.get(0).newPosts()).isEqualTo(1); // alice (following): only bob's
        assertThat(created.get(1).newPosts()).isEqualTo(2); // carol (all): both
    }

    @Test
    void followingFilterIsEvaluatedPerPost_soAFollowWhileConnectedTakesEffect() {
        when(followees.isVisibleInFollowing(ALICE, BOB)).thenReturn(false, true);
        service.subscribe(ALICE, FeedScope.FOLLOWING);

        service.broadcast(postBy(BOB)); // not followed yet
        service.broadcast(postBy(BOB)); // followed meanwhile (no reconnect)

        assertThat(created.get(0).newPosts()).isEqualTo(1);
    }

    @Test
    void projectionFailureSkipsFollowingSubscribersButNotOthers() {
        when(followees.isVisibleInFollowing(any(), any())).thenThrow(new IllegalStateException("db down"));
        service.subscribe(ALICE, FeedScope.FOLLOWING);
        service.subscribe(BOB, FeedScope.ALL);

        service.broadcast(postBy(CAROL));

        assertThat(created.get(0).newPosts()).isZero();
        assertThat(created.get(1).newPosts()).isEqualTo(1);
        assertThat(service.subscriberCount()).isEqualTo(2);
    }

    @Test
    void nullScopeMeansAll() {
        service.subscribe(ALICE, null);

        service.broadcast(postBy(CAROL));

        assertThat(created.get(0).newPosts()).isEqualTo(1);
        verifyNoInteractions(followees);
    }
}
