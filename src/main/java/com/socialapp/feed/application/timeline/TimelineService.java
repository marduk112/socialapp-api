package com.socialapp.feed.application.timeline;

import com.socialapp.feed.domain.HashtagParser;
import com.socialapp.feed.domain.Post;
import com.socialapp.feed.domain.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Timelines with keyset pagination over {@code (createdAt DESC, id DESC)}.
 *
 * <p>Unlike offset paging, a page is defined by the key of the previous page's last post, so posts created
 * (or deleted) between two requests never cause duplicates or skips of the posts being paged through: new
 * posts sort before the cursor and simply are not part of the remaining pages. {@code id} breaks ties
 * between equal {@code createdAt}. One extra row is fetched to know whether a next page exists, so the
 * last page reports {@code next = null} without an additional request.
 *
 * <p>{@link FeedScope#FOLLOWING} uses Feed's {@code follows} projection (eventually consistent with the
 * social graph, typically within a second).
 */
@Service
@RequiredArgsConstructor
public class TimelineService {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    public static final int MAX_QUERY_LENGTH = 100;

    private final PostRepository postRepository;

    /**
     * @param size page size; values above {@link #MAX_SIZE} are clamped to it
     * @throws IllegalArgumentException (400) if {@code size < 1}
     */
    @Transactional(readOnly = true)
    public FeedPage page(UUID viewerId, FeedScope scope, FeedCursor cursor, int size) {
        int pageSize = pageSize(size);
        var after = cursor == null ? FeedCursor.START : cursor;

        List<Post> rows = switch (scope == null ? FeedScope.ALL : scope) {
            case ALL -> postRepository.findPageAfter(after.createdAt(), after.id(), pageSize + 1);
            case FOLLOWING -> postRepository.findFollowingPageAfter(viewerId, after.createdAt(), after.id(),
                    pageSize + 1);
        };
        return toPage(rows, pageSize);
    }

    /**
     * Posts carrying a hashtag, same ordering, cursor and size rules as {@link #page}.
     *
     * @param tag as given by the client: case-insensitive, one leading {@code #} tolerated
     * @throws IllegalArgumentException (400) for an invalid tag or {@code size < 1}
     */
    @Transactional(readOnly = true)
    public FeedPage hashtagPage(String tag, FeedCursor cursor, int size) {
        String normalised = HashtagParser.normaliseQuery(tag);
        int pageSize = pageSize(size);
        var after = cursor == null ? FeedCursor.START : cursor;
        return toPage(postRepository.findHashtagPageAfter(normalised, after.createdAt(), after.id(), pageSize + 1),
                pageSize);
    }

    /**
     * Full-text search over post text, same ordering, cursor and size rules as {@link #page} (newest matches first).
     *
     * @param query user input; trimmed, 1..{@value #MAX_QUERY_LENGTH} code points
     * @throws IllegalArgumentException (400) for a blank/too long query or {@code size < 1}
     */
    @Transactional(readOnly = true)
    public FeedPage searchPage(String query, FeedCursor cursor, int size) {
        String q = query == null ? "" : query.trim();
        int length = q.codePointCount(0, q.length());
        if (length < 1 || length > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("q must be 1 to " + MAX_QUERY_LENGTH + " characters");
        }
        int pageSize = pageSize(size);
        var after = cursor == null ? FeedCursor.START : cursor;
        return toPage(postRepository.findSearchPageAfter(q, after.createdAt(), after.id(), pageSize + 1), pageSize);
    }

    private static int pageSize(int size) {
        if (size < 1) throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
        return Math.min(size, MAX_SIZE);
    }

    /** {@code rows} holds up to pageSize + 1 posts; the extra one only signals that a next page exists. */
    private static FeedPage toPage(List<Post> rows, int pageSize) {
        if (rows.size() <= pageSize) return new FeedPage(List.copyOf(rows), null);

        var posts = List.copyOf(rows.subList(0, pageSize));
        var last = posts.get(pageSize - 1);
        return new FeedPage(posts, new FeedCursor(last.getCreatedAt(), last.getId()));
    }
}
