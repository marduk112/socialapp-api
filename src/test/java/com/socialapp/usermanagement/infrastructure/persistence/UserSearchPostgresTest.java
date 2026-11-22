package com.socialapp.usermanagement.infrastructure.persistence;

import com.socialapp.shared.events.UserBlockedEvent;
import com.socialapp.shared.events.UserUnblockedEvent;
import com.socialapp.usermanagement.application.usecase.UserBlockProjection;
import com.socialapp.usermanagement.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** People-search SQL (V24: pg_trgm index, ranking, LIKE escaping) against a real PostgreSQL with the Flyway schema. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import({UserPersistenceAdapter.class, JdbcUserBlockRepository.class, UserBlockProjection.class})
class UserSearchPostgresTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired UserPersistenceAdapter users;
    @Autowired UserBlockProjection blocks;

    /** A searcher without any block. */
    private static final UUID VIEWER = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");

    private User user(String username) {
        return users.save(User.register(username, username.toLowerCase() + "@test.com", "hash", null));
    }

    private List<String> search(String term, int limit) {
        return search(term, VIEWER, limit);
    }

    private List<String> search(String term, UUID viewer, int limit) {
        return users.searchByUsername(term, viewer, limit).stream().map(User::getUsername).toList();
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

        var found = users.searchByUsername("dav", VIEWER, 10);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getUsername()).isEqualTo("dave");
    }

    // ── Blocks (stage 5A) ───────────────────────────────────────────────────

    @Test
    void blockedUsersAreHiddenFromBothSides_untilUnblocked() {
        var alice = user("zed_alice");
        var bob = user("zed_bob");
        user("zed_carol");

        blocks.on(new UserBlockedEvent(UUID.randomUUID(), T0, alice.getId(), bob.getId()));

        assertThat(search("zed", alice.getId(), 10)).containsExactly("zed_alice", "zed_carol");
        assertThat(search("zed", bob.getId(), 10)).containsExactly("zed_bob", "zed_carol");
        assertThat(search("zed", 10)).containsExactly("zed_alice", "zed_bob", "zed_carol");

        blocks.on(new UserUnblockedEvent(UUID.randomUUID(), T0.plusSeconds(1), alice.getId(), bob.getId()));

        assertThat(search("zed", alice.getId(), 10)).containsExactly("zed_alice", "zed_bob", "zed_carol");
        assertThat(search("zed", bob.getId(), 10)).containsExactly("zed_alice", "zed_bob", "zed_carol");
    }

    @Test
    void blockProjectionIsIdempotentAndOrderTolerant() {
        var alice = user("yy_alice");
        var bob = user("yy_bob");
        var block = new UserBlockedEvent(UUID.randomUUID(), T0, alice.getId(), bob.getId());
        var unblock = new UserUnblockedEvent(UUID.randomUUID(), T0.plusSeconds(1), alice.getId(), bob.getId());

        // newest first, then the older block and a redelivery: the unblock (tombstone) wins
        assertThat(blocks.on(unblock)).isTrue();
        assertThat(blocks.on(block)).isFalse();
        assertThat(blocks.on(unblock)).isFalse();
        assertThat(search("yy", alice.getId(), 10)).containsExactly("yy_alice", "yy_bob");

        // a newer block applies; its redelivery does not
        var again = new UserBlockedEvent(UUID.randomUUID(), T0.plusSeconds(2), alice.getId(), bob.getId());
        assertThat(blocks.on(again)).isTrue();
        assertThat(blocks.on(again)).isFalse();
        assertThat(search("yy", alice.getId(), 10)).containsExactly("yy_alice");
        // malformed (self) events are dropped
        assertThat(blocks.on(new UserBlockedEvent(UUID.randomUUID(), T0, alice.getId(), alice.getId()))).isFalse();
    }
}
