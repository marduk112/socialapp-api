package com.socialapp.feed.infrastructure.web;

import com.socialapp.config.ValidationConfig;
import com.socialapp.config.security.SecurityConfig;
import com.socialapp.feed.application.timeline.FeedCursor;
import com.socialapp.feed.application.timeline.FeedScope;
import com.socialapp.feed.application.timeline.TimelineService;
import com.socialapp.feed.application.usecase.BookmarkService;
import com.socialapp.feed.application.usecase.PostService;
import com.socialapp.feed.application.usecase.PostVisibility;
import com.socialapp.feed.domain.*;
import com.socialapp.feed.infrastructure.sse.FeedSseService;
import com.socialapp.usermanagement.infrastructure.security.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web slice with the real security chain, real PostService and real domain objects;
 * only the persistence ports are mocked, so ownership checks are exercised end to end
 * (HTTP -> controller -> service -> aggregate). No Docker/infra required.
 */
@WebMvcTest(controllers = PostController.class)
@Import({SecurityConfig.class, JwtService.class, PostService.class, TimelineService.class, BookmarkService.class,
        PostVisibility.class, ValidationConfig.class})
class PostControllerSecurityTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Value("${app.jwt.secret}") String secret;

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
    @MockitoBean BlockMuteRepository blockMuteRepository;

    private String bearer(UUID userId, String username) {
        return "Bearer " + jwtService.issueToken(userId, username);
    }

    // ── Authentication ────────────────────────────────────────────────────────

    @Test
    void requestWithoutTokenIsRejectedWith401Json() throws Exception {
        mvc.perform(get("/api/v1/feed"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error", is("Authentication required")))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void malformedTokenIsRejectedWith401() throws Exception {
        mvc.perform(get("/api/v1/feed").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("Invalid or expired token")));
    }

    @Test
    void expiredTokenIsRejectedWith401() throws Exception {
        String expired = Jwts.builder()
                .issuer("social-app")
                .subject(ALICE.toString())
                .claim("username", "alice")
                .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .expiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();

        mvc.perform(get("/api/v1/feed").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenIsAccepted() throws Exception {
        var post = Post.create(ALICE, "alice", "hello", null);
        when(postRepository.findPageAfter(any(), any(), any(), anyInt())).thenReturn(List.of(post));

        mvc.perform(get("/api/v1/feed").header(HttpHeaders.AUTHORIZATION, bearer(BOB, "bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].content", is("hello")));
    }

    // ── Timeline contract: GET /feed?scope&cursor&size -> {items, nextCursor} ──

    @Test
    void feedDefaultsToScopeAllFirstPageSize20_andLastPageHasNullCursor() throws Exception {
        var post = Post.create(ALICE, "alice", "hello", null);
        when(postRepository.findPageAfter(any(), any(), any(), anyInt())).thenReturn(List.of(post));

        mvc.perform(get("/api/v1/feed").header(HttpHeaders.AUTHORIZATION, bearer(BOB, "bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(1)))
                .andExpect(jsonPath("$.items[0].id", is(post.getId().toString())))
                .andExpect(jsonPath("$.items[0].authorUsername", is("alice")))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.nextCursor").hasJsonPath());

        verify(postRepository).findPageAfter(BOB, FeedCursor.START.createdAt(), FeedCursor.START.id(), 21);
        verify(postRepository, never()).findFollowingPageAfter(any(), any(), any(), anyInt());
    }

    @Test
    void fullPageReturnsOpaqueCursorOfItsLastItem_whichSelectsTheNextPage() throws Exception {
        var p1 = Post.create(ALICE, "alice", "1", null);
        var p2 = Post.create(ALICE, "alice", "2", null);
        var p3 = Post.create(ALICE, "alice", "3", null);
        when(postRepository.findPageAfter(any(), any(), any(), anyInt())).thenReturn(List.of(p1, p2, p3));

        var body = mvc.perform(get("/api/v1/feed").param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(BOB, "bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(2)))
                .andReturn().getResponse().getContentAsString();
        String cursor = com.jayway.jsonpath.JsonPath.read(body, "$.nextCursor");
        assertThat(cursor).matches("[A-Za-z0-9_-]+");
        assertThat(FeedCursor.decode(cursor)).isEqualTo(new FeedCursor(p2.getCreatedAt(), p2.getId()));

        mvc.perform(get("/api/v1/feed").param("size", "2").param("cursor", cursor)
                        .header(HttpHeaders.AUTHORIZATION, bearer(BOB, "bob")))
                .andExpect(status().isOk());
        verify(postRepository).findPageAfter(BOB, p2.getCreatedAt(), p2.getId(), 3);
    }

    @Test
    void followingScopeQueriesThePostsOfTheCallersFollowees() throws Exception {
        when(postRepository.findFollowingPageAfter(any(), any(), any(), anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/v1/feed").param("scope", "following")
                        .header(HttpHeaders.AUTHORIZATION, bearer(BOB, "bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(0)))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));

        verify(postRepository).findFollowingPageAfter(BOB, FeedCursor.START.createdAt(), FeedCursor.START.id(), 21);
        verify(postRepository, never()).findPageAfter(any(), any(), any(), anyInt());
    }

    @Test
    void sizeAboveMaxIsClampedTo100() throws Exception {
        when(postRepository.findPageAfter(any(), any(), any(), anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/v1/feed").param("size", "1000")
                        .header(HttpHeaders.AUTHORIZATION, bearer(BOB, "bob")))
                .andExpect(status().isOk());

        verify(postRepository).findPageAfter(any(), any(), any(), org.mockito.ArgumentMatchers.eq(101));
    }

    @Test
    void invalidFeedParametersAre400Json() throws Exception {
        for (var params : List.of(
                new String[] {"cursor", "not-a-cursor!"},
                new String[] {"cursor", java.util.Base64.getUrlEncoder().encodeToString("v1|garbage|x".getBytes())},
                new String[] {"scope", "friends"},
                new String[] {"size", "0"},
                new String[] {"size", "abc"})) {
            mvc.perform(get("/api/v1/feed").param(params[0], params[1])
                            .header(HttpHeaders.AUTHORIZATION, bearer(BOB, "bob")))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.error").isString())
                    .andExpect(jsonPath("$.timestamp").exists());
        }
        verifyNoInteractions(postRepository);
    }

    @Test
    void oldPageParamIsIgnored() throws Exception {
        when(postRepository.findPageAfter(any(), any(), any(), anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/v1/feed").param("page", "3")
                        .header(HttpHeaders.AUTHORIZATION, bearer(BOB, "bob")))
                .andExpect(status().isOk());

        verify(postRepository).findPageAfter(BOB, FeedCursor.START.createdAt(), FeedCursor.START.id(), 21);
    }

    // ── SSE query-param token ─────────────────────────────────────────────────

    @Test
    void sseStreamAcceptsAccessTokenQueryParam() throws Exception {
        var emitter = new SseEmitter();
        when(feedSseService.subscribe(ALICE, FeedScope.ALL)).thenReturn(emitter);

        var result = mvc.perform(get("/api/v1/feed/stream")
                        .param("access_token", jwtService.issueToken(ALICE, "alice")))
                .andExpect(request().asyncStarted())
                .andReturn();

        // The ASYNC re-dispatch (stream completion) carries no token and must not be rejected
        emitter.complete();
        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk());
    }

    @Test
    void sseStreamPassesTheCallerAndFollowingScope() throws Exception {
        var emitter = new SseEmitter();
        when(feedSseService.subscribe(BOB, FeedScope.FOLLOWING)).thenReturn(emitter);

        mvc.perform(get("/api/v1/feed/stream")
                        .param("access_token", jwtService.issueToken(BOB, "bob"))
                        .param("scope", "following"))
                .andExpect(request().asyncStarted());

        verify(feedSseService).subscribe(BOB, FeedScope.FOLLOWING);
        emitter.complete();
    }

    @Test
    void sseStreamWithInvalidScopeIs400() throws Exception {
        mvc.perform(get("/api/v1/feed/stream")
                        .param("access_token", jwtService.issueToken(BOB, "bob"))
                        .param("scope", "nope"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(feedSseService);
    }

    @Test
    void sseStreamWithoutTokenIsRejected() throws Exception {
        mvc.perform(get("/api/v1/feed/stream"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(feedSseService);
    }

    @Test
    void accessTokenQueryParamIsIgnoredOutsideSseStream() throws Exception {
        mvc.perform(get("/api/v1/feed").param("access_token", jwtService.issueToken(ALICE, "alice")))
                .andExpect(status().isUnauthorized());
    }

    // ── Ownership ─────────────────────────────────────────────────────────────

    @Test
    void deletingSomeoneElsesPostIsForbidden_evenWhenSpoofingRequesterId() throws Exception {
        var alicesPost = Post.create(ALICE, "alice", "mine", null);
        when(postRepository.findById(alicesPost.getId())).thenReturn(Optional.of(alicesPost));
        when(postRepository.findByIdForUpdate(alicesPost.getId())).thenReturn(Optional.of(alicesPost)); // 2C: locked

        mvc.perform(delete("/api/v1/posts/{id}", alicesPost.getId())
                        .param("requesterId", ALICE.toString()) // legacy param must be ignored
                        .header(HttpHeaders.AUTHORIZATION, bearer(BOB, "bob")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("Only the author can delete this post")));

        verify(postRepository, never()).delete(any());
    }

    @Test
    void authorCanDeleteOwnPost() throws Exception {
        var alicesPost = Post.create(ALICE, "alice", "mine", null);
        when(postRepository.findById(alicesPost.getId())).thenReturn(Optional.of(alicesPost));
        when(postRepository.findByIdForUpdate(alicesPost.getId())).thenReturn(Optional.of(alicesPost)); // 2C: locked

        mvc.perform(delete("/api/v1/posts/{id}", alicesPost.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice")))
                .andExpect(status().isNoContent());

        verify(postRepository).delete(alicesPost);
    }

    @Test
    void editingSomeoneElsesPostIsForbidden() throws Exception {
        var alicesPost = Post.create(ALICE, "alice", "mine", null);
        when(postRepository.findByIdForUpdate(alicesPost.getId())).thenReturn(Optional.of(alicesPost));

        mvc.perform(put("/api/v1/posts/{id}", alicesPost.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(BOB, "bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requesterId\":\"" + ALICE + "\",\"content\":\"hacked\"}"))
                .andExpect(status().isForbidden());

        verify(postRepository, never()).save(any());
    }

    @Test
    void editingSomeoneElsesCommentIsForbidden() throws Exception {
        var alicesComment = Comment.create(Post.create(BOB, "bob", "p", null), null, ALICE, "alice", "nice");
        when(commentRepository.findById(alicesComment.getId())).thenReturn(Optional.of(alicesComment));

        mvc.perform(put("/api/v1/comments/{id}", alicesComment.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(BOB, "bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hacked\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/v1/comments/{id}", alicesComment.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(BOB, "bob")))
                .andExpect(status().isForbidden());

        verify(commentRepository, never()).save(any());
        verify(commentRepository, never()).delete(any());
    }

    // ── Identity comes from the token, not the request ───────────────────────

    @Test
    void createPostUsesPrincipalAndIgnoresBodyIdentity() throws Exception {
        when(postRepository.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/v1/posts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorId\":\"" + BOB + "\",\"authorUsername\":\"bob\",\"content\":\"hi\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorId", is(ALICE.toString())))
                .andExpect(jsonPath("$.authorUsername", is("alice")));
    }

    @Test
    void likeUsesPrincipalAndIgnoresUserIdParam() throws Exception {
        var post = Post.create(BOB, "bob", "like me", null);
        when(postRepository.findByIdForUpdate(post.getId())).thenReturn(Optional.of(post));
        when(postRepository.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/v1/posts/{id}/like", post.getId())
                        .param("userId", BOB.toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likesCount", is(1)));

        verify(postReactionRepository).findByPostIdAndUserId(post.getId(), ALICE);
        verify(postReactionRepository).save(argThat(r -> r.getUserId().equals(ALICE)));
    }

    @Test
    void repeatedLikeIsIdempotentSuccess_not500() throws Exception {
        var post = Post.create(BOB, "bob", "like me", null);
        // Simulates the persisted state after the first click: count 1 and ALICE's LIKE row exists.
        post.react(ALICE, java.util.Set.of(), com.socialapp.shared.events.ReactionEvent.ReactionType.LIKE, true);
        when(postRepository.findByIdForUpdate(post.getId())).thenReturn(Optional.of(post));
        when(postReactionRepository.findByPostIdAndUserId(post.getId(), ALICE)).thenReturn(List.of(
                PostReaction.of(post.getId(), ALICE, com.socialapp.shared.events.ReactionEvent.ReactionType.LIKE)));

        mvc.perform(post("/api/v1/posts/{id}/like", post.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likesCount", is(1)));

        verify(postReactionRepository, never()).save(any());
        verify(postReactionRepository, never()).deleteAll(any());
    }

    // ── Stage 2B: hashtags, mentions, content limit ──────────────────────────

    @Test
    void hashtagTimelineRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/hashtags/{tag}/posts", "hello"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("Authentication required")));
        verifyNoInteractions(postRepository);
    }

    @Test
    void hashtagTimelineNormalisesTheTag_returnsEnvelopeWithTagsAndMentions() throws Exception {
        var post = Post.create(ALICE, "alice", "#Hello @bob", null);
        when(postRepository.findHashtagPageAfter(any(), any(), any(), any(), anyInt())).thenReturn(List.of(post));
        when(postHashtagRepository.findByPostIds(any())).thenReturn(java.util.Map.of(post.getId(), List.of("hello")));
        when(postMentionRepository.findByPostIds(any()))
                .thenReturn(java.util.Map.of(post.getId(), List.of(new Mention(BOB, "bob"))));

        mvc.perform(get("/api/v1/hashtags/{tag}/posts", "#HeLLo").header(HttpHeaders.AUTHORIZATION, bearer(BOB, "bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(1)))
                .andExpect(jsonPath("$.items[0].hashtags[0]", is("hello")))
                .andExpect(jsonPath("$.items[0].mentions[0].userId", is(BOB.toString())))
                .andExpect(jsonPath("$.items[0].mentions[0].username", is("bob")))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.nextCursor").hasJsonPath());

        verify(postRepository).findHashtagPageAfter("hello", BOB, FeedCursor.START.createdAt(), FeedCursor.START.id(), 21);
    }

    @Test
    void hashtagTimelineUsesTheFeedCursorAndSizeRules() throws Exception {
        var p1 = Post.create(ALICE, "alice", "#t 1", null);
        var p2 = Post.create(ALICE, "alice", "#t 2", null);
        var p3 = Post.create(ALICE, "alice", "#t 3", null);
        when(postRepository.findHashtagPageAfter(any(), any(), any(), any(), anyInt())).thenReturn(List.of(p1, p2, p3));

        var body = mvc.perform(get("/api/v1/hashtags/t/posts").param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(BOB, "bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(2)))
                .andReturn().getResponse().getContentAsString();
        String cursor = com.jayway.jsonpath.JsonPath.read(body, "$.nextCursor");
        assertThat(FeedCursor.decode(cursor)).isEqualTo(new FeedCursor(p2.getCreatedAt(), p2.getId()));

        mvc.perform(get("/api/v1/hashtags/t/posts").param("cursor", cursor).param("size", "500")
                        .header(HttpHeaders.AUTHORIZATION, bearer(BOB, "bob")))
                .andExpect(status().isOk());
        verify(postRepository).findHashtagPageAfter("t", BOB, p2.getCreatedAt(), p2.getId(), 101);
    }

    @Test
    void unknownHashtagIsAnEmptyPage() throws Exception {
        when(postRepository.findHashtagPageAfter(any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/v1/hashtags/{tag}/posts", "nobodyusesthis")
                        .header(HttpHeaders.AUTHORIZATION, bearer(BOB, "bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(0)))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
        verifyNoInteractions(postHashtagRepository, postMentionRepository);
    }

    @Test
    void invalidHashtagOrParametersAre400Json() throws Exception {
        for (String tag : List.of("123", "a-b", "_", "##x", "a".repeat(51), "two words", "😀")) {
            mvc.perform(get("/api/v1/hashtags/{tag}/posts", tag).header(HttpHeaders.AUTHORIZATION, bearer(BOB, "bob")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").isString())
                    .andExpect(jsonPath("$.timestamp").exists());
        }
        for (var params : List.of(new String[] {"cursor", "garbage!"}, new String[] {"size", "0"},
                new String[] {"size", "abc"})) {
            mvc.perform(get("/api/v1/hashtags/ok/posts").param(params[0], params[1])
                            .header(HttpHeaders.AUTHORIZATION, bearer(BOB, "bob")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").isString());
        }
        verifyNoInteractions(postRepository);
    }

    @Test
    void feedPageLoadsTagsAndMentionsWithOneBatchQueryEach_noNPlusOne() throws Exception {
        var p1 = Post.create(ALICE, "alice", "#a", null);
        var p2 = Post.create(BOB, "bob", "#b @alice", null);
        var p3 = Post.create(ALICE, "alice", "plain", null);
        when(postRepository.findPageAfter(any(), any(), any(), anyInt())).thenReturn(List.of(p1, p2, p3));
        when(postHashtagRepository.findByPostIds(any())).thenReturn(java.util.Map.of(
                p1.getId(), List.of("a"), p2.getId(), List.of("b")));
        when(postMentionRepository.findByPostIds(any())).thenReturn(java.util.Map.of(
                p2.getId(), List.of(new Mention(ALICE, "alice"))));

        mvc.perform(get("/api/v1/feed").header(HttpHeaders.AUTHORIZATION, bearer(BOB, "bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].hashtags[0]", is("a")))
                .andExpect(jsonPath("$.items[0].mentions.length()", is(0)))
                .andExpect(jsonPath("$.items[1].hashtags[0]", is("b")))
                .andExpect(jsonPath("$.items[1].mentions[0].username", is("alice")))
                .andExpect(jsonPath("$.items[2].hashtags.length()", is(0)))
                .andExpect(jsonPath("$.items[2].mentions.length()", is(0)));

        var ids = List.of(p1.getId(), p2.getId(), p3.getId());
        verify(postHashtagRepository, times(1)).findByPostIds(ids);
        verify(postMentionRepository, times(1)).findByPostIds(ids);
        verify(authorProfileRepository, times(1)).findAllById(any());
        verify(authorProfileRepository, never()).findById(any());
    }

    @Test
    void createReturnsParsedHashtagsAndResolvedMentions() throws Exception {
        when(postRepository.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));
        when(authorProfileRepository.findByLowerUsernameIn(any()))
                .thenReturn(List.of(new AuthorProfile(BOB, "Bob", null, null)));
        // The response reads the index back, as the real adapters would return what was written.
        java.util.Map<UUID, List<String>> tags = new java.util.HashMap<>();
        java.util.Map<UUID, List<Mention>> mentions = new java.util.HashMap<>();
        doAnswer(inv -> { tags.put(inv.getArgument(0), inv.getArgument(1)); return null; })
                .when(postHashtagRepository).replace(any(), any());
        doAnswer(inv -> { mentions.put(inv.getArgument(0), inv.getArgument(1)); return null; })
                .when(postMentionRepository).replace(any(), any());
        when(postHashtagRepository.findByPostIds(any())).thenAnswer(inv -> tags);
        when(postMentionRepository.findByPostIds(any())).thenAnswer(inv -> mentions);

        mvc.perform(post("/api/v1/posts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"#Hello #hello @bob @nobody\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hashtags.length()", is(1)))
                .andExpect(jsonPath("$.hashtags[0]", is("hello")))
                .andExpect(jsonPath("$.mentions.length()", is(1)))
                .andExpect(jsonPath("$.mentions[0].userId", is(BOB.toString())))
                .andExpect(jsonPath("$.mentions[0].username", is("Bob")));
    }

    private static String json(String field, String value) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of(field, value));
    }

    @Test
    void overLimitContentIs400MentioningTheLimit_onCreateEditAndShare() throws Exception {
        var alicesPost = Post.create(ALICE, "alice", "mine", null);
        when(postRepository.findByIdForUpdate(alicesPost.getId())).thenReturn(Optional.of(alicesPost));
        when(postRepository.findById(alicesPost.getId())).thenReturn(Optional.of(alicesPost));
        String tooLong = "a".repeat(501);

        var requests = List.of(
                post("/api/v1/posts").content(json("content", tooLong)),
                put("/api/v1/posts/{id}", alicesPost.getId()).content(json("content", tooLong)),
                post("/api/v1/posts/{id}/share", alicesPost.getId()).content(json("comment", tooLong)));
        for (var request : requests) {
            mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice"))
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.error", org.hamcrest.Matchers.containsString("at most 500 characters")))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
        verify(postRepository, never()).save(any());
        verifyNoInteractions(postHashtagRepository, postMentionRepository);
    }

    @Test
    void fiveHundredEmojiAreAccepted_limitCountsCodePointsNotUtf16Units() throws Exception {
        when(postRepository.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));
        String emoji = "😀".repeat(500);

        mvc.perform(post("/api/v1/posts").header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(json("content", emoji)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content", is(emoji)));

        mvc.perform(post("/api/v1/posts").header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(json("content", emoji + "!")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Validation failed: content must be at most 500 characters")));
    }

    @Test
    void blankContentIsStillRejected() throws Exception {
        mvc.perform(post("/api/v1/posts").header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(json("content", "   ")))
                .andExpect(status().isBadRequest());
        verify(postRepository, never()).save(any());
    }

    /**
     * Validation messages are API text: English whatever the client asks for (Accept-Language) and whatever the
     * server's locale is (simulates {@code -Duser.language=pl} / a Polish OS, where Hibernate Validator would
     * otherwise answer "nie może być odstępem").
     */
    @Test
    void validationMessagesAreEnglishForAPolishClientOnAPolishJvm() throws Exception {
        var previous = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("pl-PL"));
        try {
            mvc.perform(post("/api/v1/posts").header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice"))
                            .header(HttpHeaders.ACCEPT_LANGUAGE, "pl-PL,pl;q=0.9")
                            .contentType(MediaType.APPLICATION_JSON).content(json("content", "   ")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", is("Validation failed: content must not be blank")));

            mvc.perform(post("/api/v1/posts/" + UUID.randomUUID() + "/quote")
                            .header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice"))
                            .header(HttpHeaders.ACCEPT_LANGUAGE, "pl")
                            .contentType(MediaType.APPLICATION_JSON).content(json("comment", "")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", is("Validation failed: comment must not be blank")));
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    // ── Reposts and quotes (stage 2C) ─────────────────────────────────────────

    private Post stored(Post p) {
        when(postRepository.findById(p.getId())).thenReturn(Optional.of(p));
        when(postRepository.findByIdForUpdate(p.getId())).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void repostQuoteAndUndoRequireAuthentication() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/v1/posts/{id}/repost", id)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/posts/{id}/repost", id)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/posts/{id}/quote", id).contentType(MediaType.APPLICATION_JSON)
                .content(json("comment", "x"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/posts/{id}/share", id)).andExpect(status().isUnauthorized());
        verifyNoInteractions(postRepository);
    }

    @Test
    void repostIs201ThenIdempotent200_withTheNestedOriginalAndCounters() throws Exception {
        var original = stored(Post.create(BOB, "bob", "#orig bob's", "img.png"));
        when(postRepository.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));
        when(postRepository.findAllById(any())).thenReturn(List.of(original));
        when(postRepository.countRepostsAndQuotes(any())).thenReturn(List.<Object[]>of(
                new Object[] {original.getId(), PostType.REPOST, 1L}));
        when(postRepository.findRepostedOriginalIds(eq(ALICE), any())).thenReturn(List.of(original.getId()));
        when(authorProfileRepository.findAllById(any())).thenReturn(List.of(new AuthorProfile(BOB, "bob", "b.png", null)));

        var body = mvc.perform(post("/api/v1/posts/{id}/repost", original.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type", is("REPOST")))
                .andExpect(jsonPath("$.content", is("")))
                .andExpect(jsonPath("$.authorId", is(ALICE.toString())))
                .andExpect(jsonPath("$.repostsCount", is(0)))
                .andExpect(jsonPath("$.quotesCount", is(0)))
                .andExpect(jsonPath("$.viewerReposted", is(false)))
                .andExpect(jsonPath("$.userReaction").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.originalPostId", is(original.getId().toString())))
                .andExpect(jsonPath("$.originalAuthorId", is(BOB.toString())))
                .andExpect(jsonPath("$.originalAuthorUsername", is("bob")))
                .andExpect(jsonPath("$.originalContent", is("#orig bob's")))
                .andExpect(jsonPath("$.original.id", is(original.getId().toString())))
                .andExpect(jsonPath("$.original.type", is("POST")))
                .andExpect(jsonPath("$.original.authorAvatarUrl", is("b.png")))
                .andExpect(jsonPath("$.original.imageUrl", is("img.png")))
                .andExpect(jsonPath("$.original.repostsCount", is(1)))
                .andExpect(jsonPath("$.original.viewerReposted", is(true)))
                .andExpect(jsonPath("$.original.original").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString();

        var created = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .readTree(body).get("id").asText();
        var existing = postRepository.save(Post.repost(ALICE, "alice", original));
        when(postRepository.findRepost(ALICE, original.getId())).thenReturn(Optional.of(existing));

        mvc.perform(post("/api/v1/posts/{id}/repost", original.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(existing.getId().toString())))
                .andExpect(jsonPath("$.type", is("REPOST")));
        assertThat(created).isNotBlank();
    }

    @Test
    void repostOrQuoteOfUnknownPostIs404() throws Exception {
        UUID unknown = UUID.randomUUID();
        mvc.perform(post("/api/v1/posts/{id}/repost", unknown).header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("Post not found: " + unknown)));
        mvc.perform(post("/api/v1/posts/{id}/quote", unknown).header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(json("comment", "hm")))
                .andExpect(status().isNotFound());
        verify(postRepository, never()).save(any());
    }

    @Test
    void undoRepostIsAlways204() throws Exception {
        var original = stored(Post.create(BOB, "bob", "orig", null));

        mvc.perform(delete("/api/v1/posts/{id}/repost", original.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice")))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/posts/{id}/repost", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice")))
                .andExpect(status().isNoContent());

        verify(postRepository, times(1)).deleteRepost(ALICE, original.getId());
    }

    @Test
    void quoteIs201WithItsOwnText_blankMissingOrTooLongIs400() throws Exception {
        var original = stored(Post.create(BOB, "bob", "orig", null));
        when(postRepository.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/v1/posts/{id}/quote", original.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(json("comment", "my take")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type", is("QUOTE")))
                .andExpect(jsonPath("$.content", is("my take")))
                .andExpect(jsonPath("$.originalPostId", is(original.getId().toString())));

        for (String body : List.of(json("comment", "   "), json("comment", "x".repeat(501)), "{}")) {
            mvc.perform(post("/api/v1/posts/{id}/quote", original.getId())
                            .header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", org.hamcrest.Matchers.startsWith("Validation failed: comment")));
        }
        mvc.perform(post("/api/v1/posts/{id}/quote", original.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice")))
                .andExpect(status().isBadRequest()); // no body at all
        verify(postRepository, times(1)).save(any());
    }

    @Test
    void editingARepostIs409_editingSomeoneElsesRepostIs403_quoteCommentIsEditable() throws Exception {
        var original = Post.create(BOB, "bob", "orig", null);
        var repost = stored(Post.repost(ALICE, "alice", original));
        var quote = stored(Post.quote(ALICE, "alice", original, "old"));
        when(postRepository.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(put("/api/v1/posts/{id}", repost.getId()).header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(json("content", "text")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", org.hamcrest.Matchers.containsString("repost cannot be edited")));
        mvc.perform(put("/api/v1/posts/{id}", repost.getId()).header(HttpHeaders.AUTHORIZATION, bearer(BOB, "bob"))
                        .contentType(MediaType.APPLICATION_JSON).content(json("content", "text")))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/posts/{id}", quote.getId()).header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(json("content", "new #text")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type", is("QUOTE")))
                .andExpect(jsonPath("$.content", is("new #text")));
        verify(postHashtagRepository).replace(quote.getId(), List.of("text"));
        verify(postHashtagRepository, never()).replace(eq(repost.getId()), any());
    }

    @Test
    void deprecatedShareAlias_noBodyOrBlankReposts_commentQuotes() throws Exception {
        var original = stored(Post.create(BOB, "bob", "orig", null));
        when(postRepository.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/v1/posts/{id}/share", original.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type", is("REPOST")));
        var existing = Post.repost(ALICE, "alice", original);
        when(postRepository.findRepost(ALICE, original.getId())).thenReturn(Optional.of(existing));
        mvc.perform(post("/api/v1/posts/{id}/share", original.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(json("comment", " ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(existing.getId().toString())));
        mvc.perform(post("/api/v1/posts/{id}/share", original.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(json("comment", "look")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type", is("QUOTE")))
                .andExpect(jsonPath("$.content", is("look")));
    }

    @Test
    void everyPostEndpointCarriesTheNewFields() throws Exception {
        var post = stored(Post.create(ALICE, "alice", "plain", null));
        when(postRepository.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));
        when(postRepository.findPageAfter(any(), any(), any(), anyInt())).thenReturn(List.of(post));
        when(postRepository.findHashtagPageAfter(any(), any(), any(), any(), anyInt())).thenReturn(List.of(post));
        when(postReactionRepository.findByUserIdAndPostIdIn(eq(BOB), any()))
                .thenReturn(List.of(PostReaction.of(post.getId(), BOB, com.socialapp.shared.events.ReactionEvent.ReactionType.LIKE)));

        var requests = List.of(
                get("/api/v1/feed"), get("/api/v1/hashtags/plain/posts"));
        for (var r : requests) {
            mvc.perform(r.header(HttpHeaders.AUTHORIZATION, bearer(BOB, "bob")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].type", is("POST")))
                    .andExpect(jsonPath("$.items[0].repostsCount", is(0)))
                    .andExpect(jsonPath("$.items[0].quotesCount", is(0)))
                    .andExpect(jsonPath("$.items[0].viewerReposted", is(false)))
                    .andExpect(jsonPath("$.items[0].userReaction", is("LIKE")))
                    .andExpect(jsonPath("$.items[0].original").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.items[0].originalAuthorId").hasJsonPath());
        }
        for (var r : List.of(get("/api/v1/posts/{id}", post.getId()), post("/api/v1/posts/{id}/like", post.getId()))) {
            mvc.perform(r.header(HttpHeaders.AUTHORIZATION, bearer(BOB, "bob")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.type", is("POST")))
                    .andExpect(jsonPath("$.repostsCount", is(0)))
                    .andExpect(jsonPath("$.viewerReposted", is(false)))
                    .andExpect(jsonPath("$.original").hasJsonPath());
        }
    }

    // ── CORS / public resources ───────────────────────────────────────────────

    @Test
    void corsPreflightFromFrontendOriginPasses() throws Exception {
        mvc.perform(options("/api/v1/posts")
                        .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void corsPreflightFromUnknownOriginIsRejected() throws Exception {
        mvc.perform(options("/api/v1/posts")
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploadedImagesArePublic() throws Exception {
        // File does not exist -> 404, but crucially not 401
        mvc.perform(get("/uploads/avatars/does-not-exist.png"))
                .andExpect(status().isNotFound());
    }

    // ── Images (3C): mediaIds on create/quote, media on every PostResponse ───

    @Test
    void createWithMediaAttachesTheCallersUploadsInOrder_andReturnsThemAsMedia() throws Exception {
        when(postRepository.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));
        var first = PostMedia.upload(ALICE, ImageFormat.PNG, 10, 20, 100);
        var second = PostMedia.upload(ALICE, ImageFormat.JPEG, 30, 40, 100);
        when(postMediaRepository.findAllByIdForUpdate(any())).thenReturn(List.of(first, second));
        when(postMediaRepository.findByPostIds(any())).thenAnswer(inv -> List.of(second, first).stream()
                .filter(PostMedia::isAttached).sorted(java.util.Comparator.comparing(PostMedia::getPosition)).toList());
        when(mediaStorage.publicUrl(any())).thenAnswer(inv -> "http://h/uploads/media/" + inv.getArgument(0));

        mvc.perform(post("/api/v1/posts").header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"pics\",\"mediaIds\":[\"" + second.getId() + "\",\"" + first.getId() + "\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.media.length()", is(2)))
                .andExpect(jsonPath("$.media[0].id", is(second.getId().toString())))
                .andExpect(jsonPath("$.media[0].url", is("http://h/uploads/media/" + second.getStorageKey())))
                .andExpect(jsonPath("$.media[0].width", is(30)))
                .andExpect(jsonPath("$.media[0].height", is(40)))
                .andExpect(jsonPath("$.media[1].id", is(first.getId().toString())));

        assertThat(second.getPosition()).isEqualTo((short) 0);
        assertThat(first.getPosition()).isEqualTo((short) 1);
        assertThat(first.getPostId()).isEqualTo(second.getPostId()).isNotNull();
    }

    @Test
    void postWithoutMediaHasAnEmptyMediaArray() throws Exception {
        var post = Post.create(ALICE, "alice", "plain", null);
        when(postRepository.findById(post.getId())).thenReturn(Optional.of(post));

        mvc.perform(get("/api/v1/posts/{id}", post.getId()).header(HttpHeaders.AUTHORIZATION, bearer(BOB, "bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.media").isArray())
                .andExpect(jsonPath("$.media.length()", is(0)));
    }

    @Test
    void someoneElsesUploadIs404_alreadyAttachedIs400_andNothingIsSaved() throws Exception {
        var bobs = PostMedia.upload(BOB, ImageFormat.PNG, 1, 1, 1);
        var attached = PostMedia.upload(ALICE, ImageFormat.PNG, 1, 1, 1);
        attached.attachTo(UUID.randomUUID(), 0, ALICE);
        when(postMediaRepository.findAllByIdForUpdate(List.of(bobs.getId()))).thenReturn(List.of(bobs));
        when(postMediaRepository.findAllByIdForUpdate(List.of(attached.getId()))).thenReturn(List.of(attached));

        mvc.perform(post("/api/v1/posts").header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"x\",\"mediaIds\":[\"" + bobs.getId() + "\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("Media not found: " + bobs.getId())));
        mvc.perform(post("/api/v1/posts").header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"x\",\"mediaIds\":[\"" + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/posts").header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"x\",\"mediaIds\":[\"" + attached.getId() + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Media is already attached to a post: " + attached.getId())));
        verify(postRepository, never()).save(any());
    }

    @Test
    void moreThanFourOrDuplicateOrMalformedMediaIdsAre400_andMediaOnlyPostsAreNotAllowed() throws Exception {
        var ids = new StringBuilder();
        for (int i = 0; i < 5; i++) ids.append(i == 0 ? "" : ",").append('"').append(UUID.randomUUID()).append('"');
        var dup = UUID.randomUUID();
        for (String body : List.of(
                "{\"content\":\"x\",\"mediaIds\":[" + ids + "]}",
                "{\"content\":\"x\",\"mediaIds\":[\"" + dup + "\",\"" + dup + "\"]}",
                "{\"content\":\"x\",\"mediaIds\":[\"not-a-uuid\"]}",
                "{\"content\":\"\",\"mediaIds\":[\"" + UUID.randomUUID() + "\"]}")) {
            mvc.perform(post("/api/v1/posts").header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").exists());
        }
        var original = Post.create(BOB, "bob", "orig", null);
        when(postRepository.findById(original.getId())).thenReturn(Optional.of(original));
        when(postRepository.findByIdForUpdate(original.getId())).thenReturn(Optional.of(original));
        mvc.perform(post("/api/v1/posts/{id}/quote", original.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ALICE, "alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"q\",\"mediaIds\":[" + ids + "]}"))
                .andExpect(status().isBadRequest());
        verify(postRepository, never()).save(any());
        verifyNoInteractions(postMediaRepository);
    }
}
