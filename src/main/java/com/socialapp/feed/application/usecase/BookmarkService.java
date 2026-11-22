package com.socialapp.feed.application.usecase;

import com.socialapp.feed.application.timeline.FeedCursor;
import com.socialapp.feed.application.timeline.FeedPage;
import com.socialapp.feed.application.timeline.TimelineService;
import com.socialapp.feed.domain.Bookmark;
import com.socialapp.feed.domain.BookmarkRepository;
import com.socialapp.feed.domain.Post;
import com.socialapp.feed.domain.PostRepository;
import com.socialapp.shared.domain.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Private bookmarks ("saved posts"). A bookmark belongs to its user only: every operation is scoped by the caller's
 * id. Bookmarking a repost item bookmarks that item (the id as given), not its original. Deleting a post cascades
 * to its bookmarks (V21 FK), and the list skips a post that disappears between the two reads. Posts hidden by a block
 * (either direction, stage 5A) are skipped by the list query and cannot be bookmarked; the rows are kept, so they come
 * back after an unblock. Mutes do not affect bookmarks.
 */
@Service
@RequiredArgsConstructor
public class BookmarkService {

    private final BookmarkRepository bookmarkRepository;
    private final PostRepository postRepository;
    private final PostVisibility visibility;

    /**
     * Idempotent: bookmarking again keeps the original position in the list.
     *
     * @throws NotFoundException unknown/deleted post, or one hidden from the user by a block (stage 5A)
     */
    @Transactional
    public void bookmark(UUID userId, UUID postId) {
        var post = postRepository.findById(postId).orElseThrow(() -> new NotFoundException("Post not found: " + postId));
        visibility.ensureNotBlocked(post, userId);
        bookmarkRepository.add(userId, postId);
    }

    /** Idempotent: no bookmark (or an unknown/deleted post) is a no-op. */
    @Transactional
    public void unbookmark(UUID userId, UUID postId) {
        bookmarkRepository.remove(userId, postId);
    }

    /**
     * The caller's bookmarked posts, most recently bookmarked first, keyset-paged by (bookmarked_at, post id); the
     * cursor reuses the {@link FeedCursor} format with the bookmark time. Two queries (bookmarks, then their posts).
     *
     * @throws IllegalArgumentException (400) if {@code size < 1}
     */
    @Transactional(readOnly = true)
    public FeedPage page(UUID userId, FeedCursor cursor, int size) {
        if (size < 1) throw new IllegalArgumentException("size must be between 1 and " + TimelineService.MAX_SIZE);
        int pageSize = Math.min(size, TimelineService.MAX_SIZE);
        var after = cursor == null ? FeedCursor.START : cursor;

        List<Bookmark> rows = bookmarkRepository.findPageAfter(userId, after.createdAt(), after.id(), pageSize + 1);
        boolean more = rows.size() > pageSize;
        var bookmarks = more ? rows.subList(0, pageSize) : rows;
        Map<UUID, Post> posts = postRepository
                .findAllById(bookmarks.stream().map(Bookmark::getPostId).toList()).stream()
                .collect(Collectors.toMap(Post::getId, Function.identity()));
        var items = bookmarks.stream().map(b -> posts.get(b.getPostId())).filter(p -> p != null).toList();
        if (!more) return new FeedPage(items, null);
        var last = bookmarks.get(pageSize - 1);
        return new FeedPage(items, new FeedCursor(last.getCreatedAt(), last.getPostId()));
    }
}
