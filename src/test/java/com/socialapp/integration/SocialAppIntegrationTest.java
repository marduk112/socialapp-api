package com.socialapp.integration;

import com.socialapp.config.KafkaConfig;
import com.socialapp.config.outbox.OutboxImporter;
import com.socialapp.feed.application.usecase.PostTextIndexBackfill;
import com.socialapp.socialgraph.infrastructure.backfill.FollowBackfillStore;
import com.socialapp.socialgraph.infrastructure.backfill.FollowProjectionBackfill;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.ReflectionTestUtils;
import com.socialapp.feed.application.timeline.FeedScope;
import com.socialapp.feed.application.timeline.TimelineService;
import com.socialapp.feed.application.usecase.AuthorProfileProjection;
import com.socialapp.feed.domain.FollowRepository;
import com.socialapp.shared.events.UserFollowedEvent;
import com.socialapp.socialgraph.infrastructure.outbox.SocialOutboxStore;
import com.socialapp.usermanagement.infrastructure.security.JwtService;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.neo4j.core.Neo4jClient;
import com.socialapp.feed.application.usecase.PostService;
import com.socialapp.feed.domain.AuthorProfileRepository;
import com.socialapp.feed.domain.CommentRepository;
import com.socialapp.feed.domain.Post;
import com.socialapp.feed.domain.PostReactionRepository;
import com.socialapp.feed.domain.PostRepository;
import com.socialapp.shared.domain.NotFoundException;
import com.socialapp.shared.events.UserCreatedEvent;
import com.socialapp.shared.events.UserUpdatedEvent;
import com.socialapp.socialgraph.domain.UserNodeRepository;
import com.socialapp.shared.events.ReactionEvent;
import com.socialapp.socialgraph.application.command.FollowUserCommand;
import com.socialapp.socialgraph.application.usecase.FollowSuggestion;
import com.socialapp.socialgraph.application.usecase.SocialGraphService;
import com.socialapp.socialgraph.domain.UserNode;
import com.socialapp.usermanagement.application.command.CreateUserCommand;
import com.socialapp.usermanagement.application.usecase.UserService;
import com.socialapp.usermanagement.domain.User;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Full integration test spinning up PostgreSQL, Neo4j and Kafka in parallel.
 * Testcontainers @Container fields are static so containers are shared
 * across all test methods (started once per class).
 *
 * <p>Shutdown order matters: the Spring context must be closed while the containers are still up, otherwise the
 * cached context (outbox relay, social outbox forwarder, Kafka listeners) keeps running against stopped databases
 * and logs connection errors (e.g. "Outbox relay run failed"). Hence {@code @DirtiesContext(AFTER_CLASS)} (the
 * SpringExtension closes the context in its after-all callback) and {@code @Testcontainers} declared BEFORE
 * {@code @SpringBootTest}: JUnit registers class-level extensions in declaration order and runs after-all callbacks
 * in reverse, so the context is closed first and the containers are stopped last. (Closing the context from an
 * {@code @AfterAll} method instead would leave a closed context in the cache, which the Spring test listeners then
 * reject.)
 */
