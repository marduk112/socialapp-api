package com.socialapp.feed.infrastructure.persistence;

import com.socialapp.feed.application.timeline.FeedCursor;
import com.socialapp.feed.application.timeline.FeedPage;
import com.socialapp.feed.application.timeline.FeedScope;
import com.socialapp.feed.application.timeline.TimelineService;
import com.socialapp.feed.application.trends.TrendsService;
import com.socialapp.feed.application.usecase.BlockMuteProjection;
import com.socialapp.feed.application.usecase.BookmarkService;
import com.socialapp.feed.application.usecase.FollowProjection;
import com.socialapp.feed.application.usecase.PostService;
import com.socialapp.feed.domain.BlockMuteRepository;
import com.socialapp.feed.domain.Comment;
import com.socialapp.feed.domain.Post;
import com.socialapp.feed.domain.TrendingHashtag;
import com.socialapp.feed.infrastructure.web.PostResponse;
import com.socialapp.shared.domain.NotFoundException;
import com.socialapp.shared.events.UserBlockedEvent;
import com.socialapp.shared.events.UserFollowedEvent;
import com.socialapp.shared.events.UserMutedEvent;
import com.socialapp.shared.events.UserUnblockedEvent;
import com.socialapp.shared.events.UserUnmutedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage 5A, Feed, against a real PostgreSQL with the Flyway schema (V26): the block/mute projection (idempotent,
 * order-tolerant) and every Feed view it filters - timelines (all, following), tag pages, search, trends, bookmarks,
 * comments, single posts and embedded originals - for blocks in both directions and mutes in one direction, with exact
 * keyset pages, and unblock/unmute restoring everything. Commit-per-call (like {@link BookmarkPostgresTest}); fresh
 * users, tags and words per test, so tests never see each other's restrictions.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({PostService.class, BlockMuteAdapters.class, TimelineService.class, BookmarkService.class, TrendsService.class,
        FollowProjection.class, JdbcPostHashtagRepository.class, JdbcPostMentionRepository.class,
        com.socialapp.feed.infrastructure.media.MediaAdapters.class})
class BlockMutePostgresTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");

    @Autowired PostService postService;
    @Autowired TimelineService timeline;
    @Autowired BookmarkService bookmarks;
    @Autowired TrendsService trends;
    @Autowired BlockMuteProjection projection;
    @Autowired FollowProjection follows;
    @Autowired BlockMuteRepository repository;

    // Fresh per test (the database is shared and committed).
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();
    private final UUID dave = UUID.randomUUID();
    private final String unique = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private int tick;

    private Instant next() {
        return T0.plusSeconds(++tick);
    }

    private void block(UUID blocker, UUID blocked) {
        projection.on(new UserBlockedEvent(UUID.randomUUID(), next(), blocker, blocked));
    }

    private void unblock(UUID blocker, UUID blocked) {
        projection.on(new UserUnblockedEvent(UUID.randomUUID(), next(), blocker, blocked));
    }

    private void mute(UUID muter, UUID muted) {
        projection.on(new UserMutedEvent(UUID.randomUUID(), next(), muter, muted));
    }

    private void unmute(UUID muter, UUID muted) {
        projection.on(new UserUnmutedEvent(UUID.randomUUID(), next(), muter, muted));
    }

    private Post post(UUID author, String text) {
        return postService.createPost(author, "user", text, null);
    }

    /** Pages through a whole list as a client would; asserts that only full pages carry a cursor. */
    private List<UUID> pageThrough(Function<FeedCursor, FeedPage> pages, int size) {
        List<UUID> seen = new ArrayList<>();
        FeedCursor cursor = null;
        for (int i = 0; i < 1000; i++) {
            var page = pages.apply(cursor);
            page.posts().forEach(p -> seen.add(p.getId()));
            if (page.next() == null) return seen;
            assertThat(page.posts()).hasSize(size);
            cursor = FeedCursor.decode(page.next().encode());
        }
        throw new AssertionError("pagination did not terminate");
    }

    /** The posts of {@code ids} that {@code viewer}'s timeline contains, in timeline order. */
    private List<UUID> timelineOf(UUID viewer, FeedScope scope, int size, List<Post> ids) {
        var wanted = new HashSet<>(ids.stream().map(Post::getId).toList());
        return pageThrough(c -> timeline.page(viewer, scope, c, size), size).stream().filter(wanted::contains).toList();
    }

    /** Newest first, as every timeline orders. */
    private static List<UUID> newestFirst(Post... posts) {
        return java.util.Arrays.stream(posts)
                .sorted(java.util.Comparator.comparing(Post::getCreatedAt).reversed()
                        .thenComparing(Post::getId, java.util.Comparator.reverseOrder()))
                .map(Post::getId).toList();
    }

    // ── Projection ──────────────────────────────────────────────────────────

    @Test
    void projectionIsIdempotentAndOrderTolerant_newestEventPerPairWins() {
        var older = new UserBlockedEvent(UUID.randomUUID(), T0.plusSeconds(1), alice, bob);
        var newer = new UserUnblockedEvent(UUID.randomUUID(), T0.plusSeconds(2), alice, bob);

        assertThat(projection.on(newer)).isTrue();   // the unblock arrives first: tombstone
        assertThat(projection.on(older)).isFalse();  // the late block cannot resurrect it
        assertThat(projection.on(newer)).isFalse();  // redelivery
        assertThat(repository.blockedEitherWay(alice)).isEmpty();

        var again = new UserBlockedEvent(UUID.randomUUID(), T0.plusSeconds(3), alice, bob);
        assertThat(projection.on(again)).isTrue();
        assertThat(projection.on(again)).isFalse();
        assertThat(repository.blockedEitherWay(alice)).containsExactly(bob);
        assertThat(repository.blockedEitherWay(bob)).containsExactly(alice); // either direction counts
        assertThat(repository.blockedAmong(bob, List.of(alice, carol))).containsExactly(alice);

        var mute = new UserMutedEvent(UUID.randomUUID(), T0.plusSeconds(5), alice, carol);
        var lateUnmute = new UserUnmutedEvent(UUID.randomUUID(), T0.plusSeconds(4), alice, carol);
        assertThat(projection.on(mute)).isTrue();
        assertThat(projection.on(lateUnmute)).isFalse();
        assertThat(repository.mutedBy(alice)).containsExactly(carol);
        assertThat(repository.mutedBy(carol)).isEmpty(); // one-directional
        assertThat(repository.blockedEitherWay(carol)).isEmpty(); // a mute is not a block

        // malformed events (never produced by SocialGraph) are dropped
        assertThat(projection.on(new UserBlockedEvent(UUID.randomUUID(), T0, alice, alice))).isFalse();
        assertThat(projection.on(new UserMutedEvent(UUID.randomUUID(), null, alice, dave))).isFalse();
    }

    // ── Timelines ───────────────────────────────────────────────────────────

    @Test
    void timelinesHideBlockedUsersBothWaysAndMutedUsersOneWay_withExactKeysetPages_untilUndone() {
        List<Post> all = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            all.add(post(alice, "a" + i));
            all.add(post(bob, "b" + i));
            all.add(post(carol, "c" + i));
            all.add(post(dave, "d" + i));
        }
        Post[] alices = all.stream().filter(p -> p.getAuthorId().equals(alice)).toArray(Post[]::new);
        Post[] bobs = all.stream().filter(p -> p.getAuthorId().equals(bob)).toArray(Post[]::new);
        Post[] carols = all.stream().filter(p -> p.getAuthorId().equals(carol)).toArray(Post[]::new);
        Post[] daves = all.stream().filter(p -> p.getAuthorId().equals(dave)).toArray(Post[]::new);
        block(alice, bob);
        mute(alice, carol);

        for (int size : new int[] {1, 3, 5}) {
            assertThat(timelineOf(alice, FeedScope.ALL, size, all)).as("alice, size %d", size)
                    .containsExactlyElementsOf(newestFirst(concat(alices, daves)));
            // bob is blocked BY alice: alice's posts are gone for him too; carol is only muted by alice
            assertThat(timelineOf(bob, FeedScope.ALL, size, all)).as("bob, size %d", size)
                    .containsExactlyElementsOf(newestFirst(concat(bobs, carols, daves)));
            assertThat(timelineOf(carol, FeedScope.ALL, size, all)).as("carol, size %d", size)
                    .containsExactlyElementsOf(newestFirst(all.toArray(Post[]::new)));
        }

        // following scope: a muted followee is hidden too
        follows.on(new UserFollowedEvent(UUID.randomUUID(), next(), alice, carol));
        follows.on(new UserFollowedEvent(UUID.randomUUID(), next(), alice, dave));
        assertThat(timelineOf(alice, FeedScope.FOLLOWING, 2, all))
                .containsExactlyElementsOf(newestFirst(concat(alices, daves)));

        unblock(alice, bob);
        unmute(alice, carol);
        assertThat(timelineOf(alice, FeedScope.ALL, 3, all))
                .containsExactlyElementsOf(newestFirst(all.toArray(Post[]::new)));
        assertThat(timelineOf(bob, FeedScope.ALL, 3, all))
                .containsExactlyElementsOf(newestFirst(all.toArray(Post[]::new)));
        assertThat(timelineOf(alice, FeedScope.FOLLOWING, 2, all))
                .containsExactlyElementsOf(newestFirst(concat(alices, carols, daves)));
    }

    @Test
    void repostsOfHiddenAuthorsAreHidden_quotesStayWithTheBlockedOriginalUnavailable() {
        var bobs = post(bob, "bob's");
        var carols = post(carol, "carol's");
        var repostOfBob = postService.repost(bobs.getId(), dave, "dave").post();
        var repostOfCarol = postService.repost(carols.getId(), dave, "dave").post();
        var quoteOfBob = postService.quote(bobs.getId(), dave, "dave", "dave on bob");
        var quoteOfCarol = postService.quote(carols.getId(), dave, "dave", "dave on carol");
        var items = List.of(bobs, carols, repostOfBob, repostOfCarol, quoteOfBob, quoteOfCarol);
        block(bob, alice); // bob blocks alice: the other direction
        mute(alice, carol);

        assertThat(timelineOf(alice, FeedScope.ALL, 2, items))
                .containsExactlyElementsOf(newestFirst(quoteOfBob, quoteOfCarol));

        var views = postService.views(List.of(quoteOfBob, quoteOfCarol), alice).stream().map(PostResponse::from).toList();
        assertThat(views.get(0).original()).isNull();          // blocked: unavailable, no snapshot either
        assertThat(views.get(0).originalContent()).isNull();
        assertThat(views.get(0).originalPostId()).isNull();
        assertThat(views.get(1).original().authorId()).isEqualTo(carol); // muted: still embedded
        assertThat(views.get(1).originalContent()).isEqualTo("carol's");

        // explicit opening: a block is a 404 (also for a repost item of the blocked user's post), a mute is not
        assertThatThrownBy(() -> postService.findVisibleById(bobs.getId(), alice)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> postService.findVisibleById(repostOfBob.getId(), alice))
                .isInstanceOf(NotFoundException.class);
        assertThat(postService.findVisibleById(carols.getId(), alice).getId()).isEqualTo(carols.getId());
        assertThat(postService.findVisibleById(repostOfCarol.getId(), alice).getId()).isEqualTo(repostOfCarol.getId());
        assertThat(postService.findVisibleById(quoteOfBob.getId(), bob).getId()).isEqualTo(quoteOfBob.getId()); // dave is not blocked
    }

    // ── Tag pages, search, trends ───────────────────────────────────────────

    @Test
    void tagPagesSearchAndTrendsAreAsTheViewerSeesThem() {
        String tag = unique;
        var alices = post(alice, "#" + tag + " " + unique + "word alice");
        var bobs = post(bob, "#" + tag + " " + unique + "word bob");
        var carols = post(carol, "#" + tag + " " + unique + "word carol");
        var daves1 = post(dave, "#" + tag + " " + unique + "word dave one");
        var daves2 = post(dave, "#" + tag + " " + unique + "word dave two");
        block(alice, bob);
        mute(alice, carol);

        assertThat(pageThrough(c -> timeline.hashtagPage(alice, tag, c, 1), 1))
                .containsExactlyElementsOf(newestFirst(alices, daves1, daves2));
        assertThat(pageThrough(c -> timeline.hashtagPage(bob, "#" + tag, c, 2), 2))
                .containsExactlyElementsOf(newestFirst(bobs, carols, daves1, daves2));
        assertThat(pageThrough(c -> timeline.searchPage(alice, unique + "word", c, 2), 2))
                .containsExactlyElementsOf(newestFirst(alices, daves1, daves2));
        assertThat(pageThrough(c -> timeline.searchPage(bob, unique + "word", c, 1), 1))
                .containsExactlyElementsOf(newestFirst(bobs, carols, daves1, daves2));

        assertThat(trendOf(alice, tag)).isEqualTo(new TrendingHashtag(tag, 3, 2)); // alice, dave x2
        assertThat(trendOf(bob, tag)).isEqualTo(new TrendingHashtag(tag, 4, 3));   // bob, carol, dave x2
        assertThat(trendOf(carol, tag)).isEqualTo(new TrendingHashtag(tag, 5, 4));

        unblock(alice, bob);
        unmute(alice, carol);
        assertThat(trendOf(alice, tag)).isEqualTo(new TrendingHashtag(tag, 5, 4));
        assertThat(pageThrough(c -> timeline.hashtagPage(alice, tag, c, 20), 20)).hasSize(5);
    }

    private TrendingHashtag trendOf(UUID viewer, String tag) {
        return trends.trendingHashtags(viewer, 24, TrendsService.MAX_LIMIT).stream()
                .filter(t -> t.tag().equals(tag)).findFirst().orElseThrow();
    }

    // ── Bookmarks, comments ─────────────────────────────────────────────────

    @Test
    void bookmarksHideBlockedPostsOnly_andGetThemBackAfterAnUnblock() {
        var bobs = post(bob, "b");
        var carols = post(carol, "c");
        var daves = post(dave, "d");
        var repostOfBob = postService.repost(bobs.getId(), dave, "dave").post();
        for (var p : List.of(bobs, carols, daves, repostOfBob)) bookmarks.bookmark(alice, p.getId());
        block(alice, bob);
        mute(alice, carol);

        // newest bookmark first; bob's post and the repost of it are hidden, the muted carol's post is not
        assertThat(pageThrough(c -> bookmarks.page(alice, c, 1), 1)).containsExactly(daves.getId(), carols.getId());
        assertThatThrownBy(() -> bookmarks.bookmark(alice, bobs.getId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> bookmarks.bookmark(bob, post(alice, "a").getId()))
                .isInstanceOf(NotFoundException.class); // the other direction too

        unblock(alice, bob);
        assertThat(pageThrough(c -> bookmarks.page(alice, c, 3), 3))
                .containsExactly(repostOfBob.getId(), daves.getId(), carols.getId(), bobs.getId());
    }

    @Test
    void commentListsHideBlockedCommentersBothWays_notMutedOnes_withExactPages() {
        var daves = post(dave, "thread");
        List<Comment> comments = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            comments.add(postService.addComment(daves.getId(), alice, "alice", "a" + i, null));
            comments.add(postService.addComment(daves.getId(), bob, "bob", "b" + i, null));
            comments.add(postService.addComment(daves.getId(), carol, "carol", "c" + i, null));
        }
        block(bob, alice);
        mute(alice, carol);

        assertThat(commentAuthors(alice, daves, 2)).containsExactly(alice, carol, alice, carol, alice, carol);
        assertThat(commentAuthors(bob, daves, 4)).containsExactly(bob, carol, bob, carol, bob, carol);
        assertThat(commentAuthors(dave, daves, 5)).hasSize(9);

        // the blocked side's post: comments 404, as do commenting, replying and liking
        var bobsPost = post(bob, "bob's thread");
        assertThatThrownBy(() -> postService.getComments(bobsPost.getId(), alice, 0, 10))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> postService.addComment(bobsPost.getId(), alice, "alice", "x", null))
                .isInstanceOf(NotFoundException.class);
        var bobsComment = comments.get(1);
        assertThatThrownBy(() -> postService.addComment(daves.getId(), alice, "alice", "r", bobsComment.getId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> postService.reactToComment(bobsComment.getId(), alice,
                com.socialapp.shared.events.ReactionEvent.ReactionType.LIKE, true)).isInstanceOf(NotFoundException.class);
        // a muted user's content stays reachable
        assertThat(postService.addComment(daves.getId(), alice, "alice", "r", comments.get(2).getId())).isNotNull();

        unblock(bob, alice);
        assertThat(commentAuthors(alice, daves, 100)).hasSize(10);
    }

    /** Authors of all comments {@code viewer} sees on {@code post}, paging with {@code size}. */
    private List<UUID> commentAuthors(UUID viewer, Post post, int size) {
        List<UUID> authors = new ArrayList<>();
        for (int page = 0; ; page++) {
            var comments = postService.getComments(post.getId(), viewer, page, size);
            comments.forEach(c -> authors.add(c.getAuthorId()));
            if (comments.size() < size) return authors;
        }
    }

    private static Post[] concat(Post[]... parts) {
        return java.util.Arrays.stream(parts).flatMap(java.util.Arrays::stream).toArray(Post[]::new);
    }
}
