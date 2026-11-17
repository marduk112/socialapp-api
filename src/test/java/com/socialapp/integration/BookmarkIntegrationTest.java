package com.socialapp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 3B end to end through the real stack (HTTP -> security -> Feed -> Postgres): bookmark, list with the Post DTO,
 * the {@code bookmarked} flag in the feed and on a single post, paging, isolation between users, unbookmark, and
 * cascade when the post is deleted. Same container/context lifecycle rules as {@link SocialAppIntegrationTest}.
 */
@Testcontainers(disabledWithoutDocker = true) // keep FIRST: its after-all (stop containers) must run last
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BookmarkIntegrationTest {

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
        registry.add("app.media.cleanup-enabled", () -> "false"); // scheduled media purge off in tests
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort int port;
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

    private User register(String username) throws Exception {
        var body = ok(call("POST", "/users", null, Map.of("username", username, "email", username + "@test.io",
                "password", "password123")), 201);
        return new User(UUID.fromString(body.get("id").asText()), username, body.get("token").asText());
    }

    private String post(User as, String content) throws Exception {
        return ok(call("POST", "/posts", as, Map.of("content", content)), 201).get("id").asText();
    }

    private List<String> bookmarkIds(User as, int size) throws Exception {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        do {
            var page = ok(call("GET", "/bookmarks?size=" + size + (cursor == null ? "" : "&cursor=" + cursor), as, null), 200);
            page.get("items").forEach(i -> {
                assertThat(i.get("bookmarked").asBoolean()).isTrue();
                ids.add(i.get("id").asText());
            });
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
        } while (cursor != null);
        return ids;
    }

    @Test
    void bookmarksAreCreatedListedPagedIsolatedAndCascadedOverHttp() throws Exception {
        var alice = register("alice3b");
        var bob = register("bob3b");
        var p1 = post(bob, "first");
        var p2 = post(bob, "second");
        var p3 = post(bob, "third");

        assertThat(call("GET", "/bookmarks", null, null).statusCode()).isEqualTo(401);
        ok(call("PUT", "/posts/" + UUID.randomUUID() + "/bookmark", alice, null), 404);

        for (var id : List.of(p1, p2, p3)) {
            ok(call("PUT", "/posts/" + id + "/bookmark", alice, null), 204);
            Thread.sleep(5); // distinct bookmark times
        }
        ok(call("PUT", "/posts/" + p1 + "/bookmark", alice, null), 204); // idempotent: keeps its position

        assertThat(bookmarkIds(alice, 20)).containsExactly(p3, p2, p1);
        assertThat(bookmarkIds(alice, 1)).containsExactly(p3, p2, p1);
        assertThat(bookmarkIds(bob, 20)).isEmpty(); // isolation

        // per-viewer flag in the feed and on a single post
        var feed = ok(call("GET", "/feed?size=100", alice, null), 200).get("items");
        feed.forEach(i -> {
            if (List.of(p1, p2, p3).contains(i.get("id").asText())) assertThat(i.get("bookmarked").asBoolean()).isTrue();
        });
        assertThat(ok(call("GET", "/posts/" + p2, alice, null), 200).get("bookmarked").asBoolean()).isTrue();
        assertThat(ok(call("GET", "/posts/" + p2, bob, null), 200).get("bookmarked").asBoolean()).isFalse();

        // bad paging input
        ok(call("GET", "/bookmarks?cursor=garbage", alice, null), 400);
        ok(call("GET", "/bookmarks?size=0", alice, null), 400);

        // bob unbookmarking does not touch alice's; alice's DELETE is idempotent
        ok(call("DELETE", "/posts/" + p2 + "/bookmark", bob, null), 204);
        assertThat(bookmarkIds(alice, 20)).containsExactly(p3, p2, p1);
        ok(call("DELETE", "/posts/" + p2 + "/bookmark", alice, null), 204);
        ok(call("DELETE", "/posts/" + p2 + "/bookmark", alice, null), 204);
        assertThat(bookmarkIds(alice, 20)).containsExactly(p3, p1);

        // deleting the post removes its bookmark and never breaks the list
        ok(call("DELETE", "/posts/" + p3, bob, null), 204);
        assertThat(bookmarkIds(alice, 20)).containsExactly(p1);
        ok(call("DELETE", "/posts/" + p3 + "/bookmark", alice, null), 204);
        ok(call("PUT", "/posts/" + p3 + "/bookmark", alice, null), 404);
    }
}
