package com.socialapp.integration;

import com.socialapp.feed.application.usecase.PostService;
import com.socialapp.feed.domain.Post;
import com.socialapp.shared.events.ReactionEvent;
import com.socialapp.socialgraph.application.command.FollowUserCommand;
import com.socialapp.socialgraph.application.usecase.SocialGraphService;
import com.socialapp.socialgraph.domain.UserNode;
import com.socialapp.usermanagement.application.command.CreateUserCommand;
import com.socialapp.usermanagement.application.usecase.UserService;
import com.socialapp.usermanagement.domain.User;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Full integration test spinning up PostgreSQL, Neo4j and Kafka in parallel.
 * Testcontainers @Container fields are static so containers are shared
 * across all test methods (started once per class).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
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
    }

    @Autowired UserService userService;
    @Autowired SocialGraphService socialGraphService;
    @Autowired PostService postService;

    private static UUID aliceId;
    private static UUID bobId;

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void shouldCreateUserAndSyncToNeo4j() {
        // When
        User alice = userService.handle(new CreateUserCommand("alice", "alice@test.com", null));
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
        User bob = userService.handle(new CreateUserCommand("bob", "bob@test.com", null));
        bobId = bob.getId();
        assertThat(bob.getUsername()).isEqualTo("bob");
    }

    @Test
    @Order(3)
    void shouldFollowUser() {
        // Given — ensure nodes exist in Neo4j
        socialGraphService.syncUserCreated(
                new com.socialapp.shared.events.UserCreatedEvent(aliceId, "alice", "alice@test.com", null));
        socialGraphService.syncUserCreated(
                new com.socialapp.shared.events.UserCreatedEvent(bobId, "bob", "bob@test.com", null));

        // When
        socialGraphService.follow(new FollowUserCommand(aliceId, bobId));

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

    // ── Helpers ───────────────────────────────────────────────────────────────
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
