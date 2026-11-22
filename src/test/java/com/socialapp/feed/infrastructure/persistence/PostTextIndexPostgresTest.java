package com.socialapp.feed.infrastructure.persistence;

import com.socialapp.feed.application.timeline.FeedCursor;
import com.socialapp.feed.application.timeline.TimelineService;
import com.socialapp.feed.application.usecase.PostService;
import com.socialapp.feed.domain.AuthorProfile;
import com.socialapp.feed.domain.AuthorProfileRepository;
import com.socialapp.feed.domain.Mention;
import com.socialapp.feed.domain.Post;
import com.socialapp.feed.domain.PostHashtagRepository;
import com.socialapp.feed.domain.PostMentionRepository;
import com.socialapp.feed.domain.PostRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hashtag/mention persistence (V17 schema, JDBC adapters inside the JPA transaction), mention resolution
 * against {@code author_profiles} and the hashtag timeline's keyset pagination against a real PostgreSQL.
 * Each test runs in a rolled-back transaction.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import({PostService.class, BlockMuteAdapters.class, TimelineService.class, JdbcPostHashtagRepository.class, JdbcPostMentionRepository.class, com.socialapp.feed.infrastructure.media.MediaAdapters.class})
class PostTextIndexPostgresTest {

    /** A viewer without any block or mute. */
    private static final java.util.UUID VIEWER = java.util.UUID.randomUUID();

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

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

    @Autowired PostService postService;
    @Autowired TimelineService timeline;
    @Autowired PostRepository posts;
    @Autowired PostHashtagRepository hashtags;
    @Autowired PostMentionRepository mentions;
    @Autowired AuthorProfileRepository profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    @BeforeEach
    void users() {
        profiles.save(new AuthorProfile(ALICE, "alice", null, null));
        profiles.save(new AuthorProfile(BOB, "Bob", null, null));
        profiles.save(new AuthorProfile(CAROL, "carol.x", null, null));
        profiles.save(new AuthorProfile(UUID.randomUUID(), null, null, null)); // legacy row without username
        profiles.flush();
    }

    private List<String> tagsOf(UUID postId) {
        return jdbc.queryForList("SELECT tag FROM post_hashtags WHERE post_id = ? ORDER BY position", String.class, postId);
    }

    private List<Mention> mentionsOf(UUID postId) {
        return mentions.findByPostIds(List.of(postId)).getOrDefault(postId, List.of());
    }

    // ── persistence on create / edit / share ────────────────────────────────

    @Test
    void createPersistsNormalisedTagsInOrderAndResolvedMentions() {
        var post = postService.createPost(ALICE, "alice", "#Hello world #hello #Zażółć @bob @CAROL.X. @ghost @alice", null);

        assertThat(tagsOf(post.getId())).containsExactly("hello", "zażółć");
        // case-insensitive, canonical username, trailing '.' trimmed; unknown and self dropped
        assertThat(mentionsOf(post.getId())).containsExactly(new Mention(BOB, "Bob"), new Mention(CAROL, "carol.x"));
    }

