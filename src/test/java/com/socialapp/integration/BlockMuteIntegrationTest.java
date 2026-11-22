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
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Stage 5A end to end through the real stack: block/mute over HTTP -> Neo4j (one Cypher statement, follows removed both
 * ways) -> Neo4j outbox -> Postgres outbox -> Kafka -> the Feed, Notification and UserManagement projections -> every
 * view they filter, then unblock/unmute restoring them. Projections are waited for by polling the observable effect
 * (never fixed sleeps). Same container/context lifecycle rules as {@link SocialAppIntegrationTest}.
 */
@Testcontainers(disabledWithoutDocker = true) // keep FIRST: its after-all (stop containers) must run last
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BlockMuteIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("socialapp_test").withUsername("test").withPassword("test");

    @Container
    static final Neo4jContainer neo4j = new Neo4jContainer("neo4j:5.26")
            .withAdminPassword("testpassword").withoutAuthentication();

    @Container
    static final ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:8.3.3"));

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
        registry.add("app.media.cleanup-enabled", () -> "false");
    }

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired Neo4jClient neo4jClient;

    private final HttpClient http = HttpClient.newHttpClient();

    record User(UUID id, String username, String token) {}

    private HttpResponse<String> call(String method, String path, User as, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (as != null) builder.header("Authorization", "Bearer " + as.token());
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return response.body().isEmpty() ? null : JSON.readTree(response.body());
    }

    private int status(String method, String path, User as) throws Exception {
        return call(method, path, as, null).statusCode();
    }

    private User register(String username) throws Exception {
        var body = ok(call("POST", "/users", null, Map.of("username", username, "email", username + "@test.io",
                "password", "password123")), 201);
        return new User(UUID.fromString(body.get("id").asText()), username, body.get("token").asText());
    }

    /** Retries while a user has not reached Neo4j yet (SocialGraph consumes user-events asynchronously): 404 until then. */
    private void follow(User follower, User followee) {
        await().atMost(WAIT).ignoreExceptions().until(() ->
                call("POST", "/social/follow", follower, Map.of("followeeId", followee.id())).statusCode() == 200);
    }

    private String post(User author, String content) throws Exception {
        return ok(call("POST", "/posts", author, Map.of("content", content)), 201).get("id").asText();
    }

    private static List<JsonNode> items(JsonNode page) {
        return StreamSupport.stream(page.get("items").spliterator(), false).toList();
    }

    private List<String> ids(String path, User as) throws Exception {
        var node = ok(call("GET", path, as, null), 200);
        var list = node.isArray() ? StreamSupport.stream(node.spliterator(), false).toList() : items(node);
        return list.stream().map(n -> (n.has("id") ? n.get("id") : n.get("userId")).asText()).toList();
    }

    private List<String> userIds(String path, User as) throws Exception {
        var node = ok(call("GET", path, as, null), 200);
        return StreamSupport.stream(node.spliterator(), false)
                .map(n -> (n.has("userId") ? n.get("userId") : n.get("id")).asText()).toList();
    }

    private List<JsonNode> notifications(User as) throws Exception {
        return items(ok(call("GET", "/notifications?size=100", as, null), 200));
    }

    private boolean hasNotificationFrom(User recipient, User actor, String type) throws Exception {
        return notifications(recipient).stream().anyMatch(n -> type.equals(n.get("type").asText())
                && actor.id().toString().equals(n.get("actorId").asText()));
    }

    private long trendPosts(User as, String tag) throws Exception {
        var trends = ok(call("GET", "/trends/hashtags?limit=20", as, null), 200);
        return StreamSupport.stream(trends.spliterator(), false).filter(t -> tag.equals(t.get("tag").asText()))
                .mapToLong(t -> t.get("posts").asLong()).findFirst().orElse(0);
    }

    private long outboxRows(String type, User source) {
        String field = type.contains("ollow") ? "followerId" : type.contains("lock") ? "blockerId" : "muterId";
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = ? AND payload->>'" + field
                + "' = ?", Long.class, type, source.id().toString());
    }

    private long pendingInNeo4j(String type, User source) {
        return neo4jClient.query("MATCH (o:SocialOutbox {type: $type}) "
                        + "WHERE coalesce(o.sourceId, o.followerId) = $source RETURN count(o) AS n")
                .bindAll(Map.of("type", type, "source", source.id().toString()))
                .fetchAs(Long.class).one().orElse(0L);
    }

    private JsonNode relationship(User me, User other) throws Exception {
        return ok(call("GET", "/social/relationship/" + other.id(), me, null), 200);
    }

    @Test
    @Order(1)
    void blockIsMutual_removesFollowsBothWays_refusesInteractions_hidesEverywhere_andUnblockRestores() throws Exception {
        var alice = register("alice5a");
        var bob = register("bob5a");
        var carol = register("carol5a");
        follow(alice, bob);
        follow(bob, alice);
        follow(carol, alice);
        follow(carol, bob);

        String tag = "blocktag5a";
        String alicePost = post(alice, "#" + tag + " alice speaks blockword5a");
        String bobPost = post(bob, "#" + tag + " bob speaks blockword5a");
        String carolPost = post(carol, "#" + tag + " carol speaks blockword5a");
        ok(call("POST", "/posts/" + alicePost + "/like", bob, null), 200);
        // a notification the block does not retract (the block's unfollow retracts bob's unread FOLLOW for good)
        ok(call("POST", "/posts/" + alicePost + "/comments", bob, Map.of("content", "nice")), 201);
        ok(call("PUT", "/posts/" + bobPost + "/bookmark", alice, null), 204);
        String bobsComment = ok(call("POST", "/posts/" + carolPost + "/comments", bob, Map.of("content", "bob here")), 201)
                .get("id").asText();
        String alicesComment = ok(call("POST", "/posts/" + carolPost + "/comments", alice,
                Map.of("content", "alice here")), 201).get("id").asText();
        await().atMost(WAIT).ignoreExceptions().until(() -> hasNotificationFrom(alice, bob, "LIKE"));
        await().atMost(WAIT).ignoreExceptions().until(() -> hasNotificationFrom(alice, bob, "COMMENT"));
        await().atMost(WAIT).ignoreExceptions().until(() -> userIds("/search/users?q=bob5a", alice)
                .contains(bob.id().toString()));
        assertThat(ids("/feed?size=100", alice)).contains(bobPost);
        long unreadBefore = ok(call("GET", "/notifications/unread-count", alice, null), 200).get("count").asLong();

        // ── alice blocks bob ───────────────────────────────────────────────
        assertThat(status("PUT", "/social/block/" + bob.id(), alice)).isEqualTo(204);

        // The graph (source of truth) is consistent at once: both follows gone, re-follow refused both ways (403).
        assertThat(userIds("/social/" + alice.id() + "/following", alice)).doesNotContain(bob.id().toString());
        assertThat(userIds("/social/" + bob.id() + "/following", bob)).doesNotContain(alice.id().toString());
        assertThat(userIds("/social/" + carol.id() + "/following", carol))
                .contains(alice.id().toString(), bob.id().toString()); // third parties untouched
        assertThat(call("POST", "/social/follow", bob, Map.of("followeeId", alice.id())).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/social/follow", alice, Map.of("followeeId", bob.id())).statusCode()).isEqualTo(403);
        assertThat(userIds("/social/blocks", alice)).containsExactly(bob.id().toString());
        assertThat(userIds("/social/blocks", bob)).isEmpty();
        var aliceToBob = relationship(alice, bob);
        assertThat(aliceToBob.get("blocked").asBoolean()).isTrue();
        assertThat(aliceToBob.get("following").asBoolean()).isFalse();
        assertThat(aliceToBob.get("followedBy").asBoolean()).isFalse();
        var bobToAlice = relationship(bob, alice);
        assertThat(bobToAlice.get("blocked").asBoolean()).isFalse(); // bob is not told he is blocked
        assertThat(bobToAlice.has("blockedBy")).isFalse();
        assertThat(userIds("/social/suggestions?limit=20", alice)).doesNotContain(bob.id().toString());
        assertThat(userIds("/social/suggestions?limit=20", bob)).doesNotContain(alice.id().toString());

        // One UserBlockedEvent and one UserUnfollowedEvent per removed direction reach the outbox; blocking again is a
        // no-op (nothing recorded); self -> 400, unknown user -> 404.
        await().atMost(WAIT).until(() -> outboxRows("UserBlockedEvent", alice) == 1
                && pendingInNeo4j("UserBlockedEvent", alice) == 0
                && outboxRows("UserUnfollowedEvent", alice) == 1 && outboxRows("UserUnfollowedEvent", bob) == 1);
        assertThat(status("PUT", "/social/block/" + bob.id(), alice)).isEqualTo(204);
        assertThat(outboxRows("UserBlockedEvent", alice) + pendingInNeo4j("UserBlockedEvent", alice)).isEqualTo(1);
        assertThat(status("PUT", "/social/block/" + alice.id(), alice)).isEqualTo(400);
        assertThat(status("PUT", "/social/block/" + UUID.randomUUID(), alice)).isEqualTo(404);
        assertThat(status("PUT", "/social/block/not-a-uuid", alice)).isEqualTo(400);

        // Feed (once its projection has the block): every view hides the other side, in both directions.
        await().atMost(WAIT).ignoreExceptions().until(() -> status("GET", "/posts/" + bobPost, alice) == 404);
        await().atMost(WAIT).ignoreExceptions().until(() -> status("GET", "/posts/" + alicePost, bob) == 404);
        assertThat(ids("/feed?size=100", alice)).contains(alicePost, carolPost).doesNotContain(bobPost);
        assertThat(ids("/feed?size=100", bob)).contains(bobPost, carolPost).doesNotContain(alicePost);
        assertThat(ids("/feed?scope=following&size=100", carol)).contains(alicePost, bobPost); // carol: unaffected
        assertThat(ids("/hashtags/" + tag + "/posts", alice)).containsExactlyInAnyOrder(alicePost, carolPost);
        assertThat(ids("/hashtags/" + tag + "/posts", bob)).containsExactlyInAnyOrder(bobPost, carolPost);
        assertThat(ids("/search/posts?q=blockword5a", alice)).containsExactlyInAnyOrder(alicePost, carolPost);
        assertThat(ids("/search/posts?q=blockword5a", bob)).containsExactlyInAnyOrder(bobPost, carolPost);
        assertThat(trendPosts(alice, tag)).isEqualTo(2);
        assertThat(trendPosts(bob, tag)).isEqualTo(2);
        assertThat(trendPosts(carol, tag)).isEqualTo(3);
        assertThat(ids("/bookmarks", alice)).doesNotContain(bobPost);
        assertThat(ids("/posts/" + carolPost + "/comments", alice)).containsExactly(alicesComment);
        assertThat(ids("/posts/" + carolPost + "/comments", bob)).containsExactly(bobsComment);
        assertThat(ids("/posts/" + carolPost + "/comments", carol)).containsExactly(bobsComment, alicesComment);
        // interactions with the other side's content are answered as if it did not exist (404)
        assertThat(status("GET", "/posts/" + bobPost + "/comments", alice)).isEqualTo(404);
        assertThat(status("POST", "/posts/" + bobPost + "/like", alice)).isEqualTo(404);
        assertThat(status("POST", "/posts/" + alicePost + "/dislike", bob)).isEqualTo(404);
        assertThat(call("POST", "/posts/" + alicePost + "/comments", bob, Map.of("content", "x")).statusCode())
                .isEqualTo(404);
        assertThat(call("POST", "/posts/" + carolPost + "/comments", alice,
                Map.of("content", "reply", "parentCommentId", bobsComment)).statusCode()).isEqualTo(404);
        assertThat(status("POST", "/comments/" + alicesComment + "/like", bob)).isEqualTo(404);
        assertThat(status("POST", "/posts/" + bobPost + "/repost", alice)).isEqualTo(404);
        assertThat(call("POST", "/posts/" + alicePost + "/quote", bob, Map.of("comment", "q")).statusCode())
                .isEqualTo(404);
        assertThat(status("PUT", "/posts/" + alicePost + "/bookmark", bob)).isEqualTo(404);
        // removing an own earlier reaction stays possible
        assertThat(status("DELETE", "/posts/" + alicePost + "/like", bob)).isEqualTo(200);

        // People search (UserManagement's projection) and notifications (Notification's projection) follow.
        await().atMost(WAIT).ignoreExceptions().until(() -> userIds("/search/users?q=bob5a", alice).isEmpty());
        assertThat(userIds("/search/users?q=alice5a", bob)).isEmpty();
        assertThat(userIds("/search/users?q=5a", carol)).contains(alice.id().toString(), bob.id().toString());
        await().atMost(WAIT).ignoreExceptions().until(() -> !hasNotificationFrom(alice, bob, "COMMENT"));
        assertThat(ok(call("GET", "/notifications/unread-count", alice, null), 200).get("count").asLong())
                .isLessThan(unreadBefore);

        // ── unblock: everything is visible again, follows are NOT restored ─
        assertThat(status("DELETE", "/social/block/" + bob.id(), alice)).isEqualTo(204);
        assertThat(status("DELETE", "/social/block/" + bob.id(), alice)).isEqualTo(204); // idempotent
        assertThat(status("DELETE", "/social/block/" + UUID.randomUUID(), alice)).isEqualTo(204);
        assertThat(userIds("/social/blocks", alice)).isEmpty();
        await().atMost(WAIT).ignoreExceptions().until(() -> status("GET", "/posts/" + bobPost, alice) == 200);
        await().atMost(WAIT).ignoreExceptions().until(() -> status("GET", "/posts/" + alicePost, bob) == 200);
        assertThat(ids("/feed?size=100", alice)).contains(bobPost);
        assertThat(ids("/bookmarks", alice)).contains(bobPost); // the bookmark was kept
        assertThat(ids("/posts/" + carolPost + "/comments", alice)).containsExactly(bobsComment, alicesComment);
        await().atMost(WAIT).ignoreExceptions().until(() -> userIds("/search/users?q=bob5a", alice)
                .contains(bob.id().toString()));
        await().atMost(WAIT).ignoreExceptions().until(() -> hasNotificationFrom(alice, bob, "COMMENT"));
        assertThat(userIds("/social/" + alice.id() + "/following", alice)).doesNotContain(bob.id().toString());
        assertThat(relationship(alice, bob).get("following").asBoolean()).isFalse();
        assertThat(call("POST", "/social/follow", alice, Map.of("followeeId", bob.id())).statusCode()).isEqualTo(200);
    }

    @Test
    @Order(2)
    void muteIsOneDirectionalAndSilent_hidesOnlyForTheMuter_andUnmuteRestores() throws Exception {
        var erin = register("erin5a");
        var frank = register("frank5a");
        var gina = register("gina5a");
        follow(frank, erin);
        follow(erin, frank);
        String tag = "mutetag5a";
        String frankPost = post(frank, "#" + tag + " frank talks muteword5a");
        String erinPost = post(erin, "#" + tag + " erin talks muteword5a");
        await().atMost(WAIT).ignoreExceptions().until(() -> hasNotificationFrom(erin, frank, "FOLLOW"));
        assertThat(userIds("/social/suggestions?limit=20", gina)).contains(frank.id().toString());

        assertThat(status("PUT", "/social/mute/" + frank.id(), erin)).isEqualTo(204);
        assertThat(status("PUT", "/social/mute/" + frank.id(), erin)).isEqualTo(204); // idempotent
        assertThat(status("PUT", "/social/mute/" + erin.id(), erin)).isEqualTo(400);
        assertThat(status("PUT", "/social/mute/" + UUID.randomUUID(), erin)).isEqualTo(404);
        assertThat(userIds("/social/mutes", erin)).containsExactly(frank.id().toString());
        assertThat(userIds("/social/mutes", frank)).isEmpty();
        var erinToFrank = relationship(erin, frank);
        assertThat(erinToFrank.get("muted").asBoolean()).isTrue();
        assertThat(erinToFrank.get("following").asBoolean()).isTrue();   // follows untouched both ways
        assertThat(erinToFrank.get("followedBy").asBoolean()).isTrue();
        assertThat(relationship(frank, erin).get("muted").asBoolean()).isFalse(); // frank is not told

        // erin's lists no longer show frank (once Feed has the mute); frank's are unchanged
        await().atMost(WAIT).ignoreExceptions().until(() -> !ids("/feed?size=100", erin).contains(frankPost));
        assertThat(ids("/feed?scope=following&size=100", erin)).doesNotContain(frankPost).contains(erinPost);
        assertThat(ids("/hashtags/" + tag + "/posts", erin)).containsExactly(erinPost);
        assertThat(ids("/search/posts?q=muteword5a", erin)).containsExactly(erinPost);
        assertThat(trendPosts(erin, tag)).isEqualTo(1);
        assertThat(trendPosts(frank, tag)).isEqualTo(2);
        assertThat(ids("/feed?size=100", frank)).contains(frankPost, erinPost);
        // explicitly opened content stays visible, and both can still interact
        assertThat(status("GET", "/posts/" + frankPost, erin)).isEqualTo(200);
        assertThat(status("POST", "/posts/" + frankPost + "/like", erin)).isEqualTo(200);
        assertThat(status("POST", "/posts/" + erinPost + "/like", frank)).isEqualTo(200);
        assertThat(userIds("/search/users?q=frank5a", erin)).containsExactly(frank.id().toString()); // not a block
        // notifications from frank are hidden for erin only; suggestions skip muted users
        await().atMost(WAIT).ignoreExceptions().until(() -> !hasNotificationFrom(erin, frank, "FOLLOW"));
        await().atMost(WAIT).ignoreExceptions().until(() -> hasNotificationFrom(frank, erin, "LIKE"));
        ok(call("DELETE", "/social/follow", erin, Map.of("followeeId", frank.id())), 204);
        assertThat(userIds("/social/suggestions?limit=20", erin)).doesNotContain(frank.id().toString());

        assertThat(status("DELETE", "/social/mute/" + frank.id(), erin)).isEqualTo(204);
        assertThat(status("DELETE", "/social/mute/" + frank.id(), erin)).isEqualTo(204);
        assertThat(userIds("/social/mutes", erin)).isEmpty();
        await().atMost(WAIT).ignoreExceptions().until(() -> ids("/feed?size=100", erin).contains(frankPost));
        await().atMost(WAIT).ignoreExceptions().until(() -> hasNotificationFrom(erin, frank, "FOLLOW"));
        assertThat(userIds("/social/suggestions?limit=20", erin)).contains(frank.id().toString());
    }

    @Test
    @Order(3)
    void blockAndMuteEndpointsRequireAuthentication() throws Exception {
        var id = UUID.randomUUID();
        for (String[] request : new String[][] {{"PUT", "/social/block/" + id}, {"DELETE", "/social/block/" + id},
                {"GET", "/social/blocks"}, {"PUT", "/social/mute/" + id}, {"DELETE", "/social/mute/" + id},
                {"GET", "/social/mutes"}, {"GET", "/social/relationship/" + id}}) {
            assertThat(status(request[0], request[1], null)).as(request[1]).isEqualTo(401);
        }
    }
}