@Testcontainers(disabledWithoutDocker = true) // keep FIRST: its after-all (stop containers) must run last
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SocialAppIntegrationTest {

    // ── Containers (started in parallel by Testcontainers) ───────────────────
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("socialapp_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5.18")
            .withAdminPassword("testpassword")
            .withoutAuthentication();

    @Container
    static final KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    // ── Dynamic properties wired to Spring context ───────────────────────────
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("spring.neo4j.uri", neo4j::getBoltUrl);
        registry.add("spring.neo4j.authentication.username", () -> "neo4j");
        registry.add("spring.neo4j.authentication.password", () -> "testpassword");

        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);

        // The tests only call localhost. Binding to loopback (instead of the wildcard address) also lets Tomcat's
        // graceful shutdown wake its acceptor via 127.0.0.1; with a wildcard bind it connects to a non-loopback
        // interface address, which sandboxed/firewalled machines block: 2 s wait + "Socket accept failed" ERROR.
        registry.add("server.address", () -> "127.0.0.1");
        registry.add("app.media.cleanup-enabled", () -> "false"); // scheduled media purge off in tests
    }

    @Autowired UserService userService;
    @Autowired SocialGraphService socialGraphService;
    @Autowired PostService postService;
    @Autowired UserNodeRepository userNodeRepository;
    @Autowired AuthorProfileProjection authorProfileProjection;
    @Autowired AuthorProfileRepository authorProfileRepository;
    @Autowired PostRepository postRepository;
    @Autowired PostReactionRepository postReactionRepository;
    @Autowired CommentRepository commentRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired TimelineService timelineService;
    @Autowired FollowRepository followRepository;
    @Autowired SocialOutboxStore socialOutboxStore;
    @Autowired OutboxImporter outboxImporter;
    @Autowired Neo4jClient neo4jClient;
    @Autowired JwtService jwtService;
    @Autowired FollowProjectionBackfill followProjectionBackfill;
    @Autowired FollowBackfillStore followBackfillStore;
    @Autowired PostTextIndexBackfill postTextIndexBackfill;
    @Autowired KafkaListenerEndpointRegistry kafkaListenerRegistry;
    @Autowired CommonErrorHandler kafkaErrorHandler;
    @Autowired @Qualifier("kafkaConsumerFactory") ConsumerFactory<?, ?> bootDefaultConsumerFactory;
    @Autowired @Qualifier("kafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<?, ?> bootDefaultListenerContainerFactory;
    @LocalServerPort int port;

    private static UUID aliceId;
    private static UUID bobId;

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @Order(0)
    void startupBackfillsRunOnceInTheBackgroundAndLeaveDurableMarkers() {
        // Started asynchronously at ApplicationReadyEvent; on fresh stores there is nothing to do.
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> {
            assertThat(followBackfillStore.marker(FollowProjectionBackfill.NAME).completed()).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT completed_at IS NOT NULL FROM backfill_state WHERE name = ?",
                    Boolean.class, PostTextIndexBackfill.NAME)).isTrue();
        });
        assertThat(followBackfillStore.marker(FollowProjectionBackfill.NAME).events()).isZero();
        assertThat(socialOutboxStore.countPending()).isZero();
        // Markers present: running again is a no-op
        assertThat(followProjectionBackfill.run(() -> false).alreadyDone()).isTrue();
        assertThat(postTextIndexBackfill.run(() -> false).alreadyDone()).isTrue();
    }

    @Test
    @Order(1)
    void shouldCreateUserAndSyncToNeo4j() {
        // When
        User alice = userService.handle(new CreateUserCommand("alice", "alice@test.com", "password123", null));
        aliceId = alice.getId();

        // Then — wait for async Kafka → Neo4j sync
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var node = socialGraphService.getFollowing(aliceId.toString());
            assertThat(node).isNotNull();
        });

        assertThat(alice.getUsername()).isEqualTo("alice");
        assertThat(alice.getEmail()).isEqualTo("alice@test.com");
    }

    @Test
    @Order(2)
    void shouldCreateSecondUser() {
        User bob = userService.handle(new CreateUserCommand("bob", "bob@test.com", "password123", null));
        bobId = bob.getId();
        assertThat(bob.getUsername()).isEqualTo("bob");
    }

    @Test
    @Order(3)
    void shouldFollowUser() {
        // Given — nodes are created asynchronously by the Kafka consumer (user-events).
        // When — follow() throws until both nodes have been synced, so retry until it succeeds.
        await().atMost(Duration.ofSeconds(15))
                .ignoreExceptions()
                .untilAsserted(() -> socialGraphService.follow(new FollowUserCommand(aliceId, bobId)));

        // Then
        List<UserNode> following = socialGraphService.getFollowing(aliceId.toString());
        assertThat(following).extracting(UserNode::getUserId)
                .contains(bobId.toString());
        assertThat(socialGraphService.isFollowing(aliceId.toString(), bobId.toString())).isTrue();
    }

    @Test
    @Order(4)
    void shouldCreatePostAndPublishEvent() throws Exception {
        // When
        Post post = postService.createPost(aliceId, "alice", "Hello, world!", null);

        // Then
        assertThat(post.getId()).isNotNull();
        assertThat(post.getLikesCount()).isZero();
        assertThat(post.getDislikesCount()).isZero();

        // Verify Kafka received the PostCreatedEvent
        try (var consumer = buildTestConsumer(kafka.getBootstrapServers(), "test-verify-group")) {
            consumer.subscribe(List.of("post-events"));
            var records = consumer.poll(Duration.ofSeconds(5));
            assertThat(records.count()).isGreaterThan(0);
        }
    }

    @Test
    @Order(5)
    void shouldLikeAndDislikePost() {
        Post post = postService.createPost(aliceId, "alice", "Like/dislike test", null);
        UUID postId = post.getId();

        // Like
        Post liked = postService.react(postId, bobId, ReactionEvent.ReactionType.LIKE, true);
        assertThat(liked.getLikesCount()).isEqualTo(1);
        assertThat(liked.getDislikesCount()).isZero();

        // Dislike same post — should switch reaction
        Post disliked = postService.react(postId, bobId, ReactionEvent.ReactionType.DISLIKE, true);
        assertThat(disliked.getLikesCount()).isZero();
        assertThat(disliked.getDislikesCount()).isEqualTo(1);

        // Remove dislike
        Post clean = postService.react(postId, bobId, ReactionEvent.ReactionType.DISLIKE, false);
        assertThat(clean.getDislikesCount()).isZero();
    }

    @Test
    @Order(6)
    void followIsIdempotentAndRaceSafe_unfollowIsIdempotent_selfFollowRejected() throws Exception {
        // alice already follows bob (order 3): repeat, also concurrently
        socialGraphService.follow(new FollowUserCommand(aliceId, bobId));
        runConcurrently(8, i -> () -> { socialGraphService.follow(new FollowUserCommand(aliceId, bobId)); return null; });

        assertThat(socialGraphService.getFollowing(aliceId.toString()))
                .extracting(UserNode::getUserId).containsOnlyOnce(bobId.toString());
        assertThat(socialGraphService.getFollowers(bobId.toString()))
                .extracting(UserNode::getUserId).containsOnlyOnce(aliceId.toString());

        socialGraphService.unfollow(new FollowUserCommand(aliceId, bobId));
        socialGraphService.unfollow(new FollowUserCommand(aliceId, bobId));
        assertThat(socialGraphService.isFollowing(aliceId.toString(), bobId.toString())).isFalse();

        assertThatThrownBy(() -> socialGraphService.follow(new FollowUserCommand(aliceId, aliceId)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> socialGraphService.follow(new FollowUserCommand(aliceId, UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);

        socialGraphService.follow(new FollowUserCommand(aliceId, bobId)); // restore
        assertThat(socialGraphService.isFollowing(aliceId.toString(), bobId.toString())).isTrue();
    }

    @Test
    @Order(7)
    void concurrentReactionsLoseNoUpdatesAndDuplicatesAreIdempotent() throws Exception {
        // Many users like the same post at once -> no lost counter updates
        UUID postId = postService.createPost(aliceId, "alice", "Concurrency", null).getId();
        int users = 10;
        runConcurrently(users, i -> () -> postService.react(postId, UUID.randomUUID(), ReactionEvent.ReactionType.LIKE, true));
        assertThat(postRepository.findById(postId).orElseThrow().getLikesCount()).isEqualTo(users);

        // One user "double-clicks" many times at once -> exactly one reaction, no 500 / constraint error
        UUID post2 = postService.createPost(aliceId, "alice", "Double click", null).getId();
        runConcurrently(8, i -> () -> postService.react(post2, bobId, ReactionEvent.ReactionType.LIKE, true));
        assertThat(postRepository.findById(post2).orElseThrow().getLikesCount()).isEqualTo(1);
        assertThat(postReactionRepository.findByPostIdAndUserId(post2, bobId)).hasSize(1);

        // Comment reactions behave the same
        var comment = postService.addComment(post2, aliceId, "alice", "c", null);
        runConcurrently(8, i -> () -> postService.reactToComment(comment.getId(), bobId, ReactionEvent.ReactionType.DISLIKE, true));
        assertThat(commentRepository.findById(comment.getId()).orElseThrow().getDislikesCount()).isEqualTo(1);
    }

    @Test
    @Order(8)
    void replyMustReferenceAnExistingCommentOfTheSamePost() {
        UUID p1 = postService.createPost(aliceId, "alice", "p1", null).getId();
        UUID p2 = postService.createPost(aliceId, "alice", "p2", null).getId();
        var commentOnP1 = postService.addComment(p1, bobId, "bob", "top", null);

        assertThatThrownBy(() -> postService.addComment(p2, bobId, "bob", "reply", commentOnP1.getId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> postService.addComment(p1, bobId, "bob", "reply", UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);

        var reply = postService.addComment(p1, bobId, "bob", "reply", commentOnP1.getId());
        assertThat(reply.getParentCommentId()).isEqualTo(commentOnP1.getId());
        assertThat(postRepository.findById(p1).orElseThrow().getCommentsCount()).isEqualTo(1);
    }

    @Test
    @Order(9)
    void socialGraphUserProjectionIsOrderTolerant() {
        UUID id = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-01-01T10:00:00Z");

        // Update arrives before create -> upsert instead of being dropped
        socialGraphService.syncUserUpdated(new UserUpdatedEvent(UUID.randomUUID(), t1.plusSeconds(10), id, "carol2", "c2.png", null));
        // Late create must not overwrite; stale update must be ignored
        socialGraphService.syncUserCreated(new UserCreatedEvent(UUID.randomUUID(), t1, id, "carol", "carol@test.com", null));
        socialGraphService.syncUserUpdated(new UserUpdatedEvent(UUID.randomUUID(), t1.plusSeconds(5), id, "carol-stale", null, null));

        var node = userNodeRepository.findByUserId(id.toString()).orElseThrow();
        assertThat(node.getUsername()).isEqualTo("carol2");
        assertThat(node.getAvatarUrl()).isEqualTo("c2.png");

        // Newer update applies; a profile update does not touch FOLLOWS relationships
        socialGraphService.syncUserUpdated(new UserUpdatedEvent(UUID.randomUUID(), Instant.now(), bobId, "bob", "bob.png", null));
        assertThat(userNodeRepository.findByUserId(bobId.toString()).orElseThrow().getAvatarUrl()).isEqualTo("bob.png");
        assertThat(socialGraphService.isFollowing(aliceId.toString(), bobId.toString())).isTrue();
    }

    @Test
    @Order(10)
    void feedProjectionTracksUsernameAndRefreshesDenormalisedCopies() {
        // Populated from Kafka user-events (order 1)
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(authorProfileRepository.findById(aliceId)).hasValueSatisfying(p ->
                        assertThat(p.getUsername()).isEqualTo("alice")));

        UUID dave = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-01-01T10:00:00Z");
        authorProfileProjection.on(new UserCreatedEvent(UUID.randomUUID(), t1, dave, "dave", "dave@test.com", null));
        Post original = postService.createPost(dave, "dave", "dave's post", null);
        Post share = postService.quote(original.getId(), bobId, "bob", "look");
        var comment = postService.addComment(original.getId(), dave, "dave", "dave's comment", null);

        authorProfileProjection.on(new UserUpdatedEvent(UUID.randomUUID(), t1.plusSeconds(60), dave, "dave2", "d.png", null));
        // Late/redelivered create does not revert the rename
        authorProfileProjection.on(new UserCreatedEvent(UUID.randomUUID(), t1, dave, "dave", "dave@test.com", null));

        assertThat(postRepository.findById(original.getId()).orElseThrow().getAuthorUsername()).isEqualTo("dave2");
        assertThat(postRepository.findById(share.getId()).orElseThrow().getOriginalAuthorUsername()).isEqualTo("dave2");
        assertThat(postRepository.findById(share.getId()).orElseThrow().getAuthorUsername()).isEqualTo("bob");
        assertThat(commentRepository.findById(comment.getId()).orElseThrow().getAuthorUsername()).isEqualTo("dave2");
        var profile = authorProfileRepository.findById(dave).orElseThrow();
        assertThat(profile.getUsername()).isEqualTo("dave2");
        assertThat(profile.getAvatarUrl()).isEqualTo("d.png");
        // New content uses the projected name even if the caller's token still says "dave"
        assertThat(postService.createPost(dave, "dave", "after rename", null).getAuthorUsername()).isEqualTo("dave2");
    }

    @Test
    @Order(11)
    void poisonMessageGoesToDeadLetterTopicAndConsumersKeepGoing() {
        try (var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>("user-events", "poison-key", "{not valid json")).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        List<ConsumerRecord<String, String>> dead = new ArrayList<>();
        try (var consumer = buildTestConsumer(kafka.getBootstrapServers(), "test-dlt-group")) {
            consumer.subscribe(List.of("user-events.DLT"));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(dead::add);
                return !dead.isEmpty();
            });
        }
        var record = dead.get(0);
        assertThat(record.key()).isEqualTo("poison-key");
        assertThat(record.value()).isEqualTo("{not valid json"); // raw bytes forwarded unchanged
        assertThat(new String(record.headers().lastHeader("kafka_dlt-exception-fqcn").value(), StandardCharsets.UTF_8))
                .contains("DeserializationException");

        // The listener did not get stuck on the poison pill: a later event is still projected
        User erin = userService.handle(new CreateUserCommand("erin", "erin@test.com", "password123", null));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(userNodeRepository.existsById(erin.getId().toString())).isTrue());
    }

    @Test
    @Order(12)
    void outboxRowIsAtomicWithTheAggregate_andIsRelayedToKafkaAfterCommit() throws Exception {
        // 1) Rolled back AFTER the outbox row was written (a later before-commit hook fails):
        //    neither the post nor its outbox row survive.
        UUID[] rolledBack = new UUID[1];
        long[] rowsInsideTx = new long[1];
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            rolledBack[0] = postService.createPost(aliceId, "alice", "rolled back", null).getId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) { // ordered after the outbox writer
                    rowsInsideTx[0] = outboxRows(rolledBack[0]);
                    throw new IllegalStateException("boom after outbox write");
                }
            });
        })).hasMessageContaining("boom");
        assertThat(rowsInsideTx[0]).isEqualTo(1);
        assertThat(postRepository.findById(rolledBack[0])).isEmpty();
        assertThat(outboxRows(rolledBack[0])).isZero();

        // 2) Committed: the row is relayed (published_at set) and the message is on Kafka with the same
        //    topic, key and JSON as stored.
        UUID committed = postService.createPost(aliceId, "alice", "committed", null).getId();
        assertThat(outboxRows(committed)).isEqualTo(1);
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbcTemplate.queryForObject(
                "SELECT published_at IS NOT NULL FROM outbox_events WHERE message_key = ?", Boolean.class,
                committed.toString()));

        List<ConsumerRecord<String, String>> seen = new ArrayList<>();
        try (var consumer = buildTestConsumer(kafka.getBootstrapServers(), "test-outbox-" + UUID.randomUUID())) {
            consumer.subscribe(List.of("post-events"));
            await().atMost(Duration.ofSeconds(15)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(seen::add);
                return seen.stream().anyMatch(r -> committed.toString().equals(r.key()));
            });
        }
        assertThat(seen).extracting(ConsumerRecord::key).doesNotContain(rolledBack[0].toString());
        var record = seen.stream().filter(r -> committed.toString().equals(r.key())).findFirst().orElseThrow();
        String stored = jdbcTemplate.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE message_key = ?", String.class, committed.toString());
        var json = new ObjectMapper();
        assertThat(json.readTree(record.value())).isEqualTo(json.readTree(stored));
        assertThat(json.readTree(record.value()).get("eventType").asText()).isEqualTo("PostCreatedEvent");
    }

    // ── Stage 2A: follow -> social-events -> Feed follows projection -> following timeline ─────────

    private long socialOutboxRows(UUID follower) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE topic = 'social-events' AND message_key = ?",
                Long.class, follower.toString());
    }

    private List<UUID> followingTimeline(UUID viewer) {
        return timelineService.page(viewer, FeedScope.FOLLOWING, null, 100).posts().stream()
                .map(Post::getId).toList();
    }

    @Test
    @Order(13)
    void followFlowsThroughOutboxAndKafkaIntoTheFollowingTimeline() throws Exception {
        // alice -> bob was followed (order 3), unfollowed and re-followed (order 6); the 9 repeated/concurrent
        // follows and the second unfollow of order 6 changed nothing and must have emitted nothing.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(followRepository.findActiveFolloweeIds(aliceId)).containsExactly(bobId));
        assertThat(socialOutboxRows(aliceId)).isEqualTo(3);
        await().atMost(Duration.ofSeconds(10)).until(() -> socialOutboxStore.countPending() == 0);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events WHERE topic = 'social-events' "
                + "AND published_at IS NULL", Long.class)).isZero();

        User frank = userService.handle(new CreateUserCommand("frank", "frank@test.com", "password123", null));
        UUID bobsPost = postService.createPost(bobId, "bob", "bob for his followers", null).getId();
        UUID franksPost = postService.createPost(frank.getId(), "frank", "frank, followed by nobody", null).getId();
        UUID alicesPost = postService.createPost(aliceId, "alice", "alice's own", null).getId();

        assertThat(followingTimeline(aliceId)).contains(bobsPost, alicesPost).doesNotContain(franksPost);
        assertThat(timelineService.page(aliceId, FeedScope.ALL, null, 100).posts())
                .extracting(Post::getId).contains(bobsPost, franksPost, alicesPost);

        // Real HTTP contract: envelope, opaque cursor, last page null.
        var http = java.net.http.HttpClient.newHttpClient();
        String token = jwtService.issueToken(aliceId, "alice");
        var json = new ObjectMapper();
        List<String> ids = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String url = "http://localhost:" + port + "/api/v1/feed?scope=following&size=2"
                    + (cursor == null ? "" : "&cursor=" + cursor);
            var response = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                            .header("Authorization", "Bearer " + token).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var body = json.readTree(response.body());
            assertThat(body.has("nextCursor")).isTrue();
            body.get("items").forEach(item -> ids.add(item.get("id").asText()));
            cursor = body.get("nextCursor").isNull() ? null : body.get("nextCursor").asText();
            pages++;
        } while (cursor != null && pages < 50);
        assertThat(ids).doesNotHaveDuplicates()
                .containsExactlyElementsOf(followingTimeline(aliceId).stream().map(UUID::toString).toList());
        assertThat(pages).isGreaterThan(1);

        var bad = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                        "http://localhost:" + port + "/api/v1/feed?cursor=%21%21%21garbage"))
                        .header("Authorization", "Bearer " + token).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json.readTree(bad.body()).get("error").asText()).isNotBlank();
    }

    @Test
    @Order(14)
    void unfollowRemovesPostsFromTheFollowingTimeline_concurrentRequestsEmitExactlyOneEvent() throws Exception {
        UUID bobsPost = postService.createPost(bobId, "bob", "soon hidden", null).getId();
        await().atMost(Duration.ofSeconds(10)).until(() -> followingTimeline(aliceId).contains(bobsPost));
        long before = socialOutboxRows(aliceId);

        runConcurrently(8, i -> () -> socialGraphService.unfollow(new FollowUserCommand(aliceId, bobId)));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(followingTimeline(aliceId)).doesNotContain(bobsPost));
        assertThat(socialOutboxRows(aliceId)).isEqualTo(before + 1);
        assertThat(followRepository.findActiveFolloweeIds(aliceId)).isEmpty();

        // Concurrent follows: one relationship, one event
        runConcurrently(8, i -> () -> socialGraphService.follow(new FollowUserCommand(aliceId, bobId)));
        await().atMost(Duration.ofSeconds(20)).until(() -> followingTimeline(aliceId).contains(bobsPost));
        assertThat(socialOutboxRows(aliceId)).isEqualTo(before + 2);
        assertThat(socialGraphService.getFollowing(aliceId.toString()))
                .extracting(UserNode::getUserId).containsOnlyOnce(bobId.toString());
    }

    @Test
    @Order(15)
    void followIsNotLostWhenKafkaIsDownAtFirst() {
        var docker = kafka.getDockerClient();
        socialGraphService.unfollow(new FollowUserCommand(aliceId, bobId));
        await().atMost(Duration.ofSeconds(20)).until(() -> followRepository.findActiveFolloweeIds(aliceId).isEmpty());
        long before = socialOutboxRows(aliceId);

        docker.pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            // The graph change commits regardless of Kafka; the event waits in the Postgres outbox.
            assertThat(socialGraphService.follow(new FollowUserCommand(aliceId, bobId))).isTrue();
            await().atMost(Duration.ofSeconds(30)).until(() -> jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM outbox_events
                    WHERE topic = 'social-events' AND message_key = ? AND published_at IS NULL AND attempts >= 1""",
                    Long.class, aliceId.toString()) == 1);
            assertThat(followRepository.findActiveFolloweeIds(aliceId)).isEmpty();
        } finally {
            docker.unpauseContainerCmd(kafka.getContainerId()).exec();
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(followRepository.findActiveFolloweeIds(aliceId)).containsExactly(bobId));
        assertThat(socialOutboxRows(aliceId)).isEqualTo(before + 1); // retried, not duplicated
    }

    @Test
    @Order(16)
    void crashBetweenTheStoresNeverLosesOrDuplicatesAFollowEvent() {
        UUID erinId = UUID.fromString(neo4jClient.query("MATCH (u:User {username: 'erin'}) RETURN u.userId")
                .fetchAs(String.class).one().orElseThrow());
        long now = System.currentTimeMillis();

        // (a) Crash after the Neo4j commit, before forwarding: relationship + pending event exist only in Neo4j.
        var lost = new UserFollowedEvent(UUID.randomUUID(), Instant.ofEpochMilli(now), erinId, bobId);
        // (b) Crash after the Postgres import, before the Neo4j delete: event in both stores.
        var duplicated = new UserFollowedEvent(UUID.randomUUID(), Instant.ofEpochMilli(now + 1), erinId, aliceId);
        assertThat(outboxImporter.importBatch(List.of(duplicated))).isTrue();

        for (var e : List.of(lost, duplicated)) {
            neo4jClient.query("""
                    MATCH (a:User {userId: $f}), (b:User {userId: $t})
                    MERGE (a)-[:FOLLOWS]->(b)
                    CREATE (:SocialOutbox {eventId: $id, type: 'UserFollowedEvent', followerId: $f, followeeId: $t,
                                           occurredAt: $at})""")
                    .bindAll(Map.of("f", e.followerId().toString(), "t", e.followeeId().toString(),
                            "id", e.eventId().toString(), "at", e.occurredAt().toEpochMilli()))
                    .run();
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(followRepository.findActiveFolloweeIds(erinId)).containsExactlyInAnyOrder(bobId, aliceId);
            assertThat(socialOutboxStore.countPending()).isZero();
        });
        for (var e : List.of(lost, duplicated)) {
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events WHERE event_id = ?",
                    Long.class, e.eventId())).isEqualTo(1);
        }
    }

    @Test
    @Order(17)
    void hashtagsAndMentionsOverHttp_hashtagTimeline_andLiveSsePayload() throws Exception {
        await().atMost(Duration.ofSeconds(15)).until(() -> authorProfileRepository.findById(bobId)
                .map(p -> "bob".equals(p.getUsername())).orElse(false));
        var http = java.net.http.HttpClient.newHttpClient();
        String token = jwtService.issueToken(aliceId, "alice");
        var json = new ObjectMapper();
        String base = "http://localhost:" + port + "/api/v1";

        // Live stream first, so the new post's SSE payload can be checked end to end (outbox -> Kafka -> SSE).
        var sseData = new java.util.concurrent.LinkedBlockingQueue<String>();
        var stream = http.sendAsync(java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                        base + "/feed/stream?access_token=" + token)).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofLines());
        var reader = Executors.newSingleThreadExecutor();
        reader.submit(() -> {
            stream.get().body().filter(l -> l.startsWith("data:")).forEach(l -> sseData.add(l.substring(5)));
            return null;
        });
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> sseData.contains("ok")); // "connected"

            var created = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/posts"))
                            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                                    "content", "Hi #Integration2B #integration2b @BOB @nobody_here mail a@b.com"))))
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(created.statusCode()).isEqualTo(201);
            var post = json.readTree(created.body());
            String postId = post.get("id").asText();
            assertThat(post.get("hashtags").toString()).isEqualTo("[\"integration2b\"]");
            assertThat(post.get("mentions").toString())
                    .isEqualTo("[{\"userId\":\"" + bobId + "\",\"username\":\"bob\"}]");

            await().atMost(Duration.ofSeconds(15)).until(() -> sseData.stream().anyMatch(d -> d.contains(postId)));
            var live = json.readTree(sseData.stream().filter(d -> d.contains(postId)).findFirst().orElseThrow());
            assertThat(live.get("hashtags")).isEqualTo(post.get("hashtags"));
            assertThat(live.get("mentions")).isEqualTo(post.get("mentions"));

            var byTag = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                                    base + "/hashtags/%23INTEGRATION2B/posts?size=5"))
                            .header("Authorization", "Bearer " + token).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(byTag.statusCode()).isEqualTo(200);
            var page = json.readTree(byTag.body());
            assertThat(page.get("items").get(0).get("id").asText()).isEqualTo(postId);
            assertThat(page.get("nextCursor").isNull()).isTrue();

            var tooLong = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/posts/" + postId))
                            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                            .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(
                                    json.writeValueAsString(Map.of("content", "x".repeat(501))))).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(tooLong.statusCode()).isEqualTo(400);
            assertThat(json.readTree(tooLong.body()).get("error").asText()).contains("500");
        } finally {
            reader.shutdownNow();
            stream.cancel(true);
            http.shutdownNow(); // really disconnect the SSE stream (cancel() alone leaves the server request open)
        }
    }

    @Test
    @Order(18)
    void repostFlowsThroughOutboxAndKafkaToTheFollowingSseStream_withTheNestedOriginal() throws Exception {
        // alice follows bob (re-followed in order 15); gina is followed by nobody
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(followRepository.findActiveFolloweeIds(aliceId)).contains(bobId));
        UUID gina = UUID.randomUUID();
        Post ginasPost = postService.createPost(gina, "gina", "gina's #stage2c post", null);

        var http = java.net.http.HttpClient.newHttpClient();
        String aliceToken = jwtService.issueToken(aliceId, "alice");
        String bobToken = jwtService.issueToken(bobId, "bob");
        var json = new ObjectMapper();
        String base = "http://localhost:" + port + "/api/v1";
        java.util.function.BiFunction<String, String, java.net.http.HttpRequest.Builder> req = (path, token) ->
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + path))
                        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");

        var sseData = new java.util.concurrent.LinkedBlockingQueue<String>();
        var stream = http.sendAsync(java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                        base + "/feed/stream?scope=following&access_token=" + aliceToken)).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofLines());
        var reader = Executors.newSingleThreadExecutor();
        reader.submit(() -> {
            stream.get().body().filter(l -> l.startsWith("data:")).forEach(l -> sseData.add(l.substring(5)));
            return null;
        });
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> sseData.contains("ok"));

            var created = http.send(req.apply("/posts/" + ginasPost.getId() + "/repost", bobToken)
                            .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(created.statusCode()).isEqualTo(201);
            var repost = json.readTree(created.body());
            String repostId = repost.get("id").asText();
            assertThat(repost.get("type").asText()).isEqualTo("REPOST");
            assertThat(repost.get("original").get("id").asText()).isEqualTo(ginasPost.getId().toString());
            assertThat(repost.get("original").get("viewerReposted").asBoolean()).isTrue();
            assertThat(repost.get("original").get("repostsCount").asLong()).isEqualTo(1);
            assertThat(outboxRows(UUID.fromString(repostId))).isEqualTo(1);

            var again = http.send(req.apply("/posts/" + ginasPost.getId() + "/repost", bobToken)
                            .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(again.statusCode()).isEqualTo(200);
            assertThat(json.readTree(again.body()).get("id").asText()).isEqualTo(repostId);
            assertThat(outboxRows(UUID.fromString(repostId))).isEqualTo(1); // no second event

            // live: bob is followed by alice, so his repost reaches her following stream with the nested original
            await().atMost(Duration.ofSeconds(15)).until(() -> sseData.stream().anyMatch(d -> d.contains(repostId)));
            var live = json.readTree(sseData.stream().filter(d -> d.contains(repostId)).findFirst().orElseThrow());
            assertThat(live.get("postId").asText()).isEqualTo(repostId);
            assertThat(live.get("type").asText()).isEqualTo("REPOST");
            assertThat(live.get("authorId").asText()).isEqualTo(bobId.toString());
            assertThat(live.get("content").asText()).isEmpty();
            assertThat(live.get("originalAuthorId").asText()).isEqualTo(gina.toString());
            assertThat(live.get("original").get("id").asText()).isEqualTo(ginasPost.getId().toString());
            assertThat(live.get("original").get("hashtags").toString()).isEqualTo("[\"stage2c\"]");
            assertThat(live.get("original").get("viewerReposted").asBoolean()).isFalse();
            assertThat(live.get("original").get("original").isNull()).isTrue();
            // gina's own post was never admitted to alice's following stream
            assertThat(sseData).noneMatch(d -> d.contains("\"postId\":\"" + ginasPost.getId()));

            // REST: the repost is in alice's following timeline, ordered by its own createdAt (newest)
            var page = json.readTree(http.send(req.apply("/feed?scope=following&size=5", aliceToken).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()).body());
            assertThat(page.get("items").get(0).get("id").asText()).isEqualTo(repostId);
            assertThat(page.get("items").get(0).get("original").get("id").asText())
                    .isEqualTo(ginasPost.getId().toString());

            // quote by alice, then deleting gina's post: the repost disappears, the quote stays without original
            var quote = http.send(req.apply("/posts/" + repostId + "/quote", aliceToken)
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"comment\":\"so #true\"}")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(quote.statusCode()).isEqualTo(201);
            var quoteBody = json.readTree(quote.body());
            assertThat(quoteBody.get("originalPostId").asText()).isEqualTo(ginasPost.getId().toString()); // flattened
            assertThat(quoteBody.get("hashtags").toString()).isEqualTo("[\"true\"]");

            var undo = http.send(req.apply("/posts/" + repostId + "/repost", bobToken).DELETE().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(undo.statusCode()).isEqualTo(204);
            assertThat(postRepository.findById(UUID.fromString(repostId))).isEmpty();

            postService.repost(ginasPost.getId(), bobId, "bob");
            postService.deletePost(ginasPost.getId(), gina);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM posts WHERE original_post_id IS NULL "
                    + "AND type = 'REPOST'", Long.class)).isZero();
            var afterDelete = json.readTree(http.send(req.apply("/posts/" + quoteBody.get("id").asText(), aliceToken)
                    .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString()).body());
            assertThat(afterDelete.get("type").asText()).isEqualTo("QUOTE");
            assertThat(afterDelete.get("original").isNull()).isTrue();
            assertThat(afterDelete.get("originalPostId").isNull()).isTrue();
        } finally {
            reader.shutdownNow();
            stream.cancel(true);
            http.shutdownNow(); // really disconnect the SSE stream (cancel() alone leaves the server request open)
        }
    }

    // ── Validation messages: deterministic English ─────────────────────────────────────────────────────

    @Test
    @Order(19)
    void validationErrorsAreEnglishForAPolishClientOnAPolishJvm() throws Exception {
        var http = java.net.http.HttpClient.newHttpClient();
        String token = jwtService.issueToken(aliceId, "alice");
        var json = new ObjectMapper();
        var previous = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("pl-PL")); // as with -Duser.language=pl
        try {
            var response = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                                    "http://localhost:" + port + "/api/v1/posts"))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .header("Accept-Language", "pl-PL,pl;q=0.9")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"content\":\"   \"}")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json.readTree(response.body()).get("error").asText())
                    .isEqualTo("Validation failed: content must not be blank");

            var register = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                                    "http://localhost:" + port + "/api/v1/users"))
                            .header("Content-Type", "application/json")
                            .header("Accept-Language", "pl")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                    "{\"username\":\"ab\",\"email\":\"x@y.io\",\"password\":\"password123\"}")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(register.statusCode()).isEqualTo(400);
            assertThat(json.readTree(register.body()).get("error").asText())
                    .isEqualTo("Validation failed: username size must be between 3 and 30");
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    // ── Kafka: Spring Boot's default consumer is safe and unused ──────────────────────────────────────

    @Test
    @Order(20)
    void bootsDefaultKafkaConsumerIsSafe_andNoListenerUsesIt() {
        var config = bootDefaultConsumerFactory.getConfigurationProperties();
        assertThat(config.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG))
                .isIn(ErrorHandlingDeserializer.class, ErrorHandlingDeserializer.class.getName());
        assertThat(config.get(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS))
                .isIn(JsonDeserializer.class, JsonDeserializer.class.getName());
        assertThat(config.get(JsonDeserializer.VALUE_DEFAULT_TYPE))
                .isEqualTo("com.socialapp.shared.events.DomainEvent");
        // the default container factory dead-letters too (Boot wires the unique CommonErrorHandler bean)
        assertThat(ReflectionTestUtils.getField(bootDefaultListenerContainerFactory, "commonErrorHandler"))
                .isSameAs(kafkaErrorHandler);

        var containers = kafkaListenerRegistry.getListenerContainers();
        assertThat(containers).extracting(c -> c.getGroupId()).containsExactlyInAnyOrder(
                "social-graph-group", "feed-avatar-group", "feed-sse-group", "feed-follows-group",
                "notification-users-group", "notification-social-group", "notification-posts-group",
                "notification-reactions-group", "notification-comments-group");
        for (var container : containers) {
            var factory = (ConsumerFactory<?, ?>) ReflectionTestUtils.getField(container, "consumerFactory");
            assertThat(factory).isNotSameAs(bootDefaultConsumerFactory);
            assertThat(factory.getConfigurationProperties().get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG))
                    .isEqualTo(ErrorHandlingDeserializer.class);
        }
    }

    // ── Kafka: a record that keeps failing IN the listener (not a poison pill) ─────────────────────────

    /** Collects log lines of one logger (thread-safe: listener threads append). */
    private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {
        final ConcurrentLinkedQueue<String> lines = new ConcurrentLinkedQueue<>();

        @Override
        protected void append(ILoggingEvent event) {
            lines.add(event.getFormattedMessage());
        }

        long count(String line) {
            return lines.stream().filter(line::equals).count();
        }
    }

    @Test
    @Order(21)
    void recordFailingInTheListenerIsRetriedBoundedThenDeadLettered_andLaterRecordsAreStillProcessed() throws Exception {
        // Valid JSON of a known event type, so it deserializes fine, but without userId: both user-events listeners
        // throw while handling it (SocialGraph: NPE in syncUserCreated; Feed: null id in findById).
        String key = "failing-" + UUID.randomUUID();
        UUID failingEventId = UUID.randomUUID();
        String failing = "{\"eventType\":\"UserCreatedEvent\",\"eventId\":\"" + failingEventId
                + "\",\"occurredAt\":\"2026-11-14T10:00:00Z\",\"username\":\"nobody\",\"email\":\"n@x.io\"}";
        // The next record on the same key (same partition, i.e. queued BEHIND the failing one): a valid create.
        UUID henry = UUID.randomUUID();
        String valid = "{\"eventType\":\"UserCreatedEvent\",\"eventId\":\"" + UUID.randomUUID()
                + "\",\"occurredAt\":\"2026-11-14T10:00:01Z\",\"userId\":\"" + henry
                + "\",\"username\":\"henry\",\"email\":\"h@x.io\",\"avatarUrl\":null}";

        var graphLog = new CapturingAppender();
        var graphLogger = (Logger) LoggerFactory.getLogger(
                com.socialapp.socialgraph.infrastructure.messaging.UserEventConsumer.class);
        graphLog.start();
        graphLogger.addAppender(graphLog);
        List<ConsumerRecord<String, String>> dead = new ArrayList<>();
        try {
            int sourcePartition;
            try (var producer = new KafkaProducer<String, String>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
                sourcePartition = producer.send(new ProducerRecord<>("user-events", key, failing)).get().partition();
                assertThat(producer.send(new ProducerRecord<>("user-events", key, valid)).get().partition())
                        .isEqualTo(sourcePartition);
            }

            // Later record (same partition) still processed by both groups: the listeners moved on.
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(userNodeRepository.existsById(henry.toString())).isTrue();
                assertThat(authorProfileRepository.findById(henry)).isPresent();
            });

            try (var consumer = buildTestConsumer(kafka.getBootstrapServers(), "test-dlt-" + UUID.randomUUID())) {
                consumer.subscribe(List.of("user-events" + KafkaConfig.DLT_SUFFIX));
                await().atMost(Duration.ofSeconds(30)).until(() -> {
                    consumer.poll(Duration.ofMillis(500)).forEach(r -> { if (key.equals(r.key())) dead.add(r); });
                    return dead.size() >= 2;
                });
            }

            // One dead letter per consumer group, in the right DLT and partition, with the exception headers.
            assertThat(dead).extracting(r -> header(r, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP))
                    .containsExactlyInAnyOrder("social-graph-group", "feed-avatar-group");
            for (var record : dead) {
                assertThat(record.topic()).isEqualTo("user-events.DLT");
                assertThat(record.partition()).isEqualTo(sourcePartition);
                assertThat(header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo("user-events");
                assertThat(header(record, KafkaHeaders.DLT_EXCEPTION_FQCN)).contains("ListenerExecutionFailedException");
                assertThat(header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).isNotBlank();
                assertThat(header(record, KafkaHeaders.DLT_EXCEPTION_STACKTRACE)).isNotBlank();
                // the event deserialized fine: re-serialized as JSON, same event
                assertThat(new ObjectMapper().readTree(record.value()).get("eventId").asText())
                        .isEqualTo(failingEventId.toString());
            }
            var graphDead = dead.stream().filter(r -> "social-graph-group".equals(
                    header(r, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP))).findFirst().orElseThrow();
            assertThat(header(graphDead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)).isEqualTo("java.lang.NullPointerException");

            // Bounded retries: 1 delivery + MAX_RETRIES (2) = 3 attempts in the SocialGraph listener, then no more.
            String attempt = "Received UserCreatedEvent for userId=null";
            assertThat(graphLog.count(attempt)).isEqualTo(3);
            Thread.sleep(2_500); // longer than one retry interval: a 4th attempt would show up
            assertThat(graphLog.count(attempt)).isEqualTo(3);
        } finally {
            graphLogger.detachAppender(graphLog);
            graphLog.stop();
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    // ── Follow backfill (follows created in Neo4j before stage 2A) ─────────────────────────────────────

    private Map<String, java.util.Set<String>> graphFollows(List<String> followers) {
        Map<String, java.util.Set<String>> result = new java.util.TreeMap<>();
        neo4jClient.query("""
                        MATCH (a:User)-[:FOLLOWS]->(b:User) WHERE a.userId IN $ids
                        RETURN a.userId AS f, b.userId AS t""")
                .bind(followers).to("ids")
                .fetch().all()
                .forEach(r -> result.computeIfAbsent((String) r.get("f"), k -> new java.util.TreeSet<>())
                        .add((String) r.get("t")));
        return result;
    }

    private Map<String, java.util.Set<String>> projectedFollows(List<String> followers) {
        Map<String, java.util.Set<String>> result = new java.util.TreeMap<>();
        new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbcTemplate).query(
                "SELECT follower_id, followee_id FROM follows WHERE active AND follower_id IN (:ids)",
                Map.of("ids", followers.stream().map(UUID::fromString).toList()),
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        result.computeIfAbsent(rs.getString(1), k -> new java.util.TreeSet<>()).add(rs.getString(2)));
        return result;
    }

    private long socialEventsInOutbox() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events WHERE topic = 'social-events'", Long.class);
    }

    private void awaitSocialOutboxDrained() {
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(socialOutboxStore.countPending()).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events WHERE topic = 'social-events' "
                    + "AND published_at IS NULL", Long.class)).isZero();
        });
    }

    private void resetFollowBackfillMarker() {
        neo4jClient.query("MATCH (m:Backfill {name: $name}) DETACH DELETE m")
                .bind(FollowProjectionBackfill.NAME).to("name").run();
    }

    private java.net.http.HttpResponse<String> followOverHttp(String method, String follower, String followee)
            throws Exception {
        return java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                                "http://localhost:" + port + "/api/v1/social/follow"))
                        .header("Authorization", "Bearer " + jwtService.issueToken(UUID.fromString(follower), "legacy"))
                        .header("Content-Type", "application/json")
                        .method(method, java.net.http.HttpRequest.BodyPublishers.ofString(
                                "{\"followeeId\":\"" + followee + "\"}")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(22)
    void followBackfillProjectsPre2AFollows_losesNoLiveChange_resurrectsNoUnfollow_andIsIdempotent() throws Exception {
        // Pre-2A data written straight into Neo4j: users and FOLLOWS relationships, no outbox nodes, no socialClock.
        int n = 200;
        List<String> followers = new ArrayList<>(neo4jClient.query("""
                        UNWIND range(1, $n) AS i
                        CREATE (u:User {userId: randomUUID(), username: 'legacy-follower-' + i})
                        RETURN u.userId AS id""")
                .bind(n).to("n").fetchAs(String.class).all());
        followers.sort(null); // the backfill walks followers in userId order
        List<String> followees = new ArrayList<>(neo4jClient.query("""
                        UNWIND range(1, 4) AS i
                        CREATE (u:User {userId: randomUUID(), username: 'legacy-followee-' + i})
                        RETURN u.userId AS id""")
                .fetchAs(String.class).all());
        neo4jClient.query("""
                        UNWIND $followers AS f
                        MATCH (a:User {userId: f})
                        UNWIND $followees AS t
                        MATCH (b:User {userId: t})
                        WITH a, b, t WHERE t <> $followees[3]
                        CREATE (a)-[:FOLLOWS]->(b)""")
                .bindAll(Map.of("followers", followers, "followees", followees)).run();
        assertThat(graphFollows(followers)).hasSize(n);
        assertThat(projectedFollows(followers)).isEmpty(); // exactly the gap the backfill closes

        // Followers from the earlier tests (alice, erin) are backfilled too (harmless: "active" at a newer tick).
        long otherFollowers = neo4jClient.query("""
                        MATCH (a:User) WHERE EXISTS { (a)-[:FOLLOWS]->(:User) } AND NOT a.userId IN $ids
                        RETURN count(a)""")
                .bind(followers).to("ids").fetchAs(Long.class).one().orElseThrow();

        // The startup run completed on an empty graph; reset the marker to run it over the legacy data.
        resetFollowBackfillMarker();
        long outboxBefore = socialEventsInOutbox();

        // Live changes through the API while the backfill runs, on followers it reaches early AND late:
        // unfollow a legacy pair, follow a new one, unfollow + re-follow another.
        List<String> touched = new ArrayList<>(followers.subList(0, 10));
        touched.addAll(followers.subList(n - 10, n));
        var backfill = CompletableFuture.supplyAsync(() -> followProjectionBackfill.run(() -> false));
        var live = CompletableFuture.runAsync(() -> {
            try {
                for (String f : touched) {
                    assertThat(followOverHttp("DELETE", f, followees.get(0)).statusCode()).isEqualTo(204);
                    assertThat(followOverHttp("POST", f, followees.get(3)).statusCode()).isEqualTo(200);
                    assertThat(followOverHttp("DELETE", f, followees.get(1)).statusCode()).isEqualTo(204);
                    assertThat(followOverHttp("POST", f, followees.get(1)).statusCode()).isEqualTo(200);
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        var outcome = backfill.get(60, TimeUnit.SECONDS);
        live.get(60, TimeUnit.SECONDS);

        assertThat(outcome.alreadyDone()).isFalse();
        assertThat(outcome.items()).isEqualTo(n + otherFollowers); // every follower still follows someone
        assertThat(followBackfillStore.marker(FollowProjectionBackfill.NAME).completed()).isTrue();

        // Feed's projection converges to the graph for every follower (newest occurredAt wins per pair).
        awaitSocialOutboxDrained();
        var graph = graphFollows(followers);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(projectedFollows(followers)).isEqualTo(graph));
        for (String f : touched) {
            assertThat(graph.get(f)).containsExactlyInAnyOrder(followees.get(1), followees.get(2), followees.get(3));
            // the unfollowed legacy pair is a tombstone, whatever order its events were applied in
            assertThat(jdbcTemplate.queryForObject("SELECT active FROM follows WHERE follower_id = ? AND followee_id = ?",
                    Boolean.class, UUID.fromString(f), UUID.fromString(followees.get(0)))).isFalse();
        }
        // one event per relationship the backfill saw, on top of the live ones (4 per touched follower)
        assertThat(socialEventsInOutbox() - outboxBefore).isEqualTo(outcome.written() + 4L * touched.size());

        // Second run: the durable marker makes it a no-op.
        long rows = socialEventsInOutbox();
        assertThat(followProjectionBackfill.run(() -> false).alreadyDone()).isTrue();
        assertThat(socialOutboxStore.countPending()).isZero();
        assertThat(socialEventsInOutbox()).isEqualTo(rows);

        // Forced re-run (marker deleted): one more "active" event per current relationship, newer ticks; the
        // projection is unchanged and has exactly one row per pair (no duplicates).
        resetFollowBackfillMarker();
        var rerun = followProjectionBackfill.run(() -> false);
        long relationships = neo4jClient.query("MATCH (:User)-[r:FOLLOWS]->(:User) RETURN count(r)")
                .fetchAs(Long.class).one().orElseThrow();
        assertThat(rerun.written()).isEqualTo(relationships);
        awaitSocialOutboxDrained();
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(projectedFollows(followers)).isEqualTo(graph));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) - count(DISTINCT (follower_id, followee_id)) FROM follows""", Long.class)).isZero();
    }

    @Test
    @Order(23)
    void whoToFollowRanksFriendsOfFriendsByMutualFolloweesAndTopsUpWithMostFollowed() {
        // me -> a, b ; a -> x, y, b, me ; b -> x ; z is unrelated. Direct Cypher: no outbox needed for a read query.
        Map<String, String> ids = new java.util.HashMap<>();
        for (String name : List.of("me", "a", "b", "x", "y", "z", "lonely")) {
            ids.put(name, UUID.randomUUID().toString());
            neo4jClient.query("CREATE (:User {userId: $id, username: $name})")
                    .bind(ids.get(name)).to("id").bind("wtf-" + name).to("name").run();
        }
        for (String[] edge : new String[][] {{"me", "a"}, {"me", "b"}, {"a", "x"}, {"a", "y"}, {"a", "b"}, {"a", "me"},
                {"b", "x"}}) {
            neo4jClient.query("MATCH (f:User {userId: $f}), (t:User {userId: $t}) CREATE (f)-[:FOLLOWS]->(t)")
                    .bind(ids.get(edge[0])).to("f").bind(ids.get(edge[1])).to("t").run();
        }
        UUID me = UUID.fromString(ids.get("me"));

        var top = socialGraphService.getSuggestions(me, 2);
        assertThat(top).extracting(FollowSuggestion::userId).containsExactly(ids.get("x"), ids.get("y"));
        assertThat(top).extracting(FollowSuggestion::mutualFollowers).containsExactly(2L, 1L);

        // Larger limit: friends of friends first, then the most followed; never me or anyone I follow, no duplicates.
        var all = socialGraphService.getSuggestions(me, 20);
        assertThat(all).extracting(FollowSuggestion::userId).startsWith(ids.get("x"), ids.get("y"))
                .doesNotContain(ids.get("me"), ids.get("a"), ids.get("b")).doesNotHaveDuplicates();
        assertThat(all.stream().skip(2)).allMatch(s -> s.mutualFollowers() == 0);

        // Cold start: nobody followed yet -> popularity only, still never the viewer.
        var cold = socialGraphService.getSuggestions(UUID.fromString(ids.get("lonely")), 5);
        assertThat(cold).isNotEmpty().allMatch(s -> s.mutualFollowers() == 0)
                .extracting(FollowSuggestion::userId).doesNotContain(ids.get("lonely"));
    }

    private long outboxRows(UUID messageKey) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events WHERE message_key = ?",
                Long.class, messageKey.toString());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private static <T> void runConcurrently(int n, java.util.function.IntFunction<Callable<T>> task) throws Exception {
        var pool = Executors.newFixedThreadPool(n);
        try {
            var start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<T> c = task.apply(i);
                futures.add(pool.submit(() -> { start.await(); return c.call(); }));
            }
            start.countDown();
            for (var f : futures) f.get(); // rethrows any failure
        } finally {
            pool.shutdownNow();
        }
    }

    private KafkaConsumer<String, String> buildTestConsumer(String servers, String groupId) {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servers,
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        ));
    }
}
