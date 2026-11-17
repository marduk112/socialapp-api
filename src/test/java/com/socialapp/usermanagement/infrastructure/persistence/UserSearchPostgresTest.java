package com.socialapp.usermanagement.infrastructure.persistence;

import com.socialapp.usermanagement.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** People-search SQL (V24: pg_trgm index, ranking, LIKE escaping) against a real PostgreSQL with the Flyway schema. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import(UserPersistenceAdapter.class)
class UserSearchPostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired UserPersistenceAdapter users;

    private void user(String username) {
        users.save(User.register(username, username.toLowerCase() + "@test.com", "hash", null));
    }

    private List<String> search(String term, int limit) {
        return users.searchByUsername(term, limit).stream().map(User::getUsername).toList();
    }

    @Test
    void ranksExactThenPrefixThenContainsCaseInsensitively() {
        user("big_alice");
        user("Alicia");
        user("alice");
        user("alice2");
        user("bob");

        assertThat(search("ALICE", 10)).containsExactly("alice", "alice2", "big_alice");
        assertThat(search("ali", 10)).containsExactly("Alicia", "alice", "alice2", "big_alice");
        assertThat(search("zzz", 10)).isEmpty();
    }

    @Test
    void honoursTheLimit() {
        for (int i = 0; i < 5; i++) user("carol" + i);

        assertThat(search("carol", 3)).containsExactly("carol0", "carol1", "carol2");
    }

    @Test
    void likeWildcardsInTheTermAreLiteral() {
        user("a_b");
        user("axb");
        user("100%");
        user("1000");
        user("back\\slash");

        assertThat(search("a_b", 10)).containsExactly("a_b");
        assertThat(search("%", 10)).containsExactly("100%");
        assertThat(search("_", 10)).containsExactly("a_b");
        assertThat(search("\\", 10)).containsExactly("back\\slash");
    }

    @Test
    void neverExposesAnythingBeyondTheMatchedUsers() {
        user("dave");

        var found = users.searchByUsername("dav", 10);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getUsername()).isEqualTo("dave");
    }
}
