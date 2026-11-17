package com.socialapp.feed.infrastructure.persistence;

import com.socialapp.feed.application.timeline.FeedCursor;
import com.socialapp.feed.application.timeline.TimelineService;
import com.socialapp.feed.application.usecase.BookmarkService;
import com.socialapp.feed.application.usecase.PostService;
import com.socialapp.feed.domain.Post;
import com.socialapp.shared.domain.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage 3B against a real PostgreSQL with the Flyway schema (V21): idempotent bookmark, isolation between users,
 * keyset paging with equal timestamps, cascade on post deletion, the per-viewer {@code bookmarked} flag and its
 * batch lookup. Same commit-per-call style as {@link RepostPostgresTest}; fresh user ids per test.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({PostService.class, TimelineService.class, BookmarkService.class, JdbcPostHashtagRepository.class,
        JdbcPostMentionRepository.class, com.socialapp.feed.infrastructure.media.MediaAdapters.class})
class BookmarkPostgresTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired PostService postService;
    @Autowired BookmarkService bookmarkService;
    @Autowired JdbcTemplate jdbc;

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private List<UUID> listAll(UUID user, int size) {
        List<UUID> ids = new ArrayList<>();
        FeedCursor cursor = null;
        do {
            var page = bookmarkService.page(user, cursor, size);
            page.posts().forEach(p -> ids.add(p.getId()));
            cursor = page.next();
        } while (cursor != null);
        return ids;
    }

    @Test
    void bookmarkIsIdempotent_missingPostIsNotFound_unbookmarkIsIdempotent() {
        UUID alice = UUID.randomUUID();
        var post = postService.createPost(UUID.randomUUID(), "bob", "hi", null);

        bookmarkService.bookmark(alice, post.getId());
        bookmarkService.bookmark(alice, post.getId());
        assertThat(count("SELECT count(*) FROM bookmarks WHERE user_id = ?", alice)).isEqualTo(1);

        assertThatThrownBy(() -> bookmarkService.bookmark(alice, UUID.randomUUID())).isInstanceOf(NotFoundException.class);

        bookmarkService.unbookmark(alice, post.getId());
        bookmarkService.unbookmark(alice, post.getId());
        bookmarkService.unbookmark(alice, UUID.randomUUID());
        assertThat(count("SELECT count(*) FROM bookmarks WHERE user_id = ?", alice)).isZero();
    }

    @Test
    void usersNeverSeeOrAffectEachOthersBookmarks() {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        var post = postService.createPost(UUID.randomUUID(), "carol", "shared", null);
        var other = postService.createPost(UUID.randomUUID(), "carol", "other", null);
        bookmarkService.bookmark(alice, post.getId());
        bookmarkService.bookmark(bob, other.getId());

        assertThat(listAll(alice, 10)).containsExactly(post.getId());
        assertThat(listAll(bob, 10)).containsExactly(other.getId());
        assertThat(postService.view(post, alice).bookmarked()).isTrue();
        assertThat(postService.view(post, bob).bookmarked()).isFalse();
        assertThat(postService.view(post, null).bookmarked()).isFalse();

        bookmarkService.unbookmark(bob, post.getId()); // bob never bookmarked it: alice's stays
        assertThat(listAll(alice, 10)).containsExactly(post.getId());
    }

    @Test
    void listIsNewestBookmarkedFirst_andPagesThroughEqualTimestampsWithoutDuplicatesOrGaps() {
        UUID alice = UUID.randomUUID(), author = UUID.randomUUID();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            var p = postService.createPost(author, "a", "p" + i, null);
            bookmarkService.bookmark(alice, p.getId());
            ids.add(p.getId());
        }
        // Three newest bookmarks share one timestamp (the post-id tie-break decides), the rest are older and distinct.
        jdbc.update("UPDATE bookmarks SET created_at = '2030-01-01T00:00:00Z' WHERE user_id = ? AND post_id IN (?, ?, ?)",
                alice, ids.get(0), ids.get(1), ids.get(2));
        for (int i = 3; i < 7; i++) {
            jdbc.update("UPDATE bookmarks SET created_at = '2020-01-01T00:00:00Z'::timestamptz + (? * interval '1 day') "
                    + "WHERE user_id = ? AND post_id = ?", i, alice, ids.get(i));
        }

        var expected = jdbc.queryForList("SELECT post_id FROM bookmarks WHERE user_id = ? "
                + "ORDER BY created_at DESC, post_id DESC", UUID.class, alice);
        assertThat(expected).hasSize(7);
        for (int size : new int[] {1, 2, 3, 7, 20}) {
            assertThat(listAll(alice, size)).as("size " + size).containsExactlyElementsOf(expected);
        }
        // equal-timestamp group first (newest), then the distinct ones newest first
        assertThat(expected.subList(0, 3)).containsExactlyInAnyOrder(ids.get(0), ids.get(1), ids.get(2));
        assertThat(expected.subList(3, 7)).containsExactly(ids.get(6), ids.get(5), ids.get(4), ids.get(3));
    }

    @Test
    void rebookmarkingKeepsItsPosition_andUnbookmarkThenBookmarkMovesItToTheTop() {
        UUID alice = UUID.randomUUID(), author = UUID.randomUUID();
        var first = postService.createPost(author, "a", "first", null);
        var second = postService.createPost(author, "a", "second", null);
        bookmarkService.bookmark(alice, first.getId());
        jdbc.update("UPDATE bookmarks SET created_at = created_at - interval '1 hour' WHERE user_id = ?", alice);
        bookmarkService.bookmark(alice, second.getId());

        bookmarkService.bookmark(alice, first.getId()); // idempotent: no change of position
        assertThat(listAll(alice, 10)).containsExactly(second.getId(), first.getId());

        bookmarkService.unbookmark(alice, second.getId());
        bookmarkService.bookmark(alice, second.getId());
        bookmarkService.unbookmark(alice, first.getId());
        bookmarkService.bookmark(alice, first.getId());
        assertThat(listAll(alice, 10)).containsExactly(first.getId(), second.getId());
    }

    @Test
    void deletingAPostRemovesItsBookmarksOfEveryone_andNeverBreaksTheList() {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(), author = UUID.randomUUID();
        var doomed = postService.createPost(author, "a", "doomed", null);
        var kept = postService.createPost(author, "a", "kept", null);
        bookmarkService.bookmark(alice, doomed.getId());
        bookmarkService.bookmark(alice, kept.getId());
        bookmarkService.bookmark(bob, doomed.getId());

        postService.deletePost(doomed.getId(), author);

        assertThat(count("SELECT count(*) FROM bookmarks WHERE post_id = ?", doomed.getId())).isZero();
        assertThat(listAll(alice, 10)).containsExactly(kept.getId());
        assertThat(listAll(bob, 10)).isEmpty();
        bookmarkService.unbookmark(alice, doomed.getId()); // still a 204 no-op
    }

    @Test
    void deletingAnOriginalRemovesBookmarksOfItsDeletedReposts_butKeepsDetachedQuotes() {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(), author = UUID.randomUUID();
        var original = postService.createPost(author, "a", "orig", null);
        var repost = postService.repost(original.getId(), bob, "bob").post();
        var quote = postService.quote(original.getId(), bob, "bob", "q");
        bookmarkService.bookmark(alice, repost.getId()); // a repost item is bookmarked as given
        bookmarkService.bookmark(alice, quote.getId());

        postService.deletePost(original.getId(), author);

        var page = bookmarkService.page(alice, null, 10).posts();
        assertThat(page).extracting(Post::getId).containsExactly(quote.getId());
        var view = postService.views(page, alice).get(0);
        assertThat(view.bookmarked()).isTrue();
        assertThat(view.original()).isNull(); // "original unavailable"
    }

    @Test
    void bookmarkedFlagOfRepostsAndEmbeddedOriginals_usesOneBatchLookup() {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        var original = postService.createPost(bob, "bob", "orig", null);
        var repost = postService.repost(original.getId(), bob, "bob").post();
        bookmarkService.bookmark(alice, original.getId());

        var view = postService.views(List.of(repost), alice).get(0);

        assertThat(view.bookmarked()).isFalse(); // the item itself was not bookmarked
        assertThat(view.original().bookmarked()).isTrue(); // the embedded original was
    }
}
