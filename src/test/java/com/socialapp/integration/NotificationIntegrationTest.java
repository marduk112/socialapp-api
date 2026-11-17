package com.socialapp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Stage 3A end to end through the real stack (HTTP -> Feed/SocialGraph -> outbox -> Kafka -> Notification -> REST and
 * SSE): follow, like (and unlike removal), comment, reply (single), mention, repost/quote with snippets, live delivery
 * over SSE and isolation between users. Same container/context lifecycle rules as {@link SocialAppIntegrationTest}.
 */
@Testcontainers(disabledWithoutDocker = true) // keep FIRST: its after-all (stop containers) must run last
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("socialapp_test").withUsername("test").withPassword("test");

    @Container
    static final Neo4jContainer neo4j = new Neo4jContainer("neo4j:5.18")
            .withAdminPassword("testpassword").withoutAuthentication();

    @Container
    static final ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.neo4j.uri", neo4j::getBoltUrl);
        registry.add("spring.neo4j.authentication.username", () -> "neo4j");
        registry.add("spring.neo4j.authentication.password", () -> "testpassword");
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("server.address", () -> "127.0.0.1"); // see SocialAppIntegrationTest
        registry.add("app.media.cleanup-enabled", () -> "false"); // scheduled media purge off in tests
    }

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    record User(UUID id, String username, String token) {}

    private String base() {
        return "http://localhost:" + port + "/api/v1";
    }

    private HttpResponse<String> call(String method, String path, User as, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base() + path)).header("Content-Type", "application/json");
        if (as != null) builder.header("Authorization", "Bearer " + as.token());
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return response.body().isEmpty() ? null : JSON.readTree(response.body());
    }

    private User register(String username) throws Exception {
        var body = ok(call("POST", "/users", null, Map.of("username", username, "email", username + "@test.io",
                "password", "password123")), 201);
        return new User(UUID.fromString(body.get("id").asText()), username, body.get("token").asText());
    }

    private List<JsonNode> notifications(User as) throws Exception {
        var page = ok(call("GET", "/notifications?size=100", as, null), 200);
        return StreamSupport.stream(page.get("items").spliterator(), false).toList();
    }

    private JsonNode awaitNotification(User as, String type, String field, String value) {
        JsonNode[] found = new JsonNode[1];
        await().atMost(WAIT).ignoreExceptions().until(() -> {
            found[0] = notifications(as).stream()
                    .filter(n -> type.equals(n.get("type").asText()) && value.equals(n.get(field).asText()))
                    .findFirst().orElse(null);
            return found[0] != null;
        });
        return found[0];
    }

    private long unread(User as) throws Exception {
        return ok(call("GET", "/notifications/unread-count", as, null), 200).get("count").asLong();
    }

    /** An open SSE stream: every (event, data) pair received. */
    final class Stream implements AutoCloseable {
        final ConcurrentLinkedQueue<String[]> events = new ConcurrentLinkedQueue<>();
        private final HttpClient client = HttpClient.newHttpClient();
        private final ExecutorService reader = Executors.newSingleThreadExecutor();
        private final CompletableFuture<HttpResponse<java.util.stream.Stream<String>>> response;

        Stream(User as) {
            response = client.sendAsync(HttpRequest.newBuilder(URI.create(
                    base() + "/notifications/stream?access_token=" + as.token())).GET().build(),
                    HttpResponse.BodyHandlers.ofLines());
            reader.submit(() -> {
                String[] event = new String[1];
                response.get().body().forEach(line -> {
                    if (line.startsWith("event:")) event[0] = line.substring(6);
                    else if (line.startsWith("data:")) events.add(new String[]{event[0], line.substring(5)});
                });
                return null;
            });
            await().atMost(Duration.ofSeconds(10)).until(() -> events.stream()
                    .anyMatch(e -> "connected".equals(e[0]) && "ok".equals(e[1])));
        }

        List<JsonNode> notifications() {
            List<JsonNode> result = new ArrayList<>();
            for (var e : events) {
                if (!"notification".equals(e[0])) continue;
                try {
                    result.add(JSON.readTree(e[1]));
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }
            return result;
        }

        boolean received(String id) {
            return notifications().stream().anyMatch(n -> id.equals(n.get("id").asText()));
        }

        @Override
        public void close() {
            reader.shutdownNow();
            response.cancel(true);
            client.shutdownNow(); // really disconnect (cancel() alone leaves the server request open)
        }
    }

    @Test
    @Order(1)
    void everyActionNotifiesTheRightUserOnce_overRestAndLiveOverSse() throws Exception {
        var alice = register("alice3a");
        var bob = register("bob3a");
        var carol = register("carol3a");
        // Mentions resolve against Feed's profiles; names in notifications come from Notification's own projection.
        await().atMost(WAIT).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM author_profiles WHERE author_id IN (?, ?, ?)", Long.class,
                alice.id(), bob.id(), carol.id()) == 3);
        await().atMost(WAIT).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM notification_actor_profiles WHERE user_id IN (?, ?, ?)", Long.class,
                alice.id(), bob.id(), carol.id()) == 3);

        try (var bobStream = new Stream(bob); var carolStream = new Stream(carol)) {
            // FOLLOW
            ok(call("POST", "/social/follow", alice, Map.of("followeeId", bob.id())), 200);
            var follow = awaitNotification(bob, "FOLLOW", "actorId", alice.id().toString());
            assertThat(follow.get("actorUsername").asText()).isEqualTo("alice3a");
            assertThat(follow.get("postId").isNull()).isTrue();
            assertThat(follow.get("commentId").isNull()).isTrue();
            assertThat(follow.get("read").asBoolean()).isFalse();
            assertThat(follow.get("createdAt").asText()).isNotBlank();

            // LIKE, then unlike: the unread notification is removed
            String bobsPost = ok(call("POST", "/posts", bob, Map.of("content", "bob's\n  first post")), 201)
                    .get("id").asText();
            ok(call("POST", "/posts/" + bobsPost + "/like", alice, null), 200);
            var like = awaitNotification(bob, "LIKE", "postId", bobsPost);
            assertThat(like.get("snippet").asText()).isEqualTo("bob's first post");
            assertThat(like.get("commentId").isNull()).isTrue();
            long unreadWithLike = unread(bob);
            ok(call("DELETE", "/posts/" + bobsPost + "/like", alice, null), 200);
            await().atMost(WAIT).ignoreExceptions().until(() -> notifications(bob).stream()
                    .noneMatch(n -> "LIKE".equals(n.get("type").asText())));
            assertThat(unread(bob)).isEqualTo(unreadWithLike - 1);
            // a dislike never notifies (checked below, once later events have been processed)
            ok(call("POST", "/posts/" + bobsPost + "/dislike", carol, null), 200);

            // COMMENT
            var aliceComment = ok(call("POST", "/posts/" + bobsPost + "/comments", alice,
                    Map.of("content", "nice post")), 201).get("id").asText();
            var comment = awaitNotification(bob, "COMMENT", "commentId", aliceComment);
            assertThat(comment.get("postId").asText()).isEqualTo(bobsPost);
            assertThat(comment.get("snippet").asText()).isEqualTo("nice post");

            // REPLY to bob's comment on bob's own post: exactly one notification, a REPLY
            var bobComment = ok(call("POST", "/posts/" + bobsPost + "/comments", bob,
                    Map.of("content", "thanks")), 201).get("id").asText();
            var reply = ok(call("POST", "/posts/" + bobsPost + "/comments", alice,
                    Map.of("content", "you're welcome", "parentCommentId", bobComment)), 201).get("id").asText();
            awaitNotification(bob, "REPLY", "commentId", reply);
            assertThat(notifications(bob).stream().filter(n -> reply.equals(n.get("commentId").asText())))
                    .singleElement().satisfies(n -> assertThat(n.get("type").asText()).isEqualTo("REPLY"));

            // LIKE of a comment: opens the post, carries the comment
            ok(call("POST", "/comments/" + bobComment + "/like", carol, null), 200);
            var commentLike = awaitNotification(bob, "LIKE", "commentId", bobComment);
            assertThat(commentLike.get("postId").asText()).isEqualTo(bobsPost);
            assertThat(commentLike.get("snippet").asText()).isEqualTo("thanks");
            assertThat(commentLike.get("actorUsername").asText()).isEqualTo("carol3a");

            // MENTION
            String mentionPost = ok(call("POST", "/posts", alice, Map.of("content", "hey @bob3a look")), 201)
                    .get("id").asText();
            var mention = awaitNotification(bob, "MENTION", "postId", mentionPost);
            assertThat(mention.get("snippet").asText()).isEqualTo("hey @bob3a look");

            // REPOST (snippet = the reposted text) and QUOTE (snippet = the quote; no extra MENTION)
            ok(call("POST", "/posts/" + bobsPost + "/repost", alice, null), 201);
            var repost = awaitNotification(bob, "REPOST", "postId", bobsPost);
            assertThat(repost.get("snippet").asText()).isEqualTo("bob's first post");
            String quote = ok(call("POST", "/posts/" + bobsPost + "/quote", alice,
                    Map.of("comment", "so true @bob3a")), 201).get("id").asText();
            var quoted = awaitNotification(bob, "QUOTE", "postId", quote);
            assertThat(quoted.get("snippet").asText()).isEqualTo("so true @bob3a");
            assertThat(notifications(bob).stream().filter(n -> quote.equals(n.get("postId").asText())))
                    .hasSize(1);
            // undo + repost again: still a single REPOST notification
            ok(call("DELETE", "/posts/" + bobsPost + "/repost", alice, null), 204);
            ok(call("POST", "/posts/" + bobsPost + "/repost", alice, null), 201);

            // carol's own notification (follow), to check isolation on both sides
            ok(call("POST", "/social/follow", alice, Map.of("followeeId", carol.id())), 200);
            var carolsFollow = awaitNotification(carol, "FOLLOW", "actorId", alice.id().toString());

            // Live: bob's stream received every visible notification of his, with the REST shape; carol's only hers
            var bobsVisible = notifications(bob);
            assertThat(bobsVisible).extracting(n -> n.get("type").asText())
                    .containsExactlyInAnyOrder("REPOST", "QUOTE", "MENTION", "LIKE", "REPLY", "COMMENT", "FOLLOW");
            await().atMost(WAIT).until(() -> bobsVisible.stream().allMatch(n -> bobStream.received(n.get("id").asText())));
            var liveQuote = bobStream.notifications().stream()
                    .filter(n -> quoted.get("id").asText().equals(n.get("id").asText())).findFirst().orElseThrow();
            assertThat(liveQuote).isEqualTo(quoted);
            assertThat(bobStream.received(carolsFollow.get("id").asText())).isFalse();
            await().atMost(WAIT).until(() -> carolStream.received(carolsFollow.get("id").asText()));
            assertThat(carolStream.notifications()).extracting(n -> n.get("id").asText())
                    .containsOnly(carolsFollow.get("id").asText());
            // the retracted like was pushed live once, and nothing for dislike / own comment / second repost
            assertThat(bobStream.notifications()).extracting(n -> n.get("type").asText())
                    .containsExactlyInAnyOrder("FOLLOW", "LIKE", "COMMENT", "REPLY", "LIKE", "MENTION", "REPOST",
                            "QUOTE");

            // Read marking: bob cannot touch carol's; specific ids; then all
            long bobUnread = unread(bob);
            assertThat(bobUnread).isEqualTo(7);
            ok(call("POST", "/notifications/read", bob, Map.of("ids", List.of(
                    carolsFollow.get("id").asText(), follow.get("id").asText()))), 204);
            assertThat(unread(carol)).isEqualTo(1);
            assertThat(unread(bob)).isEqualTo(6);
            assertThat(notifications(bob).stream().filter(n -> n.get("id").equals(follow.get("id"))))
                    .singleElement().satisfies(n -> assertThat(n.get("read").asBoolean()).isTrue());
            ok(call("POST", "/notifications/read", bob, null), 204);
            assertThat(unread(bob)).isZero();
            assertThat(unread(carol)).isEqualTo(1);
        }
    }

    @Test
    @Order(2)
    void feedPublishesTheEnrichedEventsThroughTheOutbox() throws Exception {
        var dan = register("dan3a");
        var erin = register("erin3a");
        String post = ok(call("POST", "/posts", dan, Map.of("content", "dan's post")), 201).get("id").asText();
        String comment = ok(call("POST", "/posts/" + post + "/comments", erin, Map.of("content", "erin says hi")), 201)
                .get("id").asText();
        ok(call("POST", "/comments/" + comment + "/like", dan, null), 200);

        var commentRow = jdbc.queryForMap("SELECT topic, message_key, aggregate_type, payload::text AS payload "
                + "FROM outbox_events WHERE event_type = 'CommentCreatedEvent' AND payload->>'commentId' = ?", comment);
        assertThat(commentRow.get("topic")).isEqualTo("comment-events");
        assertThat(commentRow.get("message_key")).isEqualTo(post);
        assertThat(commentRow.get("aggregate_type")).isEqualTo("Comment");
        var payload = JSON.readTree((String) commentRow.get("payload"));
        assertThat(payload.get("postAuthorId").asText()).isEqualTo(dan.id().toString());
        assertThat(payload.get("authorId").asText()).isEqualTo(erin.id().toString());
        assertThat(payload.get("contentSnippet").asText()).isEqualTo("erin says hi");

        var reactionRow = jdbc.queryForMap("SELECT topic, message_key, payload::text AS payload FROM outbox_events "
                + "WHERE event_type = 'ReactionEvent' AND payload->>'commentId' = ?", comment);
        assertThat(reactionRow.get("topic")).isEqualTo("reaction-events");
        assertThat(reactionRow.get("message_key")).isEqualTo(post);
        var reaction = JSON.readTree((String) reactionRow.get("payload"));
        assertThat(reaction.get("target").asText()).isEqualTo("COMMENT");
        assertThat(reaction.get("targetOwnerId").asText()).isEqualTo(erin.id().toString());

        await().atMost(WAIT).until(() -> jdbc.queryForObject("SELECT count(*) FROM outbox_events "
                + "WHERE payload->>'commentId' = ? AND published_at IS NULL", Long.class, comment) == 0);
        awaitNotification(dan, "COMMENT", "commentId", comment);
        awaitNotification(erin, "LIKE", "commentId", comment);
    }

    @Test
    @Order(3)
    void streamRejectsAMissingOrInvalidToken() throws Exception {
        for (String query : List.of("", "?access_token=not-a-jwt")) {
            var response = http.send(HttpRequest.newBuilder(URI.create(base() + "/notifications/stream" + query))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(401);
        }
    }
}
