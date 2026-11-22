package com.socialapp.feed.application.usecase;

import com.socialapp.feed.application.timeline.FeedCursor;
import com.socialapp.feed.domain.BlockMuteRepository;
import com.socialapp.feed.domain.BookmarkRepository;
import com.socialapp.feed.domain.Post;
import com.socialapp.feed.domain.PostRepository;
import com.socialapp.shared.domain.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BookmarkServiceTest {

    private static final UUID ALICE = UUID.randomUUID();

    private final BookmarkRepository bookmarks = mock(BookmarkRepository.class);
    private final PostRepository posts = mock(PostRepository.class);
    private final BlockMuteRepository blockMutes = mock(BlockMuteRepository.class);
    private final BookmarkService service = new BookmarkService(bookmarks, posts, new PostVisibility(blockMutes, posts));

    @Test
    void bookmarkingAnExistingPostInsertsIdempotently_andRepeatingIsFine() {
        var post = Post.create(UUID.randomUUID(), "bob", "hi", null);
        var postId = post.getId();
        when(posts.findById(postId)).thenReturn(Optional.of(post));

        service.bookmark(ALICE, postId);
        service.bookmark(ALICE, postId);

        verify(bookmarks, times(2)).add(ALICE, postId); // ON CONFLICT DO NOTHING makes the repeat a no-op
    }

    @Test
    void bookmarkingAMissingPostIsNotFound_andWritesNothing() {
        when(posts.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.bookmark(ALICE, UUID.randomUUID())).isInstanceOf(NotFoundException.class);
        verify(bookmarks, never()).add(any(), any());
    }

    @Test
    void unbookmarkIsIdempotentAndNeverChecksThePost() {
        var postId = UUID.randomUUID();
        when(bookmarks.remove(ALICE, postId)).thenReturn(0);

        service.unbookmark(ALICE, postId);

        verify(bookmarks).remove(ALICE, postId);
        verifyNoInteractions(posts);
    }

    @Test
    void invalidSizeIs400() {
        assertThatThrownBy(() -> service.page(ALICE, null, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyListHasNoCursor() {
        when(bookmarks.findPageAfter(any(), any(), any(), anyInt())).thenReturn(List.of());

        var page = service.page(ALICE, null, 20);

        assertThat(page.posts()).isEmpty();
        assertThat(page.next()).isNull();
        verify(bookmarks).findPageAfter(ALICE, FeedCursor.START.createdAt(), FeedCursor.START.id(), 21);
    }

    @Test
    void sizeIsClampedTo100() {
        when(bookmarks.findPageAfter(any(), any(), any(), anyInt())).thenReturn(List.of());

        service.page(ALICE, null, 5000);

        verify(bookmarks).findPageAfter(any(), any(), any(), eq(101));
    }
}
