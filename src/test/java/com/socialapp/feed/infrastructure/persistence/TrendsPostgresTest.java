package com.socialapp.feed.infrastructure.persistence;

import com.socialapp.feed.application.trends.TrendsService;
import com.socialapp.feed.domain.TrendingHashtag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The trending aggregate (V25 index, ranking, window) against a real PostgreSQL with the Flyway schema. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import({JdbcPostHashtagRepository.class, TrendsService.class})
class TrendsPostgresTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired TrendsService trends;
    @Autowired JdbcTemplate jdbc;

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();

    /** A post by {@code author} created {@code ago} before now, carrying {@code tags}. */
    private void post(UUID author, Duration ago, String... tags) {
        UUID id = UUID.randomUUID();
        var at = Instant.now().minus(ago).atOffset(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO posts (id, author_id, author_username, content, created_at, updated_at)
                VALUES (?, ?, 'u', 'c', ?, ?)""", id, author, at, at);
        for (int i = 0; i < tags.length; i++) {
            jdbc.update("INSERT INTO post_hashtags (post_id, tag, position, created_at) VALUES (?, ?, ?, ?)",
                    id, tags[i], (short) i, at);
        }
    }

    private List<String> tags(int hours, int limit) {
        return trends.trendingHashtags(hours, limit).stream().map(TrendingHashtag::tag).toList();
    }

    @Test
    void nothingRecentMeansNoTrends() {
        post(A, Duration.ofDays(3), "old");

        assertThat(trends.trendingHashtags(24, 10)).isEmpty();
    }

    @Test
    void ranksByDistinctAuthorsThenPostsThenTag() {
        post(A, Duration.ofHours(1), "spam");
        post(A, Duration.ofHours(1), "spam");
        post(A, Duration.ofHours(2), "spam");          // 3 posts, 1 author
        post(A, Duration.ofHours(1), "popular");
        post(B, Duration.ofHours(2), "popular");       // 2 posts, 2 authors -> first
        post(C, Duration.ofHours(3), "alpha", "beta"); // 1 post, 1 author each
        post(A, Duration.ofHours(3), "zeta");          // 1 post, 1 author

        var result = trends.trendingHashtags(24, 10);

        assertThat(result).containsExactly(
                new TrendingHashtag("popular", 2, 2),
                new TrendingHashtag("spam", 3, 1),
                new TrendingHashtag("alpha", 1, 1),
                new TrendingHashtag("beta", 1, 1),
                new TrendingHashtag("zeta", 1, 1));
    }

    @Test
    void onlyThePostsInsideTheWindowCount() {
        post(A, Duration.ofHours(1), "fresh");
        post(B, Duration.ofHours(30), "fresh");   // outside 24 h
        post(B, Duration.ofHours(30), "stale");

        assertThat(trends.trendingHashtags(24, 10)).containsExactly(new TrendingHashtag("fresh", 1, 1));
        assertThat(tags(48, 10)).containsExactly("fresh", "stale");
    }

    @Test
    void limitIsApplied() {
        for (int i = 0; i < 5; i++) post(A, Duration.ofHours(1), "t" + i);

        assertThat(tags(24, 3)).containsExactly("t0", "t1", "t2");
    }
}
