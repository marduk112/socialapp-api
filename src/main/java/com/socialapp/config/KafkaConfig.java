package com.socialapp.config;

import com.socialapp.config.outbox.DomainEventCodec;
import com.socialapp.shared.events.*;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@EnableKafka
public class KafkaConfig {

    /** Dead-letter topic of {@code topic} is {@code topic + DLT_SUFFIX}, same partition. */
    public static final String DLT_SUFFIX = ".DLT";

    /** Interval between retries of a failed record. */
    static final long RETRY_INTERVAL_MS = 1_000L;
    /** Retries after the first failure: 1 + 2 = 3 delivery attempts, then the record goes to the DLT. */
    static final long MAX_RETRIES = 2L;

    private static final int PARTITIONS = 3;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /** Bound of one send; the outbox relay waits for each send synchronously (see OutboxRelay). */
    @Value("${app.outbox.send-timeout-ms:5000}")
    private int sendTimeoutMs;

    @Value("${kafka.topics.user-events}")
    private String userEventsTopic;

    @Value("${kafka.topics.post-events}")
    private String postEventsTopic;

    @Value("${kafka.topics.reaction-events}")
    private String reactionEventsTopic;

    @Value("${kafka.topics.social-events}")
    private String socialEventsTopic;

    @Value("${kafka.topics.comment-events}")
    private String commentEventsTopic;

