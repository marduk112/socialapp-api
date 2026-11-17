package com.socialapp.feed.application.usecase;

import com.socialapp.config.backfill.BackfillStateRepository;
import com.socialapp.config.backfill.StartupBackfill;
import com.socialapp.feed.domain.AuthorProfile;
import com.socialapp.feed.domain.AuthorProfileRepository;
import com.socialapp.feed.domain.Mention;
import com.socialapp.feed.domain.PostHashtagRepository;
import com.socialapp.feed.domain.PostMentionRepository;
import com.socialapp.feed.domain.PostRepository;
import com.socialapp.feed.infrastructure.persistence.TextIndexAdapters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PostTextIndexBackfill} against a real PostgreSQL (Flyway schema up to V19): legacy rows are inserted with
 * plain SQL (as they were before stage 2B: no index rows), then the backfill runs with its own transactions.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import({PostService.class, TextIndexAdapters.class, BackfillStateRepository.class, com.socialapp.feed.infrastructure.media.MediaAdapters.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED) // the backfill commits its own batches
class PostTextIndexBackfillPostgresTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final Instant T0 = Instant.parse("2025-06-01T10:00:00.123456Z");
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();
    private static final UUID DAVE = UUID.randomUUID(); // a registered user WITHOUT a Feed author profile

    @Autowired PostService postService;
    @Autowired PostRepository posts;
    @Autowired PostHashtagRepository hashtags;
    @Autowired PostMentionRepository mentions;
    @Autowired AuthorProfileRepository profiles;
    @Autowired BackfillStateRepository state;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    private int seq;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM posts");
        jdbc.update("DELETE FROM author_profiles");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM backfill_state");
        profiles.save(new AuthorProfile(ALICE, "alice", null, null));
        profiles.save(new AuthorProfile(BOB, "Bob", null, null));
        profiles.save(new AuthorProfile(CAROL, "carol.x", null, null));
        jdbc.update("INSERT INTO users (id, username, email, created_at, updated_at) VALUES (?, 'dave', 'd@x.io', now(), now())",
                DAVE);
    }

    private PostTextIndexBackfill backfill(int batchSize) {
        return new PostTextIndexBackfill(posts, hashtags, mentions, profiles, state,
                new TransactionTemplate(transactionManager), batchSize);
    }

    /** A row as written before stage 2B: no post_hashtags/post_mentions rows. */
    private UUID legacy(UUID author, String content, String type, UUID originalId) {
        UUID id = UUID.randomUUID();
        var at = T0.plusSeconds(seq++).atOffset(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO posts (id, author_id, author_username, content, created_at, updated_at, type, original_post_id)
                VALUES (?, ?, 'legacy', ?, ?, ?, ?, ?)""", id, author, content, at, at, type, originalId);
        return id;
    }

    private UUID legacy(UUID author, String content) {
        return legacy(author, content, "POST", null);
    }

    private List<String> tagsOf(UUID postId) {
        return jdbc.queryForList("SELECT tag FROM post_hashtags WHERE post_id = ? ORDER BY position", String.class, postId);
    }

    private List<Mention> mentionsOf(UUID postId) {
        return mentions.findByPostIds(List.of(postId)).getOrDefault(postId, List.of());
    }

    private long indexRows() {
        return jdbc.queryForObject("SELECT (SELECT count(*) FROM post_hashtags) + (SELECT count(*) FROM post_mentions)",
                Long.class);
    }

    private BackfillStateRepository.State stateRow() {
        return state.find(PostTextIndexBackfill.NAME).orElseThrow();
    }

    @Test
    void legacyPostsGetTagsAndMentions_repostsSkipped_editedPostsUntouched_mentionsOnlyFromAuthorProfiles() {
        UUID p1 = legacy(ALICE, "#Hello world #hello #Zażółć @bob @CAROL.X. @ghost @alice @dave");
        UUID plain = legacy(BOB, "no tags here, a@b.com, #123");
        UUID quote = legacy(CAROL, "so #true @Alice", "QUOTE", p1);
        UUID repost = legacy(BOB, "", "REPOST", p1);
        for (int i = 0; i < 7; i++) legacy(BOB, "tagless legacy post " + i); // several batches of 3
        // Written after 2B through the service (rows exist), then its text changed behind the index's back: the
        // backfill must leave posts that already have rows alone, whatever their text says.
        UUID edited = postService.createPost(ALICE, "alice", "#fresh @bob", null).getId();
        postService.updatePost(edited, ALICE, "#edited @carol.x", null);
        jdbc.update("UPDATE posts SET content = '#tampered' WHERE id = ?", edited);

        var outcome = backfill(3).run(() -> false);

        assertThat(outcome.alreadyDone()).isFalse();
        assertThat(outcome.stopped()).isFalse();
        assertThat(outcome.items()).isEqualTo(10); // p1, plain, quote, 7 tagless; never the repost or the edited post
        assertThat(tagsOf(p1)).containsExactly("hello", "zażółć");
        // case-insensitive, canonical names; unknown (@ghost), self (@alice) and users without a Feed profile
        // (@dave exists only in UserManagement's table) are not mentions
        assertThat(mentionsOf(p1)).containsExactly(new Mention(BOB, "Bob"), new Mention(CAROL, "carol.x"));
        assertThat(tagsOf(quote)).containsExactly("true");
        assertThat(mentionsOf(quote)).containsExactly(new Mention(ALICE, "alice"));
        assertThat(tagsOf(plain)).isEmpty();
        assertThat(mentionsOf(plain)).isEmpty();
        assertThat(tagsOf(repost)).isEmpty();
        assertThat(mentionsOf(repost)).isEmpty();
        assertThat(tagsOf(edited)).containsExactly("edited");
        assertThat(mentionsOf(edited)).containsExactly(new Mention(CAROL, "carol.x"));
        // same as create/edit: created_at copied from the post row (hashtag timeline keyset)
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM post_hashtags h JOIN posts p ON p.id = h.post_id
                WHERE h.created_at <> p.created_at""", Long.class)).isZero();
        assertThat(outcome.written()).isEqualTo(2 + 2 + 1 + 1);
        var row = stateRow();
        assertThat(row.completed()).isTrue();
        assertThat(row.processed()).isEqualTo(10);

        // Second run: marker present -> no work at all, nothing changes
        long rows = indexRows();
        var again = backfill(3).run(() -> false);
        assertThat(again.alreadyDone()).isTrue();
        assertThat(indexRows()).isEqualTo(rows);
        assertThat(stateRow().processed()).isEqualTo(10);
    }

    @Test
    void stoppedRunResumesFromTheStoredCursor_taglessPostsAreNeverRescanned() {
        List<UUID> tagged = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            legacy(BOB, "plain " + i);
            tagged.add(legacy(ALICE, "#tag" + i + " @bob"));
        }

        AtomicInteger checks = new AtomicInteger();
        var first = backfill(4).run(() -> checks.incrementAndGet() > 1); // one batch, then "shutdown"
        assertThat(first.stopped()).isTrue();
        assertThat(stateRow().completed()).isFalse();
        assertThat(stateRow().processed()).isEqualTo(4);
        assertThat(stateRow().cursor()).isNotNull();

        var second = backfill(4).run(() -> false);
        assertThat(second.stopped()).isFalse();
        assertThat(second.items()).isEqualTo(6); // only what the first run had not passed
        assertThat(stateRow().processed()).isEqualTo(10);
        assertThat(stateRow().completed()).isTrue();
        for (int i = 0; i < tagged.size(); i++) {
            assertThat(tagsOf(tagged.get(i))).containsExactly("tag" + i);
            assertThat(mentionsOf(tagged.get(i))).containsExactly(new Mention(BOB, "Bob"));
        }
    }

    @Test
    void twoInstancesAtOnceShareTheWorkWithoutDoingAnythingTwice() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 60; i++) ids.add(legacy(i % 2 == 0 ? ALICE : CAROL, "#common #n" + i + " @bob"));

        var a = CompletableFuture.supplyAsync(() -> backfill(2).run(() -> false));
        var b = CompletableFuture.supplyAsync(() -> backfill(2).run(() -> false));
        StartupBackfill.Outcome oa = a.join(), ob = b.join(); // no exception (e.g. no duplicate-key violation)

        assertThat(oa.items() + ob.items()).isEqualTo(60); // advisory lock + shared cursor: each post exactly once
        assertThat(stateRow().processed()).isEqualTo(60);
        assertThat(stateRow().completed()).isTrue();
        for (int i = 0; i < ids.size(); i++) {
            assertThat(tagsOf(ids.get(i))).containsExactly("common", "n" + i);
            assertThat(mentionsOf(ids.get(i))).containsExactly(new Mention(BOB, "Bob"));
        }
    }

    @Test
    void aRowLockedByLiveTrafficIsSkippedThenRetried_andAConcurrentEditWins() throws Exception {
        UUID waitsForLock = legacy(ALICE, "#legacy1 @bob");
        UUID editedMeanwhile = legacy(ALICE, "#legacy2 @bob");
        for (int i = 0; i < 4; i++) legacy(BOB, "#other" + i);

        // Live traffic holds both rows: a reaction-like lock (no change) and an edit in progress.
        var locked = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var tx = new TransactionTemplate(transactionManager);
        var reaction = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            posts.findByIdForUpdate(waitsForLock).orElseThrow();
            locked.countDown();
            await(release);
        }));
        var edit = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            postService.updatePost(editedMeanwhile, ALICE, "#edited", null);
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        var run = CompletableFuture.supplyAsync(() -> backfill(10).run(() -> false));
        // Everything else is indexed without waiting; the run then waits for the two locks only.
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
                .until(() -> jdbc.queryForObject("SELECT count(*) FROM post_hashtags WHERE tag LIKE 'other%'", Long.class) == 4);
        Thread.sleep(300);
        assertThat(run).isNotDone();

        release.countDown();
        reaction.get(10, TimeUnit.SECONDS);
        edit.get(10, TimeUnit.SECONDS);
        var outcome = run.get(10, TimeUnit.SECONDS);

        assertThat(outcome.stopped()).isFalse();
        assertThat(tagsOf(waitsForLock)).containsExactly("legacy1");
        assertThat(mentionsOf(waitsForLock)).containsExactly(new Mention(BOB, "Bob"));
        assertThat(tagsOf(editedMeanwhile)).containsExactly("edited"); // the edit's rows, not the legacy text's
        assertThat(mentionsOf(editedMeanwhile)).isEmpty();
        assertThat(stateRow().completed()).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("not released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