    @Test
    void hashtagRowsCopyThePostsCreatedAtExactly() {
        var post = postService.createPost(ALICE, "alice", "#a #b", null);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM post_hashtags h JOIN posts p ON p.id = h.post_id
                WHERE h.post_id = ? AND h.created_at = p.created_at""", Long.class, post.getId())).isEqualTo(2);
    }

    @Test
    void editReplacesBothSets() {
        var post = postService.createPost(ALICE, "alice", "#old #keep @bob", null);

        postService.updatePost(post.getId(), ALICE, "#keep #new @carol.x", null);

        assertThat(tagsOf(post.getId())).containsExactly("keep", "new");
        assertThat(mentionsOf(post.getId())).containsExactly(new Mention(CAROL, "carol.x"));

        postService.updatePost(post.getId(), ALICE, "nothing left", null);
        assertThat(tagsOf(post.getId())).isEmpty();
        assertThat(mentionsOf(post.getId())).isEmpty();
    }

    @Test
    void shareIndexesItsOwnCommentNotTheOriginal() {
        var original = postService.createPost(BOB, "Bob", "#original @alice", null);

        var share = postService.quote(original.getId(), ALICE, "alice", "see #Shared @bob");
        var bare = postService.repost(original.getId(), CAROL, "carol.x").post();

        assertThat(tagsOf(share.getId())).containsExactly("shared");
        assertThat(mentionsOf(share.getId())).containsExactly(new Mention(BOB, "Bob"));
        assertThat(tagsOf(bare.getId())).isEmpty();
        assertThat(tagsOf(original.getId())).containsExactly("original");
    }

    @Test
    void deletingAPostCascadesToItsIndexRows() {
        var post = postService.createPost(ALICE, "alice", "#gone @bob", null);

        postService.deletePost(post.getId(), ALICE);
        posts.flush();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM post_hashtags WHERE post_id = ?", Long.class, post.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post_mentions WHERE post_id = ?", Long.class, post.getId())).isZero();
    }

    @Test
    void overLimitPostIsRejectedAndNothingIsWritten() {
        long before = posts.count();
        assertThatThrownBy(() -> postService.createPost(ALICE, "alice", "#x " + "a".repeat(498), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("500");
        assertThat(posts.count()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post_hashtags WHERE tag = 'x'", Long.class)).isZero();
    }

    @Test
    void indexingAnUnflushedOrMissingPostFailsLoudly() {
        assertThatThrownBy(() -> hashtags.replace(UUID.randomUUID(), List.of("x")))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── batch loading ───────────────────────────────────────────────────────

    @Test
    void batchLoadGroupsByPostInDisplayOrder() {
        var p1 = postService.createPost(ALICE, "alice", "#z #a #m @bob", null);
        var p2 = postService.createPost(BOB, "Bob", "@carol.x @alice", null);
        var p3 = postService.createPost(CAROL, "carol.x", "plain", null);

        var views = postService.views(List.of(p3, p1, p2), null);

        assertThat(views).extracting(v -> v.post().getId()).containsExactly(p3.getId(), p1.getId(), p2.getId());
        assertThat(views.get(0).hashtags()).isEmpty();
        assertThat(views.get(1).hashtags()).containsExactly("z", "a", "m");
        assertThat(views.get(1).mentions()).containsExactly(new Mention(BOB, "Bob"));
        assertThat(views.get(2).mentions()).containsExactly(new Mention(CAROL, "carol.x"), new Mention(ALICE, "alice"));
        assertThat(hashtags.findByPostIds(List.of())).isEmpty();
        assertThat(mentions.findByPostIds(List.of())).isEmpty();
    }

    // ── hashtag timeline ────────────────────────────────────────────────────

    private UUID taggedPost(Instant createdAt, String... tags) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO posts (id, author_id, author_username, content, created_at, updated_at)
                VALUES (?, ?, 'u', 'c', ?, ?)""", id, BOB, createdAt.atOffset(ZoneOffset.UTC),
                createdAt.atOffset(ZoneOffset.UTC));
        hashtags.replace(id, List.of(tags));
        return id;
    }

    private List<UUID> pageThrough(String tag, int size) {
        List<UUID> seen = new ArrayList<>();
        FeedCursor cursor = null;
        for (int i = 0; i < 100; i++) {
            var page = timeline.hashtagPage(VIEWER, tag, cursor, size);
            page.posts().forEach(p -> seen.add(p.getId()));
            if (page.next() == null) return seen;
            assertThat(page.posts()).hasSize(size);
            cursor = FeedCursor.decode(page.next().encode());
        }
        throw new AssertionError("pagination did not terminate");
    }

    private List<UUID> expected(String tag) {
        return jdbc.queryForList("""
                SELECT p.id FROM posts p JOIN post_hashtags h ON h.post_id = p.id WHERE h.tag = ?
                ORDER BY p.created_at DESC, p.id DESC""", UUID.class, tag);
    }

    @Test
    void unknownTagIsOneEmptyLastPage() {
        taggedPost(T0, "other");

        var page = timeline.hashtagPage(VIEWER, "nope", null, 20);

        assertThat(page.posts()).isEmpty();
        assertThat(page.next()).isNull();
    }

    @Test
    void tagTimelineHasOnlyThatTagNewestFirst_andTheQueryIsCaseInsensitive() {
        UUID older = taggedPost(T0, "java", "spring");
        UUID newer = taggedPost(T0.plusSeconds(5), "java");
        taggedPost(T0.plusSeconds(10), "kotlin");

        assertThat(pageThrough("java", 20)).containsExactly(newer, older);
        assertThat(pageThrough("#JAVA", 20)).containsExactly(newer, older);
        assertThat(pageThrough("Spring", 20)).containsExactly(older);
    }

    @Test
    void tiesOnCreatedAtAreBrokenByIdWithoutDuplicatesOrSkips() {
        for (int i = 0; i < 7; i++) taggedPost(T0, "tie");
        taggedPost(T0.plusMillis(1), "tie");
        taggedPost(T0.minusMillis(1), "tie");
        taggedPost(T0, "different");

        for (int size : new int[] {1, 2, 3, 4, 9, 10}) {
            assertThat(pageThrough("tie", size)).as("size %d", size).containsExactlyElementsOf(expected("tie"));
        }
        assertThat(expected("tie")).hasSize(9);
    }

    @Test
    void lastPageHasNullCursor_exactMultipleNeedsNoExtraPage() {
        for (int i = 0; i < 4; i++) taggedPost(T0.plusSeconds(i), "four");

        var first = timeline.hashtagPage(VIEWER, "four", null, 2);
        var second = timeline.hashtagPage(VIEWER, "four", first.next(), 2);
        var partial = timeline.hashtagPage(VIEWER, "four", timeline.hashtagPage(VIEWER, "four", null, 3).next(), 3);

        assertThat(first.next()).isNotNull();
        assertThat(second.posts()).hasSize(2);
        assertThat(second.next()).isNull();
        assertThat(partial.posts()).hasSize(1);
        assertThat(partial.next()).isNull();
    }

    @Test
    void editingAwayATagRemovesThePostFromThatTimeline() {
        var post = postService.createPost(ALICE, "alice", "#Topic first", null);
        assertThat(pageThrough("topic", 5)).containsExactly(post.getId());

        postService.updatePost(post.getId(), ALICE, "#other now", null);
        em.flush();

        assertThat(pageThrough("topic", 5)).isEmpty();
        assertThat(pageThrough("other", 5)).containsExactly(post.getId());
    }
}
