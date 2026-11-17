package com.socialapp.config.outbox;

import com.socialapp.shared.events.DomainEvent;
import com.socialapp.shared.events.ReactionEvent;
import com.socialapp.shared.events.UserUpdatedEvent;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The relay and cleanup against a real PostgreSQL (V14 schema, real SQL incl. advisory lock and the
 * parked-key filter) with a mocked Kafka producer, so broker failures can be simulated deterministically.
 * No Spring context: fast, and nothing else polls the table.
 */
@Testcontainers(disabledWithoutDocker = true)
class OutboxRelayPostgresTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;

    private static final int MAX_ATTEMPTS = 2;
    private final DomainEventCodec codec = new DomainEventCodec();
    private OutboxRepository repository;
    private OutboxRelay relay;

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, DomainEvent> kafka = mock(KafkaTemplate.class);
    /** Messages "on Kafka", in send order. */
    private final List<DomainEvent> sent = new CopyOnWriteArrayList<>();
    /** How many of the next sends fail as if the broker were down. */
    private final AtomicInteger brokerDownForSends = new AtomicInteger();

    @BeforeAll
    static void migrate() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        dataSource.setMaximumPoolSize(4);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void close() {
        if (dataSource != null) dataSource.close();
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE outbox_events RESTART IDENTITY");
        repository = new OutboxRepository(jdbc);
        var props = new OutboxProperties(300, 100, MAX_ATTEMPTS, 1000, 7, 3_600_000, 2);
        relay = new OutboxRelay(repository, kafka,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), codec, props, Runnable::run);

        when(kafka.send(anyString(), anyString(), any())).thenAnswer(inv -> {
            String topic = inv.getArgument(0);
            DomainEvent event = inv.getArgument(2);
            if (brokerDownForSends.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                return CompletableFuture.failedFuture(
                        new org.apache.kafka.common.errors.TimeoutException("Topic not present in metadata"));
            }
            return delivered(topic, inv.getArgument(1), event);
        });
    }

    private CompletableFuture<SendResult<String, DomainEvent>> delivered(String topic, String key, DomainEvent event) {
        sent.add(event);
        return CompletableFuture.completedFuture(new SendResult<>(new ProducerRecord<>(topic, key, event),
                new RecordMetadata(new TopicPartition(topic, 0), sent.size(), 0, 0, 0, 0)));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private DomainEvent append(String key) {
        var event = new UserUpdatedEvent(UUID.randomUUID(), Instant.now(), UUID.randomUUID(), key, null, null);
        appendRaw(event.eventId(), key, codec.toJson(event));
        return event;
    }

    private void appendRaw(UUID eventId, String key, String payload) {
        repository.append(new OutboxRepository.NewMessage(eventId, "User", "UserUpdatedEvent",
                "user-events", key, payload));
    }

    private long unpublishedCount() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long.class);
    }

    private <T> T column(String column, Class<T> type, UUID eventId) {
        return jdbc.queryForObject("SELECT " + column + " FROM outbox_events WHERE event_id = ?", type, eventId);
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void publishesInIdOrderAndStampsPublishedAt() {
        var a1 = append("A");
        var b1 = append("B");
        var a2 = append("A");

        relay.poll();

        assertThat(sent).containsExactly(a1, b1, a2);
        assertThat(unpublishedCount()).isZero();
        assertThat(column("attempts", Integer.class, a1.eventId())).isZero();
    }

    @Test
    void kafkaDown_rowsStayUnpublished_thenAreDeliveredLaterInOrder_neverParked() {
        var a1 = append("A");
        var b1 = append("B");
        var a2 = append("A");
        brokerDownForSends.set(MAX_ATTEMPTS + 1); // longer than max-attempts: an outage must not park rows

        for (int i = 0; i < MAX_ATTEMPTS + 1; i++) relay.poll();

        assertThat(sent).isEmpty(); // stopped at the head row every time, nothing overtook it
        assertThat(unpublishedCount()).isEqualTo(3);
        assertThat(column("attempts", Integer.class, a1.eventId())).isEqualTo(MAX_ATTEMPTS + 1);
        assertThat(column("last_error", String.class, a1.eventId())).contains("TimeoutException");
        assertThat(column("parked_at", OffsetDateTime.class, a1.eventId())).isNull();
        assertThat(column("attempts", Integer.class, b1.eventId())).isZero();

        relay.poll(); // broker back

        assertThat(sent).containsExactly(a1, b1, a2);
        assertThat(unpublishedCount()).isZero();
    }

    @Test
    void failureMidBatch_keepsEarlierRowsPublished_andResumesAtTheFailedRow() {
        var a1 = append("A");
        var b1 = append("B");
        var a2 = append("A");
        doAnswer(inv -> {
            DomainEvent event = inv.getArgument(2);
            if (event.equals(b1) && brokerDownForSends.getAndIncrement() == 0) {
                return CompletableFuture.failedFuture(new org.apache.kafka.common.errors.NetworkException("reset"));
            }
            return delivered(inv.getArgument(0), inv.getArgument(1), event);
        }).when(kafka).send(anyString(), anyString(), any());

        relay.poll();
        assertThat(sent).containsExactly(a1);
        assertThat(column("published_at", OffsetDateTime.class, a1.eventId())).isNotNull();
        assertThat(column("published_at", OffsetDateTime.class, b1.eventId())).isNull();

        relay.poll();
        assertThat(sent).containsExactly(a1, b1, a2);
    }

    @Test
    void poisonRowIsParkedAfterMaxAttempts_andBlocksOnlyItsOwnKey() {
        UUID poisonId = UUID.randomUUID();
        appendRaw(poisonId, "A", "{\"eventType\":\"NoSuchEvent\"}"); // A1: can never be published
        var b1 = append("B");
        var a2 = append("A");
        var b2 = append("B");

        relay.poll(); // attempt 1: fails, not yet parked, nothing overtakes it
        assertThat(sent).isEmpty();
        assertThat(column("parked_at", OffsetDateTime.class, poisonId)).isNull();

        relay.poll(); // attempt 2 = max-attempts: parked
        assertThat(column("attempts", Integer.class, poisonId)).isEqualTo(MAX_ATTEMPTS);
        assertThat(column("parked_at", OffsetDateTime.class, poisonId)).isNotNull();
        assertThat(column("last_error", String.class, poisonId)).contains("OutboxPayloadException");

        relay.poll(); // other keys flow; A2 waits behind the parked A1
        assertThat(sent).containsExactly(b1, b2);
        assertThat(column("published_at", OffsetDateTime.class, a2.eventId())).isNull();

        // Operator repairs the row and un-parks it: key A resumes in order
        var repaired = new UserUpdatedEvent(poisonId, Instant.now(), UUID.randomUUID(), "A", null, null);
        jdbc.update("UPDATE outbox_events SET payload = ?::jsonb, parked_at = NULL WHERE event_id = ?",
                codec.toJson(repaired), poisonId);
        relay.poll();
        assertThat(sent).containsExactly(b1, b2, repaired, a2);
        assertThat(unpublishedCount()).isZero();
    }

    @Test
    void onlyOneInstancePublishesAtATime() throws Exception {
        var a1 = append("A");
        try (Connection other = dataSource.getConnection()) { // "another instance" holding the relay lock
            other.setAutoCommit(false);
            try (var st = other.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                st.setLong(1, OutboxRepository.RELAY_LOCK_KEY);
                st.execute();
            }
            relay.poll();
            assertThat(sent).isEmpty();
            other.rollback(); // releases the transaction-scoped lock
        }
        relay.poll();
        assertThat(sent).containsExactly(a1);
    }

    @Test
    void stoppedRelayPublishesNothingAndIgnoresWakeUps() {
        append("A");
        relay.stop();

        relay.poll();
        relay.wakeUp();

        assertThat(sent).isEmpty();
        assertThat(unpublishedCount()).isEqualTo(1);
    }

    @Test
    void stopInterruptsABlockedSendSoShutdownIsFast() throws Exception {
        var a1 = append("A");
        var slowRelay = new OutboxRelay(repository, kafka,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), codec,
                new OutboxProperties(300, 100, MAX_ATTEMPTS, 60_000, 7, 3_600_000, 2), Runnable::run);
        var sendStarted = new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv -> {
            sendStarted.countDown();
            return new CompletableFuture<>(); // broker never answers
        }).when(kafka).send(anyString(), anyString(), any());

        var poller = new Thread(slowRelay::poll, "test-outbox-poller");
        poller.start();
        assertThat(sendStarted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        long t0 = System.nanoTime();
        slowRelay.stop();
        poller.join(5_000);

        assertThat(poller.isAlive()).isFalse();
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - t0)).isLessThan(java.time.Duration.ofSeconds(2));
        assertThat(column("published_at", OffsetDateTime.class, a1.eventId())).isNull();
        assertThat(column("attempts", Integer.class, a1.eventId())).isZero(); // shutdown is not a failure
    }

    @Test
    void wakeUpPublishesImmediately() {
        var a1 = append("A");

        relay.wakeUp(); // executor runs inline in this test

        assertThat(sent).containsExactly(a1);
    }

    @Test
    void cleanupDeletesOnlyPublishedRowsOlderThanRetention() {
        var now = Instant.parse("2026-11-14T12:00:00Z");
        var oldPublished1 = append("A");
        var oldPublished2 = append("B");
        var oldPublished3 = append("C");
        var recentPublished = append("D");
        var oldUnpublished = append("E");
        var parkedOld = append("F");
        jdbc.update("UPDATE outbox_events SET created_at = ?, published_at = ? WHERE event_id IN (?, ?, ?)",
                now.minusSeconds(9 * 86400).atOffset(ZoneOffset.UTC), now.minusSeconds(8 * 86400).atOffset(ZoneOffset.UTC),
                oldPublished1.eventId(), oldPublished2.eventId(), oldPublished3.eventId());
        jdbc.update("UPDATE outbox_events SET published_at = ? WHERE event_id = ?",
                now.minusSeconds(6 * 86400).atOffset(ZoneOffset.UTC), recentPublished.eventId());
        jdbc.update("UPDATE outbox_events SET created_at = ? WHERE event_id = ?",
                now.minusSeconds(30 * 86400).atOffset(ZoneOffset.UTC), oldUnpublished.eventId());
        jdbc.update("UPDATE outbox_events SET created_at = ?, parked_at = ? WHERE event_id = ?",
                now.minusSeconds(30 * 86400).atOffset(ZoneOffset.UTC), now.minusSeconds(29 * 86400).atOffset(ZoneOffset.UTC),
                parkedOld.eventId());

        var cleanup = new OutboxCleanup(repository, new OutboxProperties(300, 100, MAX_ATTEMPTS, 1000, 7, 3_600_000, 2),
                Clock.fixed(now, ZoneOffset.UTC));
        int deleted = cleanup.deleteExpired(); // batch size 2 -> several chunks

        assertThat(deleted).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT event_id FROM outbox_events ORDER BY id", UUID.class))
                .containsExactly(recentPublished.eventId(), oldUnpublished.eventId(), parkedOld.eventId());
    }

    @Test
    void reactionEventsSurviveTheRoundTripThroughJsonb() {
        var reaction = new ReactionEvent(UUID.randomUUID(), UUID.randomUUID(),
                ReactionEvent.ReactionType.DISLIKE, ReactionEvent.ReactionAction.REMOVED);
        repository.append(new OutboxRepository.NewMessage(reaction.eventId(), "Post", reaction.eventType(),
                "reaction-events", reaction.postId().toString(), codec.toJson(reaction)));

        relay.poll();

        assertThat(sent).containsExactly(reaction); // equal incl. eventId and occurredAt (nanos) after jsonb
    }

    // ── OutboxImporter (events recorded in another store, e.g. SocialGraph's Neo4j outbox) ──────────

    private OutboxImporter importer() {
        var writer = new OutboxEventWriter(repository, relay, codec,
                "user-events", "post-events", "reaction-events", "social-events", "comment-events");
        return new OutboxImporter(repository, writer, relay,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private long rowsFor(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_id = ?", Long.class, eventId);
    }

    @Test
    void importIsIdempotentByEventId_andWakesTheRelayAfterCommit() {
        var follower = UUID.randomUUID();
        var followee = UUID.randomUUID();
        var followed = new com.socialapp.shared.events.UserFollowedEvent(UUID.randomUUID(),
                Instant.parse("2026-11-14T10:00:00.001Z"), follower, followee);
        var unfollowed = new com.socialapp.shared.events.UserUnfollowedEvent(UUID.randomUUID(),
                Instant.parse("2026-11-14T10:00:00.002Z"), follower, followee);
        var importer = importer();

        assertThat(importer.importBatch(List.of(followed, unfollowed))).isTrue();
        // published at once by the after-commit wake-up (inline executor), in the offered order
        assertThat(sent).containsExactly(followed, unfollowed);
        assertThat(column("topic", String.class, followed.eventId())).isEqualTo("social-events");
        assertThat(column("message_key", String.class, followed.eventId())).isEqualTo(follower.toString());
        assertThat(column("aggregate_type", String.class, unfollowed.eventId())).isEqualTo("Follow");

        // Crash between the Postgres commit and the Neo4j delete: the same events are offered again.
        assertThat(importer.importBatch(List.of(followed, unfollowed))).isTrue();
        assertThat(rowsFor(followed.eventId())).isEqualTo(1);
        assertThat(rowsFor(unfollowed.eventId())).isEqualTo(1);
        relay.poll();
        assertThat(sent).containsExactly(followed, unfollowed); // not re-published
    }

    @Test
    void importWaitsWhileAnotherInstanceImports() throws Exception {
        var event = new com.socialapp.shared.events.UserFollowedEvent(UUID.randomUUID(), Instant.now(),
                UUID.randomUUID(), UUID.randomUUID());
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (var st = other.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                st.setLong(1, OutboxRepository.IMPORT_LOCK_KEY);
                st.execute();
            }
            assertThat(importer().importBatch(List.of(event))).isFalse();
            assertThat(rowsFor(event.eventId())).isZero();
            other.rollback();
        }
        assertThat(importer().importBatch(List.of(event))).isTrue();
        assertThat(rowsFor(event.eventId())).isEqualTo(1);
    }

    @Test
    void failedImportWritesNothing() {
        var good = new com.socialapp.shared.events.UserFollowedEvent(UUID.randomUUID(), Instant.now(),
                UUID.randomUUID(), UUID.randomUUID());
        var writer = new OutboxEventWriter(repository, relay, codec,
                "user-events", "post-events", "reaction-events", "social-events", "comment-events");
        var failingRepository = org.mockito.Mockito.spy(repository);
        org.mockito.Mockito.doCallRealMethod().doThrow(new IllegalStateException("boom"))
                .when(failingRepository).appendIfAbsent(any());
        var importer = new OutboxImporter(failingRepository, writer, relay,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        var second = new com.socialapp.shared.events.UserUnfollowedEvent(UUID.randomUUID(), Instant.now(),
                good.followerId(), good.followeeId());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> importer.importBatch(List.of(good, second)))
                .hasMessageContaining("boom");

        assertThat(rowsFor(good.eventId())).isZero(); // rolled back together
        assertThat(sent).isEmpty();
    }
}
