package com.socialapp.feed.application.timeline;

import com.socialapp.feed.domain.Post;
import com.socialapp.feed.domain.PostRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Page assembly logic; the SQL itself is covered by TimelinePostgresTest. */
class TimelineServiceTest {

    private static final UUID VIEWER = UUID.randomUUID();
    private final PostRepository posts = mock(PostRepository.class);
    private final TimelineService service = new TimelineService(posts);

    private static List<Post> posts(int n) {
        return IntStream.range(0, n).mapToObj(i -> Post.create(UUID.randomUUID(), "u", "p" + i, null)).toList();
    }

    @Test
    void emptyTimelineHasNoNextCursor() {
        when(posts.findPageAfter(any(), any(), any(), anyInt())).thenReturn(List.of());

        var page = service.page(VIEWER, FeedScope.ALL, null, 20);

        assertThat(page.posts()).isEmpty();
        assertThat(page.next()).isNull();
        verify(posts).findPageAfter(VIEWER, FeedCursor.START.createdAt(), FeedCursor.START.id(), 21);
    }

    @Test
    void exactlyOneFullPageLeftHasNoNextCursor() {
        when(posts.findPageAfter(any(), any(), any(), anyInt())).thenReturn(posts(3));

        var page = service.page(VIEWER, FeedScope.ALL, FeedCursor.START, 3);

        assertThat(page.posts()).hasSize(3);
        assertThat(page.next()).isNull();
    }

    @Test
    void morePostsThanSizeYieldCursorOfLastReturnedPost() {
        var rows = posts(4);
        when(posts.findPageAfter(any(), any(), any(), anyInt())).thenReturn(rows);

        var page = service.page(VIEWER, FeedScope.ALL, FeedCursor.START, 3);

        assertThat(page.posts()).containsExactlyElementsOf(rows.subList(0, 3));
        assertThat(page.next()).isEqualTo(new FeedCursor(rows.get(2).getCreatedAt(), rows.get(2).getId()));
    }

    @Test
    void cursorIsPassedThroughAndFollowingScopeUsesTheViewer() {
        var cursor = new FeedCursor(java.time.Instant.parse("2026-01-01T00:00:00Z"), UUID.randomUUID());
        when(posts.findFollowingPageAfter(any(), any(), any(), anyInt())).thenReturn(List.of());

        service.page(VIEWER, FeedScope.FOLLOWING, cursor, 5);

        verify(posts).findFollowingPageAfter(VIEWER, cursor.createdAt(), cursor.id(), 6);
        verify(posts, never()).findPageAfter(any(), any(), any(), anyInt());
    }

    @Test
    void sizeIsClampedToMaxAndMustBePositive() {
        when(posts.findPageAfter(any(), any(), any(), anyInt())).thenReturn(List.of());

        service.page(VIEWER, FeedScope.ALL, null, 5000);
        verify(posts).findPageAfter(any(), any(), any(), eq(TimelineService.MAX_SIZE + 1));

        assertThatThrownBy(() -> service.page(VIEWER, FeedScope.ALL, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.page(VIEWER, FeedScope.ALL, null, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
