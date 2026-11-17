package com.socialapp.config.outbox;

import com.socialapp.config.KafkaConfig;
import com.socialapp.shared.events.*;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the JSON of every event type, byte for byte, as written to {@code outbox_events.payload} and sent to Kafka.
 * The expected strings were captured from the Spring Boot 3.5 build (Jackson 2, Spring Kafka's
 * {@code JacksonUtils.enhancedObjectMapper()}); the Jackson 3 mapper used since Spring Boot 4 must produce exactly the
 * same output (property order, {@code occurredAt} as decimal epoch seconds with nanos, nulls written), and both the
 * codec and the consumers' deserializer must read it back to equal events.
 *
 * <p>One difference between the two outputs is inherited from Jackson 2 and kept on purpose: the outbox payload
 * (a String) carries characters outside the BMP (emoji) raw, the Kafka record value (UTF-8 bytes) escapes them as two
 * JSON {@code \\u} escapes of their UTF-16 surrogates, exactly as Spring Kafka's Jackson 2 {@code JsonSerializer} did.
 */
class DomainEventWireFormatTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID D = UUID.fromString("00000000-0000-0000-0000-00000000000d");
    private static final UUID E = UUID.fromString("00000000-0000-0000-0000-00000000000e");
    private static final Instant NANOS = Instant.parse("2026-01-02T03:04:05.123456789Z");
    private static final Instant MILLIS = Instant.parse("2026-01-02T03:04:05.006Z");
    private static final Instant WHOLE = Instant.parse("2026-01-02T03:04:05Z");

    private final DomainEventCodec codec = new DomainEventCodec();

    private static List<DomainEvent> events() {
        return List.of(
                new UserCreatedEvent(A, NANOS, B, "alice", "alice@test.com", "http://x/a.png"),
                new UserCreatedEvent(A, WHOLE, B, "alice", "alice@test.com", null),
                new UserUpdatedEvent(A, MILLIS, B, "alice2", null, "bio \"quoted\" \u2713 \uD83D\uDE00 <b>&</b>\n"),
                new PostCreatedEvent(A, NANOS, B, C, "alice", "hello #tag @bob", "http://x/i.png", "POST", null, null,
                        null, List.of()),
                new PostCreatedEvent(A, MILLIS, B, C, "alice", "quote", null, "QUOTE", D, E, "orig", List.of(D, E)),
                new ReactionEvent(A, NANOS, B, C, ReactionEvent.ReactionType.LIKE, ReactionEvent.ReactionAction.ADDED,
                        ReactionEvent.ReactionTarget.POST, null, D, "snip"),
                new ReactionEvent(A, WHOLE, B, C, ReactionEvent.ReactionType.DISLIKE,
                        ReactionEvent.ReactionAction.REMOVED, ReactionEvent.ReactionTarget.COMMENT, D, E, null),
                new UserFollowedEvent(A, MILLIS, B, C),
                new UserUnfollowedEvent(A, NANOS, B, C),
                new CommentCreatedEvent(A, NANOS, B, C, D, E, A, B, "alice", "c"),
                new CommentCreatedEvent(A, WHOLE, B, C, D, null, null, B, "alice", "c"));
    }

    private static List<String> golden() {
        return List.of(
                "{\"eventType\":\"UserCreatedEvent\",\"eventId\":\"00000000-0000-0000-0000-00000000000a\",\"occurredAt\":1767323045.123456789,\"userId\":\"00000000-0000-0000-0000-00000000000b\",\"username\":\"alice\",\"email\":\"alice@test.com\",\"avatarUrl\":\"http://x/a.png\"}",
                "{\"eventType\":\"UserCreatedEvent\",\"eventId\":\"00000000-0000-0000-0000-00000000000a\",\"occurredAt\":1767323045.000000000,\"userId\":\"00000000-0000-0000-0000-00000000000b\",\"username\":\"alice\",\"email\":\"alice@test.com\",\"avatarUrl\":null}",
                "{\"eventType\":\"UserUpdatedEvent\",\"eventId\":\"00000000-0000-0000-0000-00000000000a\",\"occurredAt\":1767323045.006000000,\"userId\":\"00000000-0000-0000-0000-00000000000b\",\"username\":\"alice2\",\"avatarUrl\":null,\"bio\":\"bio \\\"quoted\\\" \u2713 \uD83D\uDE00 <b>&</b>\\n\"}",
                "{\"eventType\":\"PostCreatedEvent\",\"eventId\":\"00000000-0000-0000-0000-00000000000a\",\"occurredAt\":1767323045.123456789,\"postId\":\"00000000-0000-0000-0000-00000000000b\",\"authorId\":\"00000000-0000-0000-0000-00000000000c\",\"authorUsername\":\"alice\",\"content\":\"hello #tag @bob\",\"imageUrl\":\"http://x/i.png\",\"postType\":\"POST\",\"originalPostId\":null,\"originalAuthorId\":null,\"originalContent\":null,\"mentionedUserIds\":[]}",
                "{\"eventType\":\"PostCreatedEvent\",\"eventId\":\"00000000-0000-0000-0000-00000000000a\",\"occurredAt\":1767323045.006000000,\"postId\":\"00000000-0000-0000-0000-00000000000b\",\"authorId\":\"00000000-0000-0000-0000-00000000000c\",\"authorUsername\":\"alice\",\"content\":\"quote\",\"imageUrl\":null,\"postType\":\"QUOTE\",\"originalPostId\":\"00000000-0000-0000-0000-00000000000d\",\"originalAuthorId\":\"00000000-0000-0000-0000-00000000000e\",\"originalContent\":\"orig\",\"mentionedUserIds\":[\"00000000-0000-0000-0000-00000000000d\",\"00000000-0000-0000-0000-00000000000e\"]}",
                "{\"eventType\":\"ReactionEvent\",\"eventId\":\"00000000-0000-0000-0000-00000000000a\",\"occurredAt\":1767323045.123456789,\"postId\":\"00000000-0000-0000-0000-00000000000b\",\"userId\":\"00000000-0000-0000-0000-00000000000c\",\"reactionType\":\"LIKE\",\"action\":\"ADDED\",\"target\":\"POST\",\"commentId\":null,\"targetOwnerId\":\"00000000-0000-0000-0000-00000000000d\",\"targetSnippet\":\"snip\"}",
                "{\"eventType\":\"ReactionEvent\",\"eventId\":\"00000000-0000-0000-0000-00000000000a\",\"occurredAt\":1767323045.000000000,\"postId\":\"00000000-0000-0000-0000-00000000000b\",\"userId\":\"00000000-0000-0000-0000-00000000000c\",\"reactionType\":\"DISLIKE\",\"action\":\"REMOVED\",\"target\":\"COMMENT\",\"commentId\":\"00000000-0000-0000-0000-00000000000d\",\"targetOwnerId\":\"00000000-0000-0000-0000-00000000000e\",\"targetSnippet\":null}",
                "{\"eventType\":\"UserFollowedEvent\",\"eventId\":\"00000000-0000-0000-0000-00000000000a\",\"occurredAt\":1767323045.006000000,\"followerId\":\"00000000-0000-0000-0000-00000000000b\",\"followeeId\":\"00000000-0000-0000-0000-00000000000c\"}",
                "{\"eventType\":\"UserUnfollowedEvent\",\"eventId\":\"00000000-0000-0000-0000-00000000000a\",\"occurredAt\":1767323045.123456789,\"followerId\":\"00000000-0000-0000-0000-00000000000b\",\"followeeId\":\"00000000-0000-0000-0000-00000000000c\"}",
                "{\"eventType\":\"CommentCreatedEvent\",\"eventId\":\"00000000-0000-0000-0000-00000000000a\",\"occurredAt\":1767323045.123456789,\"commentId\":\"00000000-0000-0000-0000-00000000000b\",\"postId\":\"00000000-0000-0000-0000-00000000000c\",\"postAuthorId\":\"00000000-0000-0000-0000-00000000000d\",\"parentCommentId\":\"00000000-0000-0000-0000-00000000000e\",\"parentCommentAuthorId\":\"00000000-0000-0000-0000-00000000000a\",\"authorId\":\"00000000-0000-0000-0000-00000000000b\",\"authorUsername\":\"alice\",\"contentSnippet\":\"c\"}",
                "{\"eventType\":\"CommentCreatedEvent\",\"eventId\":\"00000000-0000-0000-0000-00000000000a\",\"occurredAt\":1767323045.000000000,\"commentId\":\"00000000-0000-0000-0000-00000000000b\",\"postId\":\"00000000-0000-0000-0000-00000000000c\",\"postAuthorId\":\"00000000-0000-0000-0000-00000000000d\",\"parentCommentId\":null,\"parentCommentAuthorId\":null,\"authorId\":\"00000000-0000-0000-0000-00000000000b\",\"authorUsername\":\"alice\",\"contentSnippet\":\"c\"}");
    }

    @Test
    void everyEventTypeIsWrittenExactlyAsBefore() {
        var events = events();
        var golden = golden();
        assertThat(events).hasSameSizeAs(golden);
        for (int i = 0; i < events.size(); i++) {
            assertThat(codec.toJson(events.get(i))).as("event #%d", i).isEqualTo(golden.get(i));
        }
    }

    @Test
    void theProducerSerializerWritesTheSameBytes() {
        var events = events();
        var golden = golden();
        try (var wire = KafkaConfig.<DomainEvent>eventSerializer()) {
            for (int i = 0; i < events.size(); i++) {
                assertThat(new String(wire.serialize("t", events.get(i)), StandardCharsets.UTF_8))
                        .as("event #%d", i).isEqualTo(escapeSupplementaryCharacters(golden.get(i)));
            }
        }
    }

    @Test
    void theGoldenJsonReadsBackToEqualEvents() {
        var events = events();
        var golden = golden();
        try (var consumer = consumerDeserializer()) {
            for (int i = 0; i < events.size(); i++) {
                assertThat(codec.fromJson(golden.get(i))).as("codec, event #%d", i).isEqualTo(events.get(i));
                assertThat(consumer.deserialize("t", golden.get(i).getBytes(StandardCharsets.UTF_8)))
                        .as("consumer, event #%d", i).isEqualTo(events.get(i));
            }
        }
    }

    @Test
    void theConsumersReadEventsWrittenBeforeLaterFieldsExisted() {
        try (var consumer = consumerDeserializer()) {
            var reaction = (ReactionEvent) consumer.deserialize("t", ("{\"eventType\":\"ReactionEvent\",\"eventId\":\"" + A
                    + "\",\"occurredAt\":\"2026-11-01T10:00:00Z\",\"postId\":\"" + B + "\",\"userId\":\"" + C
                    + "\",\"reactionType\":\"LIKE\",\"action\":\"ADDED\",\"someFutureField\":1}")
                    .getBytes(StandardCharsets.UTF_8));
            assertThat(reaction).isEqualTo(new ReactionEvent(A, Instant.parse("2026-11-01T10:00:00Z"), B, C,
                    ReactionEvent.ReactionType.LIKE, ReactionEvent.ReactionAction.ADDED));

            var post = (PostCreatedEvent) consumer.deserialize("t", ("{\"eventType\":\"PostCreatedEvent\",\"eventId\":\""
                    + A + "\",\"occurredAt\":1767323045.123456789,\"postId\":\"" + B + "\",\"authorId\":\"" + C
                    + "\",\"authorUsername\":\"alice\",\"content\":\"hi\",\"imageUrl\":null}")
                    .getBytes(StandardCharsets.UTF_8));
            assertThat(post).isEqualTo(new PostCreatedEvent(A, NANOS, B, C, "alice", "hi", null, null, null, null,
                    null, List.of()));
        }
    }

    /** Each UTF-16 surrogate as a JSON {@code \\u} escape, as the Jackson 2 (and configured Jackson 3) UTF-8 writer. */
    private static String escapeSupplementaryCharacters(String json) {
        var out = new StringBuilder();
        json.chars().forEach(c -> out.append(Character.isSurrogate((char) c)
                ? String.format("\\u%04X", c) : String.valueOf((char) c)));
        return out.toString();
    }

    /** The value deserializer every listener factory uses (configured by properties, as in KafkaConfig). */
    private static JacksonJsonDeserializer<Object> consumerDeserializer() {
        var deserializer = new JacksonJsonDeserializer<>();
        deserializer.configure(Map.of(
                JacksonJsonDeserializer.TRUSTED_PACKAGES, "com.socialapp.*",
                JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, DomainEvent.class.getName(),
                JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, false), false);
        return deserializer;
    }
}
