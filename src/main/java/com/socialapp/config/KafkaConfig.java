package com.socialapp.config;

import com.socialapp.shared.events.*;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${kafka.topics.user-events}")
    private String userEventsTopic;

    @Value("${kafka.topics.post-events}")
    private String postEventsTopic;

    @Value("${kafka.topics.reaction-events}")
    private String reactionEventsTopic;

    // ── Topics ────────────────────────────────────────────────────────────────
    @Bean
    public NewTopic userEventsTopic() {
        return TopicBuilder.name(userEventsTopic).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic postEventsTopic() {
        return TopicBuilder.name(postEventsTopic).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic reactionEventsTopic() {
        return TopicBuilder.name(reactionEventsTopic).partitions(3).replicas(1).build();
    }

    // ── Producer ──────────────────────────────────────────────────────────────
    @Bean
    public ProducerFactory<String, DomainEvent> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, DomainEvent> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // ── Consumer factories ────────────────────────────────────────────────────
    private <T> ConcurrentKafkaListenerContainerFactory<String, T> containerFactory(Class<T> type, String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.socialapp.*");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, type.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        var factory = new ConcurrentKafkaListenerContainerFactory<String, T>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DomainEvent> userEventListenerContainerFactory() {
        return containerFactory(DomainEvent.class, "social-graph-group");
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DomainEvent> userUpdatedEventListenerContainerFactory() {
        return containerFactory(DomainEvent.class, "social-graph-update-group");
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PostCreatedEvent> postEventListenerContainerFactory() {
        return containerFactory(PostCreatedEvent.class, "feed-sse-group");
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DomainEvent> feedUserEventListenerContainerFactory() {
        return containerFactory(DomainEvent.class, "feed-avatar-group");
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DomainEvent> feedUserUpdatedEventListenerContainerFactory() {
        return containerFactory(DomainEvent.class, "feed-avatar-update-group");
    }
}
