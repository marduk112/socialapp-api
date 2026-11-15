package com.socialapp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.feed.application.media.MediaService;
import com.socialapp.feed.application.media.TestImages;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Stage 3C end to end through the real stack (Tomcat multipart limits, security, Feed, Postgres, outbox -> Kafka ->
 * SSE, local disk): upload -> create post with media -> feed / single post / SSE show the media -> the image URL serves
 * the bytes with the right headers -> delete removes rows and files; plus the rejections at the HTTP edge and the
 * cleanup of expired uploads. Same container/context lifecycle rules as {@link SocialAppIntegrationTest}.
 */
@Testcontainers(disabledWithoutDocker = true) // keep FIRST: its after-all (stop containers) must run last
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("socialapp_test").withUsername("test").withPassword("test");

    @Container
    static final Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5.18")
            .withAdminPassword("testpassword").withoutAuthentication();

    @Container
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    static final Path MEDIA_DIR;

    static {
        try {
            MEDIA_DIR = Files.createTempDirectory("media-it");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

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
        registry.add("app.media.dir", MEDIA_DIR::toString);
        registry.add("app.media.cleanup-enabled", () -> "false");
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(20);

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired MediaService mediaService;
    private final HttpClient http = HttpClient.newHttpClient();

    record User(UUID id, String username, String token) {}

    private String base() {
        return "http://localhost:" + port;
    }

    private HttpResponse<String> call(String method, String path, User as, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base() + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (as != null) builder.header("Authorization", "Bearer " + as.token());
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(User as, String filename, String contentType, byte[] bytes) throws Exception {
        String boundary = "----media" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(bytes);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var builder = HttpRequest.newBuilder(URI.create(base() + "/api/v1/media"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if (as != null) builder.header("Authorization", "Bearer " + as.token());
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

    private static Path fileOf(String url) {
        return MEDIA_DIR.resolve(url.substring(url.lastIndexOf('/') + 1));
    }

    private HttpResponse<byte[]> fetch(String url) throws Exception {
        String path = URI.create(url).getPath(); // URLs carry app.base-url; fetch from the test server
        return http.send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static List<String> ids(JsonNode media) {
        List<String> ids = new java.util.ArrayList<>();
        media.forEach(m -> ids.add(m.get("id").asText()));
        return ids;
    }

    /** The feed SSE stream: every (event, data) pair received. */
    final class FeedStream implements AutoCloseable {
        final ConcurrentLinkedQueue<String[]> events = new ConcurrentLinkedQueue<>();
        private final HttpClient client = HttpClient.newHttpClient();
        private final ExecutorService reader = Executors.newSingleThreadExecutor();
        private final CompletableFuture<HttpResponse<java.util.stream.Stream<String>>> response;

        FeedStream(User as) {
            response = client.sendAsync(HttpRequest.newBuilder(URI.create(
                    base() + "/api/v1/feed/stream?access_token=" + as.token())).GET().build(),
                    HttpResponse.BodyHandlers.ofLines());
            reader.submit(() -> {
                String[] event = new String[1];
                response.get().body().forEach(line -> {
                    if (line.startsWith("event:")) event[0] = line.substring(6);
                    else if (line.startsWith("data:")) events.add(new String[]{event[0], line.substring(5)});
                });
                return null;
            });
            await().atMost(Duration.ofSeconds(10)).until(() -> events.stream().anyMatch(e -> "connected".equals(e[0])));
        }

        JsonNode newPost(String postId) {
            for (var e : events) {
                if (!"new-post".equals(e[0])) continue;
                try {
                    var node = JSON.readTree(e[1]);
                    if (postId.equals(node.get("postId").asText())) return node;
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }
            return null;
        }

        @Override
        public void close() {
            reader.shutdownNow();
            response.cancel(true);
            client.shutdownNow();
        }
    }

    @Test
    void uploadAttachShowServeAndDeleteImagesOverHttp() throws Exception {
        var alice = register("alice3c");
        var bob = register("bob3c");

        // ── upload: auth and rejections at the HTTP edge ──
        assertThat(upload(null, "a.png", "image/png", TestImages.png(4, 4)).statusCode()).isEqualTo(401);
        ok(upload(alice, "fake.png", "image/png", "not an image".getBytes(StandardCharsets.UTF_8)), 415);
        ok(upload(alice, "x.svg", "image/svg+xml", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>"
                .getBytes(StandardCharsets.UTF_8)), 415);
        ok(upload(alice, "empty.png", "image/png", new byte[0]), 400);
        byte[] tooBig = new byte[5 * 1024 * 1024 + 100]; // over the multipart file limit: Tomcat/Spring refuse it
        System.arraycopy(TestImages.png(2, 2), 0, tooBig, 0, 8);
        ok(upload(alice, "big.png", "image/png", tooBig), 413);

        // ── upload: real images, type from the bytes ──
        var png = ok(upload(alice, "pic.png", "image/png", TestImages.png(40, 30)), 201);
        var jpg = ok(upload(alice, "pic.txt", "text/plain", TestImages.jpeg(16, 9)), 201); // lying header, real JPEG
        assertThat(png.get("url").asText()).endsWith(".png");
        assertThat(jpg.get("url").asText()).endsWith(".jpg");
        assertThat(png.get("width").asInt()).isEqualTo(40);
        assertThat(png.get("height").asInt()).isEqualTo(30);

        var served = fetch(png.get("url").asText());
        assertThat(served.statusCode()).isEqualTo(200);
        assertThat(served.headers().firstValue("Content-Type")).contains("image/png");
        assertThat(served.headers().allValues("X-Content-Type-Options")).containsExactly("nosniff");
        assertThat(served.headers().firstValue("Content-Security-Policy")).contains("default-src 'none'; sandbox");
        assertThat(served.body()).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');

        // ── attach: someone else's upload is not found; the owner attaches atomically, in order ──
        ok(call("POST", "/posts", bob, Map.of("content", "stolen", "mediaIds", List.of(png.get("id").asText()))), 404);

        try (var stream = new FeedStream(bob)) {
            var post = ok(call("POST", "/posts", alice, Map.of("content", "two pics",
                    "mediaIds", List.of(jpg.get("id").asText(), png.get("id").asText()))), 201);
            String postId = post.get("id").asText();
            assertThat(ids(post.get("media"))).containsExactly(jpg.get("id").asText(), png.get("id").asText());
            assertThat(post.get("media").get(1)).isEqualTo(png);

            ok(call("POST", "/posts", alice, Map.of("content", "again", "mediaIds", List.of(png.get("id").asText()))), 400);

            // single post and feed show the media
            assertThat(ids(ok(call("GET", "/posts/" + postId, bob, null), 200).get("media")))
                    .containsExactly(jpg.get("id").asText(), png.get("id").asText());
            JsonNode inFeed = null;
            for (var item : ok(call("GET", "/feed?size=50", bob, null), 200).get("items")) {
                if (postId.equals(item.get("id").asText())) inFeed = item;
            }
            assertThat(inFeed).isNotNull();
            assertThat(inFeed.get("media").get(0)).isEqualTo(jpg);

            // live: the SSE new-post payload carries the media
            await().atMost(WAIT).until(() -> stream.newPost(postId) != null);
            assertThat(ids(stream.newPost(postId).get("media")))
                    .containsExactly(jpg.get("id").asText(), png.get("id").asText());

            // a quote with its own image embeds the original with its images
            var bobs = ok(upload(bob, "b.gif", "image/gif", TestImages.gif(5, 5)), 201);
            var quote = ok(call("POST", "/posts/" + postId + "/quote", bob, Map.of("comment", "nice",
                    "mediaIds", List.of(bobs.get("id").asText()))), 201);
            assertThat(ids(quote.get("media"))).containsExactly(bobs.get("id").asText());
            assertThat(ids(quote.get("original").get("media"))).hasSize(2);
            await().atMost(WAIT).until(() -> stream.newPost(quote.get("id").asText()) != null);
            var live = stream.newPost(quote.get("id").asText());
            assertThat(ids(live.get("media"))).containsExactly(bobs.get("id").asText());
            assertThat(ids(live.get("original").get("media"))).hasSize(2);

            // edit leaves media alone (mediaIds in a PUT body is ignored)
            var edited = ok(call("PUT", "/posts/" + postId, alice, Map.of("content", "edited", "mediaIds", List.of())), 200);
            assertThat(ids(edited.get("media"))).hasSize(2);

            // ── delete: rows and files go, the quote keeps its own ──
            ok(call("DELETE", "/posts/" + postId, alice, null), 204);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM post_media WHERE post_id = CAST(? AS uuid)",
                    Long.class, postId)).isZero();
            assertThat(fileOf(png.get("url").asText())).doesNotExist();
            assertThat(fileOf(jpg.get("url").asText())).doesNotExist();
            assertThat(fetch(png.get("url").asText()).statusCode()).isEqualTo(404);
            var keptQuote = ok(call("GET", "/posts/" + quote.get("id").asText(), bob, null), 200);
            assertThat(keptQuote.get("original").isNull()).isTrue();
            assertThat(ids(keptQuote.get("media"))).containsExactly(bobs.get("id").asText());
            assertThat(fetch(bobs.get("url").asText()).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void expiredUnattachedUploadsArePurgedWithTheirFiles() throws Exception {
        var carol = register("carol3c");
        var old = ok(upload(carol, "o.png", "image/png", TestImages.png(3, 3)), 201);
        var fresh = ok(upload(carol, "f.png", "image/png", TestImages.png(3, 3)), 201);
        jdbc.update("UPDATE post_media SET created_at = now() - interval '2 days' WHERE id = CAST(? AS uuid)",
                old.get("id").asText());

        assertThat(mediaService.purgeExpired()).isEqualTo(1);

        assertThat(fileOf(old.get("url").asText())).doesNotExist();
        assertThat(fileOf(fresh.get("url").asText())).exists();
        ok(call("POST", "/posts", carol, Map.of("content", "late", "mediaIds", List.of(old.get("id").asText()))), 404);
        ok(call("POST", "/posts", carol, Map.of("content", "ok", "mediaIds", List.of(fresh.get("id").asText()))), 201);
    }
}
