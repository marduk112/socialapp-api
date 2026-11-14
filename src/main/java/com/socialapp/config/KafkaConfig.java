package com.socialapp.config;

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
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
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

    /** Follow graph changes (UserFollowed/UserUnfollowed), key = followerId. */
    @Bean
    public NewTopic socialEventsTopic() {
        return TopicBuilder.name(socialEventsTopic).partitions(PARTITIONS).replicas(1).build();
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

    // ── Producer ──────────────────────────────────────────────────────────────
    private Map<String, Object> producerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
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

    @Bean
    public ProducerFactory<String, DomainEvent> producerFactory() {
        return new DefaultKafkaProducerFactory<>(producerProps());
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
        var json = new JsonSerializer<>();
        json.setAddTypeInfo(false);
        valueSerializers.put(Object.class, json);

        // The delegating serializer forwards configs to its delegates; a JsonSerializer configured via
        // setters rejects JSON config properties, so they must not be present here.
        Map<String, Object> props = producerProps();
        props.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
        props.remove(JsonSerializer.ADD_TYPE_INFO_HEADERS);
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
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.socialapp.*");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, type.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

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

    /** Feed: social-events -> follows projection (following timeline). */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DomainEvent> feedSocialEventListenerContainerFactory() {
        return containerFactory(DomainEvent.class, "feed-follows-group");
    }
}
