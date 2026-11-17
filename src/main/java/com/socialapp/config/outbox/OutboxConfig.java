package com.socialapp.config.outbox;

import com.socialapp.shared.events.DomainEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

/**
 * Transactional outbox wiring (generic, BC-neutral): {@link OutboxEventWriter} stores domain events in
 * the aggregate's transaction, {@link OutboxRelay} publishes them to Kafka, {@link OutboxCleanup} purges
 * old published rows. Scheduling runs on Spring Boot's auto-configured {@code taskScheduler}
 * (single thread by default), which also executes the post-commit wake-ups of the relay.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig {

    @Bean
    public DomainEventCodec domainEventCodec() {
        return new DomainEventCodec();
    }

    /** The primary (JPA) transaction manager: the outbox lives in PostgreSQL, never in Neo4j. */
    @Bean
    public OutboxRelay outboxRelay(OutboxRepository repository, KafkaTemplate<String, DomainEvent> kafkaTemplate,
                                   PlatformTransactionManager transactionManager, DomainEventCodec codec,
                                   OutboxProperties properties, TaskScheduler taskScheduler) {
        return new OutboxRelay(repository, kafkaTemplate, new TransactionTemplate(transactionManager), codec,
                properties, task -> taskScheduler.schedule(task, Instant.now()));
    }

    @Bean
    public OutboxImporter outboxImporter(OutboxRepository repository, OutboxEventWriter writer, OutboxRelay relay,
                                         PlatformTransactionManager transactionManager) {
        return new OutboxImporter(repository, writer, relay, new TransactionTemplate(transactionManager));
    }

    @Bean
    public OutboxCleanup outboxCleanup(OutboxRepository repository, OutboxProperties properties) {
        return new OutboxCleanup(repository, properties, Clock.systemUTC());
    }
}
