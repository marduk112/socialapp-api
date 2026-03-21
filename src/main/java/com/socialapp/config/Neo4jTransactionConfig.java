package com.socialapp.config;

import jakarta.persistence.EntityManagerFactory;
import org.neo4j.driver.Driver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.neo4j.core.transaction.Neo4jTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;

/**
 * Explicit dual-transaction-manager configuration.
 *
 * Spring Boot's JpaTransactionManagerAutoConfiguration is conditional on
 * @ConditionalOnMissingBean(TransactionManager.class). Once we declare the
 * Neo4j TM ourselves, Boot skips auto-creating the JPA one, leaving
 * plain @Transactional with no "transactionManager" bean to bind to.
 *
 * Solution: declare BOTH managers here.
 *   - jpaTransactionManager (@Primary, name "transactionManager") — used by
 *     all plain @Transactional annotations and Spring Data JPA repositories.
 *   - neo4jTransactionManager — used explicitly via
 *     @Transactional("neo4jTransactionManager") in SocialGraphService.
 */
@Configuration
public class Neo4jTransactionConfig {

    @Primary
    @Bean("transactionManager")
    public JpaTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    @Bean("neo4jTransactionManager")
    public Neo4jTransactionManager neo4jTransactionManager(Driver driver) {
        return new Neo4jTransactionManager(driver);
    }
}