    // ── Topics ────────────────────────────────────────────────────────────────
    @Bean
    public NewTopic userEventsTopic() {
        return TopicBuilder.name(userEventsTopic).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic postEventsTopic() {
        return TopicBuilder.name(postEventsTopic).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic reactionEventsTopic() {
        return TopicBuilder.name(reactionEventsTopic).partitions(PARTITIONS).replicas(1).build();
    }

    /** Social graph changes: follows (key followerId), blocks (key blockerId) and mutes (key muterId). */
    @Bean
    public NewTopic socialEventsTopic() {
        return TopicBuilder.name(socialEventsTopic).partitions(PARTITIONS).replicas(1).build();
    }

    /** Comments and replies (CommentCreatedEvent), key = postId. */
    @Bean
    public NewTopic commentEventsTopic() {
        return TopicBuilder.name(commentEventsTopic).partitions(PARTITIONS).replicas(1).build();
    }

    // DLTs need at least as many partitions as the source topic (the recoverer keeps the partition).
    @Bean
    public NewTopic userEventsDltTopic() {
        return TopicBuilder.name(userEventsTopic + DLT_SUFFIX).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic postEventsDltTopic() {
        return TopicBuilder.name(postEventsTopic + DLT_SUFFIX).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic reactionEventsDltTopic() {
        return TopicBuilder.name(reactionEventsTopic + DLT_SUFFIX).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic socialEventsDltTopic() {
        return TopicBuilder.name(socialEventsTopic + DLT_SUFFIX).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic commentEventsDltTopic() {
        return TopicBuilder.name(commentEventsTopic + DLT_SUFFIX).partitions(PARTITIONS).replicas(1).build();
    }

    // ── Producer ──────────────────────────────────────────────────────────────
    // Value serializers are instances built on DomainEventCodec.wireMapper() (Jackson 3 configured to write the same
    // bytes as the Jackson 2 JsonSerializer used before Spring Boot 4), so the outbox payload is the wire JSON. They are
    // configured with setters, hence no spring.json.* properties in these maps (a serializer configured by setters
    // rejects them).
    private Map<String, Object> producerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // Fail a send within app.outbox.send-timeout-ms instead of Kafka's defaults (60s blocking on metadata,
        // 120s delivery): the relay then retries the row itself, and no stale retry keeps running inside the
        // producer after the relay has given up on it. delivery.timeout >= linger (0) + request.timeout.
        props.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, sendTimeoutMs);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, sendTimeoutMs);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, sendTimeoutMs);
        return props;
    }

    /** JSON value serializer of events and dead letters: no type headers, {@link DomainEventCodec#wireMapper()}. */
    public static <T> JacksonJsonSerializer<T> eventSerializer() {
        var json = new JacksonJsonSerializer<T>(DomainEventCodec.wireMapper());
        json.setAddTypeInfo(false);
        return json;
    }

    @Bean
    public ProducerFactory<String, DomainEvent> producerFactory() {
        return new DefaultKafkaProducerFactory<>(producerProps(), new StringSerializer(), eventSerializer());
    }

    @Bean
    public KafkaTemplate<String, DomainEvent> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // ── Error handling / dead-letter topics ───────────────────────────────────

    /**
     * Retries a failing record {@link #MAX_RETRIES} times ({@link #RETRY_INTERVAL_MS} apart, blocking,
     * so per-partition order is kept), then publishes it to {@code <topic>.DLT} and moves on.
     * Deserialization failures (poison pills) are not retryable and go to the DLT immediately.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        var recoverer = new DeadLetterPublishingRecoverer(new KafkaTemplate<>(deadLetterProducerFactory()),
                (record, ex) -> new TopicPartition(record.topic() + DLT_SUFFIX, record.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
    }

    /**
     * Producer for dead letters (a bean only so it is closed on shutdown; the template built on it is
     * deliberately not a bean, so it never competes with {@link #kafkaTemplate()} for injection).
     * Values of records that failed deserialization arrive as the raw {@code byte[]} and are forwarded
     * unchanged; records that failed in the listener carry the deserialized event and are re-serialized
     * as JSON.
     */
    @Bean
    public DefaultKafkaProducerFactory<Object, Object> deadLetterProducerFactory() {
        Map<Class<?>, Serializer<?>> valueSerializers = new LinkedHashMap<>();
        valueSerializers.put(byte[].class, new ByteArraySerializer());
        valueSerializers.put(Object.class, eventSerializer());

        // The delegating serializer forwards configs to its delegates; a JSON serializer configured via
        // setters rejects JSON config properties, so producerProps() has none.
        Map<String, Object> props = producerProps();
        return new DefaultKafkaProducerFactory<>(props,
                objectSerializer(new StringSerializer()),
                new DelegatingByTypeSerializer(valueSerializers, true));
    }

    @SuppressWarnings("unchecked")
    private static Serializer<Object> objectSerializer(Serializer<?> serializer) {
        return (Serializer<Object>) serializer;
    }

    // ── Consumer factories ────────────────────────────────────────────────────
    private <T> ConcurrentKafkaListenerContainerFactory<String, T> containerFactory(Class<T> type, String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // ErrorHandlingDeserializer turns a malformed payload into a DeserializationException handled
        // by the error handler (-> DLT) instead of an endless poll/fail loop on the same offset.
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        // JacksonJsonDeserializer = Spring Kafka 4's Jackson 3 successor of JsonDeserializer (same spring.json.*
        // properties). Its default mapper ignores unknown properties and reads both the decimal and the ISO form of
        // occurredAt, so events written before any later field addition still deserialize.
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JacksonJsonDeserializer.class);
        props.put(JacksonJsonDeserializer.TRUSTED_PACKAGES, "com.socialapp.*");
        props.put(JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, type.getName());
        props.put(JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        var factory = new ConcurrentKafkaListenerContainerFactory<String, T>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
        factory.setCommonErrorHandler(kafkaErrorHandler());
        return factory;
    }

    // One consumer group per BC on user-events (group ids are set on the @KafkaListener annotations).

    /** SocialGraph: user-events. */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DomainEvent> userEventListenerContainerFactory() {
        return containerFactory(DomainEvent.class, "social-graph-group");
    }

    /** Feed: post-events -> SSE. */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PostCreatedEvent> postEventListenerContainerFactory() {
        return containerFactory(PostCreatedEvent.class, "feed-sse-group");
    }

    /** Feed: user-events -> author_profiles projection. */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DomainEvent> feedUserEventListenerContainerFactory() {
        return containerFactory(DomainEvent.class, "feed-avatar-group");
    }

    /** Feed: social-events -> follows (following timeline) and block/mute projections. */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DomainEvent> feedSocialEventListenerContainerFactory() {
        return containerFactory(DomainEvent.class, "feed-follows-group");
    }

    /** UserManagement: social-events -> user_blocks projection (people search, stage 5A). */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DomainEvent> userBlocksSocialEventListenerContainerFactory() {
        return containerFactory(DomainEvent.class, "user-blocks-group");
    }

    // Notification BC: one listener + group per topic (group ids also on the @KafkaListener annotations).

    /** Notification: user-events -> actor profile projection. */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DomainEvent> notificationUserEventListenerContainerFactory() {
        return containerFactory(DomainEvent.class, "notification-users-group");
    }

    /** Notification: social-events -> FOLLOW, block/mute projection. */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DomainEvent> notificationSocialEventListenerContainerFactory() {
        return containerFactory(DomainEvent.class, "notification-social-group");
    }

    /** Notification: post-events -> MENTION / REPOST / QUOTE. */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DomainEvent> notificationPostEventListenerContainerFactory() {
        return containerFactory(DomainEvent.class, "notification-posts-group");
    }

    /** Notification: reaction-events -> LIKE. */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DomainEvent> notificationReactionEventListenerContainerFactory() {
        return containerFactory(DomainEvent.class, "notification-reactions-group");
    }

    /** Notification: comment-events -> COMMENT / REPLY. */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DomainEvent> notificationCommentEventListenerContainerFactory() {
        return containerFactory(DomainEvent.class, "notification-comments-group");
    }
}
