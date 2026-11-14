package com.socialapp.feed.infrastructure.persistence;

import com.socialapp.feed.application.timeline.FeedScope;
import com.socialapp.feed.application.timeline.TimelineService;
import com.socialapp.feed.application.usecase.PostService;
import com.socialapp.feed.application.usecase.PostView;
import com.socialapp.feed.domain.*;
import com.socialapp.shared.events.ReactionEvent.ReactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage 2C against a real PostgreSQL with the Flyway schema (V18): repost idempotency under concurrency, the
 * partial unique index and CHECK constraints, grouped counters, deletion of an original (reposts deleted, quotes
 * detached), the hashtag timeline, and the N+1 guard (JDBC statements per page do not grow with the page size).
 * Tests are NOT wrapped in a rolled-back transaction: every service call commits, as in production, which the
 * concurrency test needs. Each test uses fresh user ids, so tests do not see each other's rows.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({PostService.class, TimelineService.class, JdbcPostHashtagRepository.class, JdbcPostMentionRepository.class,
        RepostPostgresTest.StatementCounting.class})
class RepostPostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /** Counts JDBC statements prepared/created through the application DataSource (JPA and JdbcTemplate alike). */
    static final AtomicInteger STATEMENTS = new AtomicInteger();

    @TestConfiguration
    static class StatementCounting {
        @Bean
        static BeanPostProcessor countingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DataSource ds)) return bean;
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                            (proxy, method, args) -> {
                                Object result = invoke(ds, method, args);
                                return result instanceof Connection c ? counting(c) : result;
                            });
                }
            };
        }

        private static Connection counting(Connection c) {
            return (Connection) Proxy.newProxyInstance(StatementCounting.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        String m = method.getName();
                        if (m.equals("prepareStatement") || m.equals("createStatement") || m.equals("prepareCall")) {
                            STATEMENTS.incrementAndGet();
                        }
                        return invoke(c, method, args);
                    });
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    @Autowired PostService postService;
    @Autowired TimelineService timeline;
    @Autowired PostRepository posts;
    @Autowired PostHashtagRepository hashtags;
    @Autowired JdbcTemplate jdbc;

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // ── idempotent repost ───────────────────────────────────────────────────

    @Test
    void concurrentDoubleRepostIsIdempotent_oneRowOneCreated_no500() throws Exception {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        var original = postService.createPost(bob, "bob", "popular", null);

        List<PostService.RepostResult> results = runConcurrently(8,
                i -> () -> postService.repost(original.getId(), alice, "alice"));

        assertThat(results).filteredOn(PostService.RepostResult::created).hasSize(1);
        assertThat(results).extracting(r -> r.post().getId()).containsOnly(results.get(0).post().getId());
        assertThat(count("SELECT count(*) FROM posts WHERE author_id = ? AND original_post_id = ? AND type = 'REPOST'",
                alice, original.getId())).isEqualTo(1);
    }

    @Test
    void repostThroughARepostItemIsFlattened_andIsTheSameIdempotentRepost() {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(), carol = UUID.randomUUID();
        var original = postService.createPost(bob, "bob", "orig", null);
        var carols = postService.repost(original.getId(), carol, "carol").post();

        var first = postService.repost(carols.getId(), alice, "alice");
        var second = postService.repost(original.getId(), alice, "alice");

        assertThat(first.created()).isTrue();
        assertThat(first.post().getOriginalPostId()).isEqualTo(original.getId());
        assertThat(second.created()).isFalse();
        assertThat(second.post().getId()).isEqualTo(first.post().getId());
    }

    @Test
    void schemaEnforcesTheTypeInvariants() {
        UUID alice = UUID.randomUUID();
        var original = postService.createPost(UUID.randomUUID(), "bob", "orig", null);
        String insert = """
                INSERT INTO posts (id, author_id, author_username, content, created_at, updated_at, type, original_post_id)
                VALUES (?, ?, 'alice', ?, now(), now(), ?, ?)""";

        jdbc.update(insert, UUID.randomUUID(), alice, "", "REPOST", original.getId());
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), alice, "", "REPOST", original.getId()))
                .isInstanceOf(DataIntegrityViolationException.class); // partial unique index
        // quotes are unlimited
        jdbc.update(insert, UUID.randomUUID(), alice, "q1", "QUOTE", original.getId());
        jdbc.update(insert, UUID.randomUUID(), alice, "q2", "QUOTE", original.getId());
        // CHECKs: REPOST needs an original and no text, POST has no original, unknown type rejected
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), UUID.randomUUID(), "", "REPOST", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), UUID.randomUUID(), "x", "REPOST", original.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), alice, "x", "POST", original.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), alice, "x", "SHARE", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        // and the original can only be deleted once its reposts are gone (no orphan repost via ON DELETE SET NULL)
        assertThatThrownBy(() -> jdbc.update("DELETE FROM posts WHERE id = ?", original.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void undoRemovesOnlyTheCallersRepost_viaOriginalOrRepostItemId_idempotent() {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(), carol = UUID.randomUUID();
        var original = postService.createPost(bob, "bob", "orig", null);
        var carols = postService.repost(original.getId(), carol, "carol").post();
        postService.repost(original.getId(), alice, "alice");

        postService.undoRepost(carols.getId(), alice); // resolved to the original
        postService.undoRepost(original.getId(), alice); // nothing left: no-op
        postService.undoRepost(UUID.randomUUID(), alice); // unknown: no-op

        assertThat(posts.findRepost(alice, original.getId())).isEmpty();
        assertThat(posts.findRepost(carol, original.getId())).isPresent();
        assertThat(postService.repost(original.getId(), alice, "alice").created()).isTrue(); // can repost again
    }

    // ── counters and viewer state ───────────────────────────────────────────

    @Test
    void groupedCountsAndViewerRepostedComeFromTheDatabase() {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(), carol = UUID.randomUUID();
        var original = postService.createPost(bob, "bob", "orig", null);
        var lonely = postService.createPost(bob, "bob", "nobody cares", null);
        postService.repost(original.getId(), alice, "alice");
        postService.repost(original.getId(), carol, "carol");
        postService.quote(original.getId(), alice, "alice", "q1");
        postService.quote(original.getId(), alice, "alice", "q2");
        var carolsQuote = postService.quote(original.getId(), carol, "carol", "q3");
        postService.react(original.getId(), alice, ReactionType.LIKE, true);

        var rows = posts.countRepostsAndQuotes(List.of(original.getId(), lonely.getId(), carolsQuote.getId()));
        assertThat(rows).hasSize(2).allSatisfy(r -> assertThat(r[0]).isEqualTo(original.getId()));
        assertThat(posts.findRepostedOriginalIds(alice, List.of(original.getId(), lonely.getId())))
                .containsExactly(original.getId());

        var asAlice = postService.views(List.of(original, lonely, carolsQuote), alice);
        assertThat(asAlice.get(0).repostsCount()).isEqualTo(2);
        assertThat(asAlice.get(0).quotesCount()).isEqualTo(3);
        assertThat(asAlice.get(0).viewerReposted()).isTrue();
        assertThat(asAlice.get(0).userReaction()).isEqualTo(ReactionType.LIKE);
        assertThat(asAlice.get(1).repostsCount()).isZero();
        assertThat(asAlice.get(1).viewerReposted()).isFalse();
        // nested original of carol's quote: counters and alice's state too
        var nested = asAlice.get(2).original();
        assertThat(nested.post().getId()).isEqualTo(original.getId());
        assertThat(nested.repostsCount()).isEqualTo(2);
        assertThat(nested.viewerReposted()).isTrue();
        assertThat(nested.userReaction()).isEqualTo(ReactionType.LIKE);

        var asBob = postService.views(List.of(original), bob).get(0);
        assertThat(asBob.viewerReposted()).isFalse();
        assertThat(asBob.userReaction()).isNull();
    }

    // ── deleting an original ────────────────────────────────────────────────

    @Test
    void deletingAnOriginalDeletesItsRepostsAndKeepsItsQuotesWithoutOriginal() {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(), carol = UUID.randomUUID();
        var original = postService.createPost(bob, "bob", "#secret text", null);
        var repost = postService.repost(original.getId(), alice, "alice").post();
        var comment = postService.addComment(repost.getId(), carol, "carol", "on the repost", null);
        postService.react(repost.getId(), carol, ReactionType.LIKE, true);
        var quote = postService.quote(original.getId(), carol, "carol", "my #view");
        var repostOfQuote = postService.repost(quote.getId(), alice, "alice").post();

        postService.deletePost(original.getId(), bob);

        assertThat(posts.findById(repost.getId())).isEmpty();
        assertThat(count("SELECT count(*) FROM comments WHERE id = ?", comment.getId())).isZero();
        assertThat(count("SELECT count(*) FROM post_reactions WHERE post_id = ?", repost.getId())).isZero();
        var kept = posts.findById(quote.getId()).orElseThrow();
        assertThat(kept.getType()).isEqualTo(PostType.QUOTE);
        assertThat(kept.getContent()).isEqualTo("my #view");
        assertThat(kept.getOriginalPostId()).isNull();
        assertThat(kept.getOriginalContent()).isNull(); // deleted text is not kept as a snapshot
        assertThat(kept.getOriginalAuthorUsername()).isNull();
        assertThat(count("SELECT count(*) FROM post_hashtags WHERE post_id = ? AND tag = 'view'", quote.getId())).isEqualTo(1);

        var views = postService.views(List.of(kept, posts.findById(repostOfQuote.getId()).orElseThrow()), alice);
        assertThat(views.get(0).original()).isNull();
        // repost of the quote: nested quote present, its quoted post gone -> second level null
        assertThat(views.get(1).original().post().getId()).isEqualTo(quote.getId());
        assertThat(views.get(1).original().original()).isNull();
    }

    @Test
    void repostOfAQuoteCarriesTheQuotedPostAsSecondLevel() {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(), carol = UUID.randomUUID();
        var quoted = postService.createPost(bob, "bob", "quoted", null);
        var quote = postService.quote(quoted.getId(), carol, "carol", "carol's take");
        var repost = postService.repost(quote.getId(), alice, "alice").post();
        postService.repost(quoted.getId(), alice, "alice");

        PostView view = postService.views(List.of(repost), alice).get(0);

        assertThat(view.original().post().getId()).isEqualTo(quote.getId());
        assertThat(view.original().repostsCount()).isEqualTo(1);
        assertThat(view.original().viewerReposted()).isTrue();
        assertThat(view.original().original().post().getId()).isEqualTo(quoted.getId());
        assertThat(view.original().original().quotesCount()).isEqualTo(1);
        assertThat(view.original().original().viewerReposted()).isTrue();
        assertThat(view.original().original().original()).isNull();
    }

    // ── timelines ───────────────────────────────────────────────────────────

    @Test
    void hashtagTimelineHasQuotesButNeverReposts() {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        String tag = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        var original = postService.createPost(bob, "bob", "#" + tag + " original", null);
        var quote = postService.quote(original.getId(), alice, "alice", "agree #" + tag);
        var repost = postService.repost(original.getId(), alice, "alice").post();
        // even a (never written by the app) tag row for a repost does not surface it
        hashtags.replace(repost.getId(), List.of(tag));

        var ids = timeline.hashtagPage(tag, null, 20).posts().stream().map(Post::getId).toList();

        assertThat(ids).containsExactly(quote.getId(), original.getId());
    }

    @Test
    void timelinesContainRepostsAndQuotesOrderedByTheirOwnCreatedAt() {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(), viewer = UUID.randomUUID();
        var original = postService.createPost(bob, "bob", "old original", null);
        var repost = postService.repost(original.getId(), alice, "alice").post();
        var quote = postService.quote(original.getId(), alice, "alice", "quote");
        jdbc.update("INSERT INTO follows (follower_id, followee_id, active, followed_at, last_event_at) "
                + "VALUES (?, ?, true, now(), now())", viewer, alice);

        var following = timeline.page(viewer, FeedScope.FOLLOWING, null, 20).posts().stream().map(Post::getId).toList();

        assertThat(following).containsExactly(quote.getId(), repost.getId()); // bob is not followed
    }

    // ── N+1 guard ───────────────────────────────────────────────────────────

    @Test
    void statementsPerPageDoNotGrowWithPageSize() {
        UUID author = UUID.randomUUID(), viewer = UUID.randomUUID();
        List<Post> page = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            var quoted = postService.createPost(author, "a", "#n" + i + " quoted @nobody", null);
            var quote = postService.quote(quoted.getId(), author, "a", "q #x" + i);
            page.add(postService.repost(quote.getId(), viewer, "v").post());
            page.add(postService.quote(quote.getId(), viewer, "v", "again"));
            page.add(postService.repost(quoted.getId(), viewer, "v").post());
            postService.react(quoted.getId(), viewer, ReactionType.LIKE, true);
        }

        int small = statementsFor(page.subList(0, 6), viewer);
        int large = statementsFor(page, viewer);

        assertThat(large).isEqualTo(small);
        // at most: 2 original levels + avatars + hashtags + mentions + counts + viewer reposts + viewer reactions;
        // here the quoted posts are already loaded at level 1 (they are reposted directly too), so 7
        assertThat(small).isBetween(7, 8);
    }

    private int statementsFor(List<Post> page, UUID viewer) {
        STATEMENTS.set(0);
        var views = postService.views(page, viewer);
        int n = STATEMENTS.get();
        assertThat(views).hasSize(page.size());
        assertThat(views.get(0).original().original()).isNotNull(); // all levels were really loaded
        assertThat(views.get(2).original().userReaction()).isEqualTo(ReactionType.LIKE);
        return n;
    }

    private static <T> List<T> runConcurrently(int n, java.util.function.IntFunction<Callable<T>> task) throws Exception {
        var pool = Executors.newFixedThreadPool(n);
        try {
            var start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<T> c = task.apply(i);
                futures.add(pool.submit(() -> { start.await(); return c.call(); }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var f : futures) results.add(f.get()); // rethrows any failure
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
