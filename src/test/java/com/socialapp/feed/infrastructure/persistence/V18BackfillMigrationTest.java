package com.socialapp.feed.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V18 backfill of legacy "shares" on a database migrated to V17 and seeded with pre-2C rows: classification
 * (blank -> REPOST, text -> QUOTE), flattening of shares of shares, orphaned shares, duplicate blank shares of the
 * same original, image/content normalisation, and that the new constraints and indexes are in place afterwards.
 */
@Testcontainers(disabledWithoutDocker = true)
class V18BackfillMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();

    private JdbcTemplate jdbc;

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    /** A pre-V18 row; a share copies the original's author/content/image like the old Post.share did. */
    private UUID legacy(UUID author, String content, UUID originalId, int secondsAfterT0, String image) {
        UUID id = UUID.randomUUID();
        var at = T0.plusSeconds(secondsAfterT0).atOffset(ZoneOffset.UTC);
        String origUser = null, origContent = null;
        if (originalId != null) {
            var o = jdbc.queryForMap("SELECT author_username, content FROM posts WHERE id = ?", originalId);
            origUser = (String) o.get("author_username");
            origContent = (String) o.get("content");
        }
        jdbc.update("""
                INSERT INTO posts (id, author_id, author_username, content, image_url, created_at, updated_at,
                                   original_post_id, original_author_username, original_content)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, author, "u" + author.toString().substring(0, 4), content, image, at, at,
                originalId, origUser, origContent);
        return id;
    }

    private Map<String, Object> row(UUID id) {
        var rows = jdbc.queryForList("SELECT * FROM posts WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Test
    void backfillClassifiesFlattensDeduplicatesAndCleansLegacyShares() {
        flyway("17").migrate();
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);

        UUID original = legacy(BOB, "the original", null, 0, "img.png");
        UUID plain = legacy(CAROL, "just a post", null, 1, null);
        // ALICE shared it blank three times (double clicks): oldest kept as REPOST, others deleted
        UUID aliceRepost = legacy(ALICE, "", original, 10, "img.png");
        UUID aliceDup1 = legacy(ALICE, "   ", original, 11, "img.png");
        UUID aliceDup2 = legacy(ALICE, "", original, 12, "img.png");
        jdbc.update("INSERT INTO comments (id, post_id, author_id, author_username, content, created_at) "
                + "VALUES (?, ?, ?, 'c', 'on a duplicate', now())", UUID.randomUUID(), aliceDup1, CAROL);
        // a share with a comment -> QUOTE (keeps text and snapshot, loses the copied image)
        UUID carolQuote = legacy(CAROL, "great #post", original, 20, "img.png");
        // shares of ALICE's blank share: flattened to the original
        UUID carolRepostOfRepost = legacy(CAROL, "", aliceRepost, 30, "img.png");
        UUID bobQuoteOfRepost = legacy(BOB, "via alice", aliceRepost, 31, "img.png");
        // CAROL also shared the original directly later: after flattening that is a duplicate of the above
        UUID carolDirectRepost = legacy(CAROL, "", original, 32, "img.png");
        // a share of a quote stays pointed at the quote
        UUID aliceRepostOfQuote = legacy(ALICE, "", carolQuote, 40, null);
        // shares whose original was deleted before V18 (FK set original_post_id NULL, snapshot left)
        UUID gone = legacy(BOB, "deleted later", null, 50, null);
        UUID orphanBlank = legacy(ALICE, "", gone, 51, null);
        UUID orphanQuote = legacy(CAROL, "about a deleted post", gone, 52, null);
        UUID shareOfOrphanBlank = legacy(BOB, "", orphanBlank, 53, null);
        jdbc.update("DELETE FROM posts WHERE id = ?", gone);
        assertThat(row(orphanBlank).get("original_post_id")).isNull();

        flyway("18").migrate();

        assertThat(row(original).get("type")).isEqualTo("POST");
        assertThat(row(original).get("image_url")).isEqualTo("img.png");
        assertThat(row(plain).get("type")).isEqualTo("POST");

        var repost = row(aliceRepost);
        assertThat(repost.get("type")).isEqualTo("REPOST");
        assertThat(repost.get("content")).isEqualTo("");
        assertThat(repost.get("image_url")).isNull();
        assertThat(row(aliceDup1)).isNull();
        assertThat(row(aliceDup2)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comments WHERE post_id = ?", Long.class, aliceDup1)).isZero();

        var quote = row(carolQuote);
        assertThat(quote.get("type")).isEqualTo("QUOTE");
        assertThat(quote.get("content")).isEqualTo("great #post");
        assertThat(quote.get("image_url")).isNull();
        assertThat(quote.get("original_post_id")).isEqualTo(original);
        assertThat(quote.get("original_content")).isEqualTo("the original");

        // flattened: the oldest of CAROL's two reposts of the original survives
        var flattened = row(carolRepostOfRepost);
        assertThat(flattened.get("type")).isEqualTo("REPOST");
        assertThat(flattened.get("original_post_id")).isEqualTo(original);
        assertThat(flattened.get("original_content")).isEqualTo("the original"); // snapshot of the real target
        assertThat(row(carolDirectRepost)).isNull();
        var flattenedQuote = row(bobQuoteOfRepost);
        assertThat(flattenedQuote.get("type")).isEqualTo("QUOTE");
        assertThat(flattenedQuote.get("original_post_id")).isEqualTo(original);
        assertThat(flattenedQuote.get("original_author_username")).isEqualTo(row(original).get("author_username"));

        assertThat(row(aliceRepostOfQuote).get("type")).isEqualTo("REPOST");
        assertThat(row(aliceRepostOfQuote).get("original_post_id")).isEqualTo(carolQuote);

        // original gone: reposts deleted (also the one pointing at a deleted repost), quote kept without snapshot
        assertThat(row(orphanBlank)).isNull();
        assertThat(row(shareOfOrphanBlank)).isNull();
        var orphan = row(orphanQuote);
        assertThat(orphan.get("type")).isEqualTo("QUOTE");
        assertThat(orphan.get("original_post_id")).isNull();
        assertThat(orphan.get("original_content")).isNull();
        assertThat(orphan.get("original_author_username")).isNull();

        // invariants hold for every row, and the new indexes exist
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posts p JOIN posts t ON t.id = p.original_post_id "
                + "WHERE t.type = 'REPOST'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT author_id, original_post_id FROM posts WHERE type = 'REPOST'
                                      GROUP BY 1, 2 HAVING count(*) > 1) d""", Long.class)).isZero();
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'posts'", String.class))
                .contains("ux_posts_repost_author_original", "ix_posts_original_type")
                .doesNotContain("idx_posts_original");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname IN "
                + "('chk_posts_type', 'chk_posts_type_original')", Long.class)).isEqualTo(2);
    }
}
