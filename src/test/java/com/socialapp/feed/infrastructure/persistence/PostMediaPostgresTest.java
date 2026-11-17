package com.socialapp.feed.infrastructure.persistence;

import com.socialapp.feed.application.media.MediaRejectedException;
import com.socialapp.feed.application.media.MediaService;
import com.socialapp.feed.application.media.MediaView;
import com.socialapp.feed.application.media.TestImages;
import com.socialapp.feed.application.timeline.TimelineService;
import com.socialapp.feed.application.usecase.PostService;
import com.socialapp.feed.domain.Post;
import com.socialapp.feed.domain.PostMediaRepository;
import com.socialapp.feed.domain.PostRepository;
import com.socialapp.feed.infrastructure.media.MediaAdapters;
import com.socialapp.shared.domain.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage 3C against a real PostgreSQL (V22) and a real local-disk storage in a temp directory: upload, atomic attach
 * (ownership, double attach, concurrency), ordering, the batch read without N+1, cascade + file deletion on post
 * delete, the pending cap and the cleanup of expired uploads. Commit-per-call style as {@link RepostPostgresTest}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({PostService.class, TimelineService.class, MediaService.class, JdbcPostHashtagRepository.class,
        JdbcPostMentionRepository.class, MediaAdapters.class, RepostPostgresTest.StatementCounting.class})
class PostMediaPostgresTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static final Path MEDIA_DIR;

    static {
        try {
            MEDIA_DIR = Files.createTempDirectory("post-media-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.media.dir", MEDIA_DIR::toString);
        registry.add("app.media.cleanup-batch-size", () -> "2"); // several batches in the cleanup test
    }

    @Autowired PostService postService;
    @Autowired MediaService mediaService;
    @Autowired PostRepository posts;
    @Autowired PostMediaRepository media;
    @Autowired JdbcTemplate jdbc;

    private MediaView upload(UUID owner) {
        return mediaService.upload(owner, TestImages.png(12, 8));
    }

    private Path fileOf(MediaView view) {
        return MEDIA_DIR.resolve(view.url().substring(view.url().lastIndexOf('/') + 1));
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // ── upload ──────────────────────────────────────────────────────────────

    @Test
    void uploadStoresAnUnattachedRowAndAFileWithAServerGeneratedName() throws Exception {
        UUID alice = UUID.randomUUID();

        var view = mediaService.upload(alice, TestImages.png(12, 8));

        assertThat(view.width()).isEqualTo(12);
        assertThat(view.height()).isEqualTo(8);
        assertThat(view.url()).matches("http://localhost:8080/uploads/media/[0-9a-f-]{36}\\.png");
        assertThat(view.url()).doesNotContain(view.id().toString()); // storage key is unrelated to the id
        var row = media.findById(view.id()).orElseThrow();
        assertThat(row.getOwnerId()).isEqualTo(alice);
        assertThat(row.getPostId()).isNull();
        assertThat(row.getPosition()).isNull();
        assertThat(row.getContentType()).isEqualTo("image/png");
        assertThat(row.getSizeBytes()).isEqualTo(Files.size(fileOf(view)));
        assertThat(Files.readAllBytes(fileOf(view))).startsWith((byte) 0x89, (byte) 'P');
    }

    @Test
    void pendingUploadsAreCappedPerUser_andAttachingFreesCapacity() {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        List<MediaView> uploads = new ArrayList<>();
        for (int i = 0; i < 20; i++) uploads.add(upload(alice));

        assertThatThrownBy(() -> upload(alice)).isInstanceOfSatisfying(MediaRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(MediaRejectedException.Reason.TOO_MANY_PENDING));
        upload(bob); // per user

        postService.createPost(alice, "alice", "two pics", null, List.of(uploads.get(0).id(), uploads.get(1).id()));
        upload(alice);
        upload(alice);
        assertThatThrownBy(() -> upload(alice)).isInstanceOf(MediaRejectedException.class);
    }

    // ── attach ──────────────────────────────────────────────────────────────

    @Test
    void createAttachesInRequestOrder_andViewsReturnThemOrderedByPosition() {
        UUID alice = UUID.randomUUID();
        var a = upload(alice);
        var b = upload(alice);
        var c = upload(alice);

        var post = postService.createPost(alice, "alice", "three", null, List.of(c.id(), a.id(), b.id()));

        assertThat(jdbc.queryForList("SELECT id FROM post_media WHERE post_id = ? ORDER BY position", UUID.class,
                post.getId())).containsExactly(c.id(), a.id(), b.id());
        var view = postService.view(posts.findById(post.getId()).orElseThrow(), null);
        assertThat(view.media()).containsExactly(c, a, b);
    }

    @Test
    void attachingSomeoneElsesOrUnknownUploadIs404_andNothingIsCreated() {
        UUID alice = UUID.randomUUID(), mallory = UUID.randomUUID();
        var alices = upload(alice);
        var own = upload(mallory);

        assertThatThrownBy(() -> postService.createPost(mallory, "m", "steal", null, List.of(own.id(), alices.id())))
                .isInstanceOf(NotFoundException.class).hasMessage("Media not found: " + alices.id());
        assertThatThrownBy(() -> postService.createPost(mallory, "m", "x", null, List.of(UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);

        assertThat(count("SELECT count(*) FROM posts WHERE author_id = ?", mallory)).isZero(); // atomic
        assertThat(media.findById(own.id()).orElseThrow().getPostId()).isNull(); // own upload not attached either
        assertThat(media.findById(alices.id()).orElseThrow().getPostId()).isNull();
    }

    @Test
    void attachingTwiceIs400() {
        UUID alice = UUID.randomUUID();
        var m = upload(alice);
        postService.createPost(alice, "alice", "first", null, List.of(m.id()));

        assertThatThrownBy(() -> postService.createPost(alice, "alice", "again", null, List.of(m.id())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already attached");
        var original = postService.createPost(UUID.randomUUID(), "bob", "orig", null);
        assertThatThrownBy(() -> postService.quote(original.getId(), alice, "alice", "q", List.of(m.id())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tooManyOrDuplicateIdsAre400() {
        UUID alice = UUID.randomUUID();
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 5; i++) ids.add(upload(alice).id());

        assertThatThrownBy(() -> postService.createPost(alice, "alice", "five", null, ids))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> postService.createPost(alice, "alice", "dup", null, List.of(ids.get(0), ids.get(0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentAttachOfTheSameUploadSucceedsExactlyOnce() throws Exception {
        UUID alice = UUID.randomUUID();
        var m = upload(alice);
        int n = 6;
        var pool = Executors.newFixedThreadPool(n);
        try {
            var start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        postService.createPost(alice, "alice", "race " + k, null, List.of(m.id()));
                        return true;
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int ok = 0;
            for (var f : results) if (f.get()) ok++;
            assertThat(ok).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM posts WHERE author_id = ?", alice)).isEqualTo(1);
    }

    @Test
    void schemaEnforcesSlotsAndAttachmentInvariants() {
        UUID alice = UUID.randomUUID();
        var post = postService.createPost(alice, "alice", "p", null, List.of(upload(alice).id()));
        String insert = "INSERT INTO post_media (id, owner_id, post_id, position, content_type, size_bytes, storage_key) "
                + "VALUES (?, ?, ?, ?, 'image/png', 1, ?)";
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), alice, post.getId(), 0, "k1")) // slot taken
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), alice, post.getId(), 4, "k2"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), alice, null, 0, "k3"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), alice, UUID.randomUUID(), 1, "k4")) // FK
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── quotes, reposts, edits ──────────────────────────────────────────────

    @Test
    void quoteCarriesItsOwnMedia_repostNone_andTheEmbeddedOriginalShowsItsMedia() {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        var bobs = upload(bob);
        var original = postService.createPost(bob, "bob", "orig", null, List.of(bobs.id()));
        var alices = upload(alice);
        var quote = postService.quote(original.getId(), alice, "alice", "my take", List.of(alices.id()));
        var repost = postService.repost(original.getId(), alice, "alice").post();

        var views = postService.views(List.of(quote, repost), alice);

        assertThat(views.get(0).media()).containsExactly(alices);
        assertThat(views.get(0).original().media()).containsExactly(bobs);
        assertThat(views.get(1).media()).isEmpty();
        assertThat(views.get(1).original().media()).containsExactly(bobs);
    }

    @Test
    void editDoesNotChangeMedia() {
        UUID alice = UUID.randomUUID();
        var m = upload(alice);
        var post = postService.createPost(alice, "alice", "before", null, List.of(m.id()));

        postService.updatePost(post.getId(), alice, "after", null);

        assertThat(postService.view(posts.findById(post.getId()).orElseThrow(), alice).media()).containsExactly(m);
    }

    // ── delete ──────────────────────────────────────────────────────────────

    @Test
    void deletingAPostCascadesItsRowsAndDeletesItsFiles_quotesKeepTheirs() {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        var b1 = upload(bob);
        var b2 = upload(bob);
        var original = postService.createPost(bob, "bob", "orig", null, List.of(b1.id(), b2.id()));
        var a1 = upload(alice);
        var quote = postService.quote(original.getId(), alice, "alice", "q", List.of(a1.id()));
        postService.repost(original.getId(), alice, "alice");

        postService.deletePost(original.getId(), bob);

        assertThat(media.findAllById(List.of(b1.id(), b2.id()))).isEmpty();
        assertThat(fileOf(b1)).doesNotExist();
        assertThat(fileOf(b2)).doesNotExist();
        assertThat(media.findById(a1.id())).isPresent();
        assertThat(fileOf(a1)).exists();
        var kept = postService.view(posts.findById(quote.getId()).orElseThrow(), alice);
        assertThat(kept.original()).isNull();
        assertThat(kept.media()).containsExactly(a1);
    }

    @Test
    void aFailedDeleteKeepsTheFiles() {
        UUID alice = UUID.randomUUID();
        var m = upload(alice);
        var post = postService.createPost(alice, "alice", "mine", null, List.of(m.id()));

        assertThatThrownBy(() -> postService.deletePost(post.getId(), UUID.randomUUID())) // not the author
                .isInstanceOf(com.socialapp.shared.domain.ForbiddenOperationException.class);

        assertThat(fileOf(m)).exists();
        assertThat(media.findById(m.id())).isPresent();
    }

    @Test
    void aMissingFileNeverBreaksTheList() throws Exception {
        UUID alice = UUID.randomUUID();
        var m = upload(alice);
        var post = postService.createPost(alice, "alice", "pic", null, List.of(m.id()));
        Files.delete(fileOf(m));

        var view = postService.views(List.of(posts.findById(post.getId()).orElseThrow()), alice).get(0);

        assertThat(view.media()).containsExactly(m);
        postService.deletePost(post.getId(), alice); // file already gone: delete still succeeds
        assertThat(posts.findById(post.getId())).isEmpty();
    }

    // ── cleanup ─────────────────────────────────────────────────────────────

    @Test
    void cleanupDeletesOnlyExpiredUnattachedUploads_rowsAndFiles() {
        UUID alice = UUID.randomUUID();
        List<MediaView> expired = List.of(upload(alice), upload(alice), upload(alice), upload(alice), upload(alice));
        var fresh = upload(alice);
        var attachedOld = upload(alice);
        postService.createPost(alice, "alice", "kept", null, List.of(attachedOld.id()));
        for (var m : expired) {
            jdbc.update("UPDATE post_media SET created_at = now() - interval '25 hours' WHERE id = ?", m.id());
        }
        jdbc.update("UPDATE post_media SET created_at = now() - interval '25 hours' WHERE id = ?", attachedOld.id());

        int deleted = mediaService.purgeExpired(); // batch size 2: three batches

        assertThat(deleted).isGreaterThanOrEqualTo(5); // other tests' expired rows may be purged too
        for (var m : expired) {
            assertThat(media.findById(m.id())).isEmpty();
            assertThat(fileOf(m)).doesNotExist();
        }
        assertThat(media.findById(fresh.id())).isPresent();
        assertThat(fileOf(fresh)).exists();
        assertThat(media.findById(attachedOld.id())).isPresent();
        assertThat(fileOf(attachedOld)).exists();
        assertThat(mediaService.purgeExpired()).isZero();
    }

    // ── N+1 guard ───────────────────────────────────────────────────────────

    @Test
    void mediaOfAPageIsOneQueryWhateverThePageSize() {
        UUID author = UUID.randomUUID(), viewer = UUID.randomUUID();
        List<Post> page = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            var original = postService.createPost(author, "a", "o" + i, null, List.of(upload(author).id()));
            page.add(postService.quote(original.getId(), viewer, "v", "q" + i, List.of(upload(viewer).id())));
            page.add(postService.repost(original.getId(), viewer, "v").post());
            jdbc.update("DELETE FROM post_media WHERE owner_id IN (?, ?) AND post_id IS NULL", author, viewer);
        }

        RepostPostgresTest.STATEMENTS.set(0);
        var small = postService.views(page.subList(0, 4), viewer);
        int smallCount = RepostPostgresTest.STATEMENTS.get();
        RepostPostgresTest.STATEMENTS.set(0);
        var large = postService.views(page, viewer);
        int largeCount = RepostPostgresTest.STATEMENTS.get();

        assertThat(largeCount).isEqualTo(smallCount);
        assertThat(large).allSatisfy(v -> assertThat(v.original().media()).hasSize(1));
        assertThat(large.get(0).media()).hasSize(1);
        assertThat(small.get(1).media()).isEmpty(); // repost
    }
}
