package com.socialapp.feed.infrastructure.web;

import com.socialapp.config.ValidationConfig;
import com.socialapp.config.security.SecurityConfig;
import com.socialapp.feed.application.timeline.TimelineService;
import com.socialapp.feed.application.usecase.BookmarkService;
import com.socialapp.feed.application.usecase.PostService;
import com.socialapp.feed.domain.*;
import com.socialapp.feed.infrastructure.sse.FeedSseService;
import com.socialapp.usermanagement.infrastructure.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Web slice of the bookmark endpoints (stage 3B): real security chain, services and domain, mocked ports. */
@WebMvcTest(controllers = PostController.class)
@Import({SecurityConfig.class, JwtService.class, PostService.class, TimelineService.class, BookmarkService.class,
        ValidationConfig.class})
class BookmarkControllerTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;

    @MockitoBean PostRepository postRepository;
    @MockitoBean PostReactionRepository postReactionRepository;
    @MockitoBean CommentRepository commentRepository;
    @MockitoBean CommentReactionRepository commentReactionRepository;
    @MockitoBean AuthorProfileRepository authorProfileRepository;
    @MockitoBean PostHashtagRepository postHashtagRepository;
    @MockitoBean PostMentionRepository postMentionRepository;
    @MockitoBean BookmarkRepository bookmarkRepository;
    @MockitoBean FeedSseService feedSseService;
    @MockitoBean PostMediaRepository postMediaRepository;
    @MockitoBean com.socialapp.feed.application.media.MediaStorage mediaStorage;

    private String bearer(UUID userId) {
        return "Bearer " + jwtService.issueToken(userId, "user");
    }

    @Test
    void allThreeEndpointsRequireAuthentication() throws Exception {
        var id = UUID.randomUUID();
        mvc.perform(put("/api/v1/posts/{id}/bookmark", id)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/posts/{id}/bookmark", id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/bookmarks")).andExpect(status().isUnauthorized());
        verifyNoInteractions(bookmarkRepository);
    }

    @Test
    void putIs204AndBookmarksForThePrincipalOnly() throws Exception {
        var post = Post.create(BOB, "bob", "hi", null);
        when(postRepository.existsById(post.getId())).thenReturn(true);

        mvc.perform(put("/api/v1/posts/{id}/bookmark", post.getId())
                        .param("userId", BOB.toString()) // ignored
                        .header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                .andExpect(status().isNoContent());

        verify(bookmarkRepository).add(ALICE, post.getId());
    }

    @Test
    void putOnAMissingPostIs404() throws Exception {
        when(postRepository.existsById(any())).thenReturn(false);

        mvc.perform(put("/api/v1/posts/{id}/bookmark", UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
        verify(bookmarkRepository, never()).add(any(), any());
    }

    @Test
    void deleteIs204EvenWhenNothingWasBookmarked() throws Exception {
        var id = UUID.randomUUID();
        when(bookmarkRepository.remove(ALICE, id)).thenReturn(0);

        mvc.perform(delete("/api/v1/posts/{id}/bookmark", id).header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                .andExpect(status().isNoContent());
        verify(bookmarkRepository).remove(ALICE, id);
    }

    @Test
    void listHasTheFeedEnvelope_withBookmarkedTrue_andOpaqueCursorOfTheBookmarkTime() throws Exception {
        var p1 = Post.create(BOB, "bob", "one", null);
        var p2 = Post.create(BOB, "bob", "two", null);
        var b1 = bookmark(ALICE, p1.getId(), Instant.parse("2025-01-02T00:00:00Z"));
        var b2 = bookmark(ALICE, p2.getId(), Instant.parse("2025-01-01T00:00:00Z"));
        when(bookmarkRepository.findPageAfter(any(), any(), any(), anyInt())).thenReturn(List.of(b1, b2));
        when(postRepository.findAllById(any())).thenReturn(List.of(p2, p1)); // order of the bookmarks must win
        when(bookmarkRepository.findBookmarkedPostIds(any(), any())).thenReturn(List.of(p1.getId(), p2.getId()));

        var json = mvc.perform(get("/api/v1/bookmarks").param("size", "1").header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(1)))
                .andExpect(jsonPath("$.items[0].id", is(p1.getId().toString())))
                .andExpect(jsonPath("$.items[0].bookmarked", is(true)))
                .andExpect(jsonPath("$.items[0].viewerReposted", is(false)))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn().getResponse().getContentAsString();

        var cursor = com.jayway.jsonpath.JsonPath.<String>read(json, "$.nextCursor");
        var decoded = com.socialapp.feed.application.timeline.FeedCursor.decode(cursor);
        org.assertj.core.api.Assertions.assertThat(decoded.createdAt()).isEqualTo(b1.getCreatedAt());
        org.assertj.core.api.Assertions.assertThat(decoded.id()).isEqualTo(p1.getId());
        verify(bookmarkRepository).findPageAfter(eq(ALICE), any(), any(), eq(2));
    }

    @Test
    void emptyListIsAnEmptyPageWithNullCursor() throws Exception {
        when(bookmarkRepository.findPageAfter(any(), any(), any(), anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/v1/bookmarks").header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(0)))
                .andExpect(jsonPath("$.nextCursor").hasJsonPath())
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
        verify(bookmarkRepository).findPageAfter(eq(ALICE), any(), any(), eq(21));
    }

    @Test
    void malformedCursorAndInvalidSizeAre400() throws Exception {
        mvc.perform(get("/api/v1/bookmarks").param("cursor", "not-a-cursor").header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/bookmarks").param("size", "0").header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/bookmarks").param("size", "abc").header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sizeAbove100IsClamped() throws Exception {
        when(bookmarkRepository.findPageAfter(any(), any(), any(), anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/v1/bookmarks").param("size", "999").header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                .andExpect(status().isOk());
        verify(bookmarkRepository).findPageAfter(any(), any(), any(), eq(101));
    }

    @Test
    void bookmarkedFlagIsInFeedAndSinglePost_batchLookedUpOncePerPage() throws Exception {
        var p1 = Post.create(BOB, "bob", "one", null);
        var p2 = Post.create(BOB, "bob", "two", null);
        when(postRepository.findPageAfter(any(), any(), anyInt())).thenReturn(List.of(p1, p2));
        when(postRepository.findById(p1.getId())).thenReturn(Optional.of(p1));
        when(bookmarkRepository.findBookmarkedPostIds(any(), any())).thenReturn(List.of(p2.getId()))
                .thenReturn(List.of(p1.getId()));

        mvc.perform(get("/api/v1/feed").header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].bookmarked", is(false)))
                .andExpect(jsonPath("$.items[1].bookmarked", is(true)));
        verify(bookmarkRepository, times(1)).findBookmarkedPostIds(ALICE, List.of(p1.getId(), p2.getId()));

        mvc.perform(get("/api/v1/posts/{id}", p1.getId()).header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookmarked", is(true)));
    }

    private static Bookmark bookmark(UUID user, UUID post, Instant at) {
        try {
            var ctor = Bookmark.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            var b = ctor.newInstance();
            set(b, "userId", user);
            set(b, "postId", post);
            set(b, "createdAt", at);
            return b;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object target, String field, Object value) throws ReflectiveOperationException {
        var f = Bookmark.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
