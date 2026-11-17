package com.socialapp.feed.application.timeline;

import com.socialapp.feed.application.usecase.FollowProjection;
import com.socialapp.feed.domain.Follow;
import com.socialapp.feed.domain.FollowRepository;
import com.socialapp.feed.domain.Post;
import com.socialapp.shared.events.UserFollowedEvent;
import com.socialapp.shared.events.UserUnfollowedEvent;
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

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keyset pagination SQL, the following-scope query and the follows projection's newest-wins upsert against a
 * real PostgreSQL with the Flyway schema (V15/V16 incl. entity validation). Each test runs in a rolled-back
 * transaction.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import({TimelineService.class, FollowProjection.class})
class TimelinePostgresTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final Instant T0 = Instant.parse("2026-11-14T10:00:00.123456Z");
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();

    @Autowired TimelineService timeline;
    @Autowired FollowProjection projection;
    @Autowired FollowRepository follows;
    @Autowired JdbcTemplate jdbc;

    private UUID post(UUID author, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO posts (id, author_id, author_username, content, created_at, updated_at)
                VALUES (?, ?, 'u', 'c', ?, ?)""", id, author, createdAt.atOffset(ZoneOffset.UTC),
                createdAt.atOffset(ZoneOffset.UTC));
        return id;
    }

    /** Pages through the whole timeline with the given size, as a client would (opaque cursor round trip). */
    private List<UUID> pageThrough(UUID viewer, FeedScope scope, int size, Runnable betweenPages) {
        List<UUID> seen = new ArrayList<>();
        FeedCursor cursor = null;
        for (int i = 0; i < 100; i++) {
            var page = timeline.page(viewer, scope, cursor, size);
            assertThat(page.posts().size()).isLessThanOrEqualTo(size);
            page.posts().forEach(p -> seen.add(p.getId()));
            if (page.next() == null) return seen;
            assertThat(page.posts()).hasSize(size); // only full pages carry a cursor
            cursor = FeedCursor.decode(page.next().encode());
            betweenPages.run();
        }
        throw new AssertionError("pagination did not terminate");
    }

    private List<UUID> expectedOrder(String where) {
        return jdbc.queryForList("SELECT id FROM posts " + where + " ORDER BY created_at DESC, id DESC", UUID.class);
    }

    // ── keyset pagination ────────────────────────────────────────────────────

    @Test
    void emptyTimelineIsOneEmptyLastPage() {
        var page = timeline.page(ALICE, FeedScope.ALL, null, 20);

        assertThat(page.posts()).isEmpty();
        assertThat(page.next()).isNull();
        assertThat(timeline.page(ALICE, FeedScope.FOLLOWING, null, 20).posts()).isEmpty();
    }

    @Test
    void tiesOnCreatedAtAreBrokenByIdWithoutDuplicatesOrSkips() {
        for (int i = 0; i < 7; i++) post(BOB, T0); // all at exactly the same instant
        post(BOB, T0.plusMillis(1));
        post(BOB, T0.minusMillis(1));

        for (int size : new int[] {1, 2, 3, 4, 9, 10}) {
            assertThat(pageThrough(ALICE, FeedScope.ALL, size, () -> {}))
                    .as("size %d", size)
                    .containsExactlyElementsOf(expectedOrder(""));
        }
    }

    @Test
    void exactMultipleOfSizeNeedsNoExtraEmptyPage_andLastPartialPageHasNullCursor() {
        for (int i = 0; i < 4; i++) post(BOB, T0.plusSeconds(i));

        var first = timeline.page(ALICE, FeedScope.ALL, null, 2);
        var second = timeline.page(ALICE, FeedScope.ALL, first.next(), 2);
        var partial = timeline.page(ALICE, FeedScope.ALL, null, 3);
        var rest = timeline.page(ALICE, FeedScope.ALL, partial.next(), 3);

        assertThat(first.next()).isNotNull();
        assertThat(second.posts()).hasSize(2);
        // With size+1 look-ahead the second page already knows it is the last one.
        assertThat(second.next()).isNull();
        assertThat(rest.posts()).hasSize(1);
        assertThat(rest.next()).isNull();
    }

    @Test
    void postsCreatedBetweenPagesNeitherDuplicateNorShiftTheRemainingPages() {
        for (int i = 0; i < 6; i++) post(BOB, T0.plusSeconds(i));
        var before = expectedOrder("");
        int[] n = {0};

        var seen = pageThrough(ALICE, FeedScope.ALL, 2, () -> {
            post(BOB, Instant.now().plusSeconds(60 + n[0]++)); // newer than everything: goes to the top
        });

        assertThat(seen).containsExactlyElementsOf(before);
    }

    @Test
    void cursorSurvivesDeletionOfTheLastSeenPost() {
        for (int i = 0; i < 5; i++) post(BOB, T0.plusSeconds(i));
        var all = expectedOrder("");
        var first = timeline.page(ALICE, FeedScope.ALL, null, 2);

        jdbc.update("DELETE FROM posts WHERE id = ?", first.posts().get(1).getId());
        var second = timeline.page(ALICE, FeedScope.ALL, first.next(), 2);

        assertThat(second.posts()).extracting(Post::getId).containsExactly(all.get(2), all.get(3));
    }

    // ── following scope ─────────────────────────────────────────────────────

    @Test
    void followingScopeShowsFolloweesAndOwnPostsOnly() {
        UUID own = post(ALICE, T0.plusSeconds(1));
        UUID bobs = post(BOB, T0.plusSeconds(2));
        UUID carols = post(CAROL, T0.plusSeconds(3));
        projection.on(new UserFollowedEvent(UUID.randomUUID(), T0, ALICE, BOB));
        projection.on(new UserFollowedEvent(UUID.randomUUID(), T0, CAROL, ALICE)); // reverse direction: irrelevant

        assertThat(pageThrough(ALICE, FeedScope.FOLLOWING, 1, () -> {})).containsExactly(bobs, own);
        assertThat(pageThrough(ALICE, FeedScope.ALL, 2, () -> {})).containsExactly(carols, bobs, own);
        assertThat(pageThrough(BOB, FeedScope.FOLLOWING, 5, () -> {})).containsExactly(bobs); // follows nobody
    }

    @Test
    void unfollowRemovesFolloweesPostsFromTheFollowingTimeline() {
        UUID bobs = post(BOB, T0);
        projection.on(new UserFollowedEvent(UUID.randomUUID(), T0, ALICE, BOB));
        assertThat(pageThrough(ALICE, FeedScope.FOLLOWING, 5, () -> {})).containsExactly(bobs);

        projection.on(new UserUnfollowedEvent(UUID.randomUUID(), T0.plusMillis(1), ALICE, BOB));

        assertThat(pageThrough(ALICE, FeedScope.FOLLOWING, 5, () -> {})).isEmpty();
    }

    @Test
    void followingTimelinePaginatesWithTiesToo() {
        for (int i = 0; i < 5; i++) post(BOB, T0);
        for (int i = 0; i < 3; i++) post(ALICE, T0);
        for (int i = 0; i < 4; i++) post(CAROL, T0);
        projection.on(new UserFollowedEvent(UUID.randomUUID(), T0, ALICE, BOB));

        var expected = jdbc.queryForList("SELECT id FROM posts WHERE author_id IN (?, ?) "
                + "ORDER BY created_at DESC, id DESC", UUID.class, ALICE, BOB);
        assertThat(pageThrough(ALICE, FeedScope.FOLLOWING, 3, () -> {})).containsExactlyElementsOf(expected);
    }

    // ── follows projection: idempotent, order-tolerant (newest occurredAt wins) ──

    private Follow pair(UUID follower, UUID followee) {
        return follows.findById(new Follow.Key(follower, followee)).orElseThrow();
    }

    @Test
    void redeliveredFollowIsANoOp() {
        var follow = new UserFollowedEvent(UUID.randomUUID(), T0, ALICE, BOB);

        assertThat(projection.on(follow)).isTrue();
        assertThat(projection.on(follow)).isFalse();

        assertThat(follows.findActiveFolloweeIds(ALICE)).containsExactly(BOB);
        assertThat(pair(ALICE, BOB).getFollowedAt()).isEqualTo(T0);
    }

    @Test
    void lateFollowDoesNotResurrectAnUnfollow() {
        var follow = new UserFollowedEvent(UUID.randomUUID(), T0, ALICE, BOB);
        var unfollow = new UserUnfollowedEvent(UUID.randomUUID(), T0.plusMillis(1), ALICE, BOB);

        projection.on(follow);
        projection.on(unfollow);
        assertThat(projection.on(follow)).isFalse(); // redelivered after the unfollow

        assertThat(follows.findActiveFolloweeIds(ALICE)).isEmpty();
        var tombstone = pair(ALICE, BOB);
        assertThat(tombstone.isActive()).isFalse();
        assertThat(tombstone.getFollowedAt()).isNull();
        assertThat(tombstone.getLastEventAt()).isEqualTo(T0.plusMillis(1));
    }

    @Test
    void outOfOrderDeliveryConvergesToTheNewestEvent() {
        var f1 = new UserFollowedEvent(UUID.randomUUID(), T0, ALICE, BOB);
        var u2 = new UserUnfollowedEvent(UUID.randomUUID(), T0.plusMillis(1), ALICE, BOB);
        var f3 = new UserFollowedEvent(UUID.randomUUID(), T0.plusMillis(2), ALICE, BOB);

        // Unfollow arrives before its follow (nothing to delete yet: tombstone), then the newest follow,
        // then the stale ones.
        assertThat(projection.on(u2)).isTrue();
        assertThat(follows.findActiveFolloweeIds(ALICE)).isEmpty();
        assertThat(projection.on(f3)).isTrue();
        assertThat(projection.on(f1)).isFalse();
        assertThat(projection.on(u2)).isFalse(); // late unfollow must not delete the newer follow

        assertThat(follows.findActiveFolloweeIds(ALICE)).containsExactly(BOB);
        assertThat(pair(ALICE, BOB).getFollowedAt()).isEqualTo(T0.plusMillis(2));
    }

    @Test
    void pairsAreIndependent() {
        projection.on(new UserFollowedEvent(UUID.randomUUID(), T0.plusSeconds(10), ALICE, BOB));
        projection.on(new UserFollowedEvent(UUID.randomUUID(), T0, ALICE, CAROL)); // older, other pair: applies

        assertThat(follows.findActiveFolloweeIds(ALICE)).containsExactlyInAnyOrder(BOB, CAROL);
    }

    // ── full-text search (V23) ───────────────────────────────────────────────

    private UUID postWithText(String content, String type, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO posts (id, author_id, author_username, content, type, created_at, updated_at)
                VALUES (?, ?, 'u', ?, ?, ?, ?)""", id, BOB, content, type, createdAt.atOffset(ZoneOffset.UTC),
                createdAt.atOffset(ZoneOffset.UTC));
        return id;
    }

    private List<UUID> search(String q) {
        return timeline.searchPage(q, null, 100).posts().stream().map(Post::getId).toList();
    }

    @Test
    void searchMatchesWordsCaseInsensitivelyNewestFirst() {
        UUID older = postWithText("Learning Kafka and Spring", "POST", T0);
        UUID newer = postWithText("kafka is fun", "POST", T0.plusSeconds(5));
        postWithText("nothing relevant here", "POST", T0.plusSeconds(6));

        assertThat(search("KAFKA")).containsExactly(newer, older);
        assertThat(search("kafka spring")).containsExactly(older); // words are ANDed
        assertThat(search("kafka or fun")).containsExactly(newer, older);
        assertThat(search("kafka -spring")).containsExactly(newer);
        assertThat(search("\"kafka is\"")).containsExactly(newer);
        assertThat(search("absent")).isEmpty();
    }

    @Test
    void searchIgnoresRepostsAndFindsQuoteComments() {
        UUID quote = postWithText("my take on kafka", "QUOTE", T0);
        // A REPOST has no text of its own (V18: content = '') and points at its original.
        jdbc.update("""
                INSERT INTO posts (id, author_id, author_username, content, type, original_post_id, created_at, updated_at)
                VALUES (?, ?, 'u', '', 'REPOST', ?, ?, ?)""", UUID.randomUUID(), CAROL, quote,
                T0.plusSeconds(1).atOffset(ZoneOffset.UTC), T0.plusSeconds(1).atOffset(ZoneOffset.UTC));

        assertThat(search("kafka")).containsExactly(quote);
    }

    @Test
    void searchTreatsAHashtagAsItsWordAndSurvivesHostileInput() {
        UUID tagged = postWithText("Shipping #Kafka today", "POST", T0);

        assertThat(search("#kafka")).containsExactly(tagged);
        // never a syntax error, whatever the user typed
        for (String q : new String[] {"!!!", "&|:*", "'", "\"", "a & (b", "-", "((("}) {
            assertThat(search(q)).as(q).isEmpty();
        }
    }

    @Test
    void searchPagesWithTheSameKeysetCursorAsTheTimeline() {
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 7; i++) expected.add(postWithText("kafka " + i, "POST", T0)); // ties on created_at
        postWithText("other", "POST", T0);
        expected = jdbc.queryForList("""
                SELECT id FROM posts WHERE content LIKE 'kafka%' ORDER BY created_at DESC, id DESC""", UUID.class);

        List<UUID> seen = new ArrayList<>();
        FeedCursor cursor = null;
        for (int i = 0; i < 20; i++) {
            var page = timeline.searchPage("kafka", cursor == null ? null : FeedCursor.decode(cursor.encode()), 3);
            page.posts().forEach(p -> seen.add(p.getId()));
            if (page.next() == null) break;
            assertThat(page.posts()).hasSize(3);
            cursor = page.next();
        }
        assertThat(seen).containsExactlyElementsOf(expected);
    }

    @Test
    void searchRejectsBlankOrOverlongQueriesAndBadSizes() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> timeline.searchPage("   ", null, 20))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> timeline.searchPage(null, null, 20))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> timeline.searchPage("x".repeat(TimelineService.MAX_QUERY_LENGTH + 1), null, 20))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> timeline.searchPage("kafka", null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(timeline.searchPage("x".repeat(TimelineService.MAX_QUERY_LENGTH), null, 20).posts()).isEmpty();
    }
}
