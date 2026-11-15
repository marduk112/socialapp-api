package com.socialapp.feed.infrastructure.web;

import com.socialapp.feed.application.timeline.FeedCursor;
import com.socialapp.feed.application.timeline.FeedScope;
import com.socialapp.feed.application.timeline.TimelineService;
import com.socialapp.feed.application.timeline.FeedPage;
import com.socialapp.feed.application.usecase.BookmarkService;
import com.socialapp.feed.application.usecase.PostService;
import com.socialapp.feed.application.usecase.PostView;
import com.socialapp.feed.domain.Comment;
import com.socialapp.feed.domain.Post;
import com.socialapp.feed.domain.PostMedia;
import com.socialapp.feed.infrastructure.sse.FeedSseService;
import com.socialapp.shared.events.ReactionEvent;
import com.socialapp.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;
    private final BookmarkService bookmarkService;
    private final TimelineService timelineService;
    private final FeedSseService feedSseService;

    // ── Feed ──────────────────────────────────────────────────────────────────

    /**
     * Timeline page, newest first: {@code {"items": [PostResponse...], "nextCursor": "<opaque>" | null}}.
     * <ul>
     *   <li>{@code scope}: {@code all} (default, every post) or {@code following} (posts by users the caller
     *       follows plus the caller's own); anything else -> 400.</li>
     *   <li>{@code cursor}: the {@code nextCursor} of the previous page; omitted -> first page; malformed -> 400.</li>
     *   <li>{@code size}: default 20; values above 100 are clamped to 100; below 1 -> 400.</li>
     * </ul>
     * Keyset pagination over (createdAt DESC, id DESC): posts created between requests never duplicate or
     * shift the remaining pages. {@code nextCursor} is null on the last page.
     */
    @GetMapping("/feed")
    public ResponseEntity<FeedPageResponse> getFeed(
            @AuthenticationPrincipal AuthenticatedUser me,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + TimelineService.DEFAULT_SIZE) int size) {
        var page = timelineService.page(me.id(), FeedScope.parse(scope), FeedCursor.decode(cursor), size);
        return ResponseEntity.ok(pageResponse(page, me));
    }

    /**
     * Posts carrying hashtag {@code tag}, newest first, same envelope, cursor and size rules as {@code /feed}.
     * {@code tag} is case-insensitive, a leading {@code #} (sent as {@code %23}) is tolerated; an invalid tag
     * -> 400, an unknown one -> 200 with no items.
     */
    @GetMapping("/hashtags/{tag}/posts")
    public ResponseEntity<FeedPageResponse> getHashtagPosts(
            @AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String tag,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + TimelineService.DEFAULT_SIZE) int size) {
        var page = timelineService.hashtagPage(tag, FeedCursor.decode(cursor), size);
        return ResponseEntity.ok(pageResponse(page, me));
    }

    /** SSE stream; {@code scope=following} only forwards posts by followed users or the caller. */
    @GetMapping(value = "/feed/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamFeed(
            @AuthenticationPrincipal AuthenticatedUser me,
            @RequestParam(required = false) String scope) {
        return feedSseService.subscribe(me.id(), FeedScope.parse(scope));
    }

    // ── Posts CRUD ─────────────────────────────────────────────────────────────

    /**
     * Creates a post. {@code mediaIds} (optional, max 4, no duplicates): unattached uploads of the caller
     * ({@code POST /media}), attached in this order atomically with the post; unknown or another user's upload -> 404,
     * already attached, more than 4 or duplicates -> 400. {@code content} stays required. Media cannot be changed
     * by an edit.
     */
    @PostMapping("/posts")
    public ResponseEntity<PostResponse> createPost(
            @AuthenticationPrincipal AuthenticatedUser me,
            @Valid @RequestBody CreatePostRequest req) {
        var post = postService.createPost(me.id(), me.username(), req.content(), req.imageUrl(), req.mediaIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(enriched(post, me));
    }

    @GetMapping("/posts/{id}")
    public ResponseEntity<PostResponse> getPost(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser me) {
        return ResponseEntity.ok(enriched(postService.findById(id), me));
    }

    @PutMapping("/posts/{id}")
    public ResponseEntity<PostResponse> updatePost(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser me,
            @Valid @RequestBody UpdatePostRequest req) {
        return ResponseEntity.ok(enriched(postService.updatePost(id, me.id(), req.content(), req.imageUrl()), me));
    }

    @DeleteMapping("/posts/{id}")
    public ResponseEntity<Void> deletePost(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser me) {
        postService.deletePost(id, me.id());
        return ResponseEntity.noContent().build();
    }

    // ── Reposts and quotes ────────────────────────────────────────────────────

    /**
     * Reposts post {@code id} (a repost item resolves to its original): 201 with the new REPOST, or 200 with the
     * caller's existing one (idempotent). Unknown/deleted post -> 404.
     */
    @PostMapping("/posts/{id}/repost")
    public ResponseEntity<PostResponse> repost(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser me) {
        return repostResponse(postService.repost(id, me.id(), me.username()), me);
    }

    /**
     * Removes the caller's repost of post {@code id} (the original's id, or a repost item's id resolved to its
     * original). Always 204, also when there was none.
     */
    @DeleteMapping("/posts/{id}/repost")
    public ResponseEntity<Void> undoRepost(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser me) {
        postService.undoRepost(id, me.id());
        return ResponseEntity.noContent().build();
    }

    /**
     * Quotes post {@code id} with a required comment (1..500 code points) and optional images ({@code mediaIds}, same
     * rules as {@code POST /posts}): 201 with the QUOTE.
     */
    @PostMapping("/posts/{id}/quote")
    public ResponseEntity<PostResponse> quote(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser me,
            @Valid @RequestBody QuotePostRequest req) {
        var post = postService.quote(id, me.id(), me.username(), req.comment(), req.mediaIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(enriched(post, me));
    }

    /**
     * @deprecated since stage 2C, use {@code POST /posts/{id}/repost} or {@code POST /posts/{id}/quote}. Alias:
     * a blank/missing comment reposts (201 new, 200 existing), otherwise quotes (201).
     */
    @Deprecated
    @PostMapping("/posts/{id}/share")
    public ResponseEntity<PostResponse> sharePost(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser me,
            @Valid @RequestBody(required = false) SharePostRequest req) {
        return repostResponse(postService.sharePost(id, me.id(), me.username(), req == null ? null : req.comment()), me);
    }

    private ResponseEntity<PostResponse> repostResponse(PostService.RepostResult result, AuthenticatedUser me) {
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(enriched(result.post(), me));
    }

    // ── Bookmarks ─────────────────────────────────────────────────────────────

    /**
     * Bookmarks post {@code id} for the caller (private). 204, idempotent; unknown/deleted post -> 404. Bookmarking a
     * repost item bookmarks that item (its own id), not the original.
     */
    @PutMapping("/posts/{id}/bookmark")
    public ResponseEntity<Void> bookmark(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser me) {
        bookmarkService.bookmark(me.id(), id);
        return ResponseEntity.noContent().build();
    }

    /** Removes the caller's bookmark of post {@code id}. Always 204 (also when there was none or the post is gone). */
    @DeleteMapping("/posts/{id}/bookmark")
    public ResponseEntity<Void> unbookmark(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser me) {
        bookmarkService.unbookmark(me.id(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * The caller's bookmarks, most recently bookmarked first: same envelope, {@code PostResponse} items, cursor and size
     * rules as {@code /feed} (keyset over bookmark time and post id; malformed cursor or size &lt; 1 -> 400).
     */
    @GetMapping("/bookmarks")
    public ResponseEntity<FeedPageResponse> getBookmarks(
            @AuthenticationPrincipal AuthenticatedUser me,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + TimelineService.DEFAULT_SIZE) int size) {
        return ResponseEntity.ok(pageResponse(bookmarkService.page(me.id(), FeedCursor.decode(cursor), size), me));
    }

    // ── Reactions ─────────────────────────────────────────────────────────────
    @PostMapping("/posts/{id}/like")
    public ResponseEntity<PostResponse> likePost(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser me) {
        return ResponseEntity.ok(enriched(postService.react(id, me.id(), ReactionEvent.ReactionType.LIKE, true), me));
    }

    @DeleteMapping("/posts/{id}/like")
    public ResponseEntity<PostResponse> unlikePost(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser me) {
        return ResponseEntity.ok(enriched(postService.react(id, me.id(), ReactionEvent.ReactionType.LIKE, false), me));
    }

    @PostMapping("/posts/{id}/dislike")
    public ResponseEntity<PostResponse> dislikePost(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser me) {
        return ResponseEntity.ok(enriched(postService.react(id, me.id(), ReactionEvent.ReactionType.DISLIKE, true), me));
    }

    @DeleteMapping("/posts/{id}/dislike")
    public ResponseEntity<PostResponse> removeDislikePost(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser me) {
        return ResponseEntity.ok(enriched(postService.react(id, me.id(), ReactionEvent.ReactionType.DISLIKE, false), me));
    }

    // ── Comments ──────────────────────────────────────────────────────────────
    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<CommentResponse> addComment(
            @PathVariable UUID postId,
            @AuthenticationPrincipal AuthenticatedUser me,
            @Valid @RequestBody CreateCommentRequest req) {
        var comment = postService.addComment(postId, me.id(), me.username(), req.content(), req.parentCommentId());
        return ResponseEntity.status(HttpStatus.CREATED).body(enrichedComment(comment));
    }

    @GetMapping("/posts/{postId}/comments")
    public ResponseEntity<List<CommentResponse>> getComments(
            @PathVariable UUID postId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var comments = postService.getComments(postId, page, size);
        Set<UUID> authorIds = comments.stream().map(Comment::getAuthorId).collect(Collectors.toSet());
        var avatarMap = postService.getAuthorAvatarUrls(authorIds);
        return ResponseEntity.ok(
                comments.stream().map(c -> CommentResponse.from(c, avatarMap.get(c.getAuthorId()))).toList()
        );
    }

    @PutMapping("/comments/{commentId}")
    public ResponseEntity<CommentResponse> updateComment(
            @PathVariable UUID commentId,
            @AuthenticationPrincipal AuthenticatedUser me,
            @Valid @RequestBody UpdateCommentRequest req) {
        var comment = postService.updateComment(commentId, me.id(), req.content());
        return ResponseEntity.ok(enrichedComment(comment));
    }

    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @PathVariable UUID commentId,
            @AuthenticationPrincipal AuthenticatedUser me) {
        postService.deleteComment(commentId, me.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/comments/{commentId}/like")
    public ResponseEntity<CommentResponse> likeComment(
            @PathVariable UUID commentId,
            @AuthenticationPrincipal AuthenticatedUser me) {
        return ResponseEntity.ok(enrichedComment(
                postService.reactToComment(commentId, me.id(), ReactionEvent.ReactionType.LIKE, true)));
    }

    @DeleteMapping("/comments/{commentId}/like")
    public ResponseEntity<CommentResponse> unlikeComment(
            @PathVariable UUID commentId,
            @AuthenticationPrincipal AuthenticatedUser me) {
        return ResponseEntity.ok(enrichedComment(
                postService.reactToComment(commentId, me.id(), ReactionEvent.ReactionType.LIKE, false)));
    }

    @PostMapping("/comments/{commentId}/dislike")
    public ResponseEntity<CommentResponse> dislikeComment(
            @PathVariable UUID commentId,
            @AuthenticationPrincipal AuthenticatedUser me) {
        return ResponseEntity.ok(enrichedComment(
                postService.reactToComment(commentId, me.id(), ReactionEvent.ReactionType.DISLIKE, true)));
    }

    @DeleteMapping("/comments/{commentId}/dislike")
    public ResponseEntity<CommentResponse> removeDislikeComment(
            @PathVariable UUID commentId,
            @AuthenticationPrincipal AuthenticatedUser me) {
        return ResponseEntity.ok(enrichedComment(
                postService.reactToComment(commentId, me.id(), ReactionEvent.ReactionType.DISLIKE, false)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private PostResponse enriched(Post post, AuthenticatedUser me) {
        return PostResponse.from(postService.view(post, me.id()));
    }

    /** Batch-enriches the page (constant number of queries per page, see {@link PostService#views}). */
    private FeedPageResponse pageResponse(FeedPage page, AuthenticatedUser me) {
        return new FeedPageResponse(
                postService.views(page.posts(), me.id()).stream().map(PostResponse::from).toList(),
                page.next() == null ? null : page.next().encode());
    }

    private CommentResponse enrichedComment(Comment comment) {
        String avatarUrl = postService.getAuthorAvatarUrl(comment.getAuthorId()).orElse(null);
        return CommentResponse.from(comment, avatarUrl);
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────
    // Identity fields (authorId, authorUsername, requesterId) are no longer read from request
    // bodies: they come from the authenticated principal. Unknown JSON fields are ignored,
    // so older clients that still send them keep working.
    // Post text limits count code points (see MaxCodePoints); the domain enforces the same limit.
    record CreatePostRequest(
            @NotBlank @MaxCodePoints(Post.MAX_CONTENT_LENGTH) String content,
            String imageUrl,
            @Size(max = PostMedia.MAX_PER_POST) List<UUID> mediaIds
    ) {}

    record UpdatePostRequest(
            @NotBlank @MaxCodePoints(Post.MAX_CONTENT_LENGTH) String content,
            String imageUrl
    ) {}

    /** @deprecated body of the deprecated {@code /share} alias. */
    @Deprecated
    record SharePostRequest(
            @MaxCodePoints(Post.MAX_CONTENT_LENGTH) String comment
    ) {}

    record QuotePostRequest(
            @NotBlank @MaxCodePoints(Post.MAX_CONTENT_LENGTH) String comment,
            @Size(max = PostMedia.MAX_PER_POST) List<UUID> mediaIds
    ) {}

    record CreateCommentRequest(
            @NotBlank @Size(max = 2000) String content,
            UUID parentCommentId
    ) {}

    record UpdateCommentRequest(
            @NotBlank @Size(max = 2000) String content
    ) {}

    /** Envelope of {@code GET /feed}; {@code nextCursor} is serialized as null on the last page. */
    record FeedPageResponse(List<PostResponse> items, String nextCursor) {}

    record CommentResponse(
            UUID id, UUID postId, UUID parentCommentId, UUID authorId, String authorUsername, String authorAvatarUrl,
            String content, int likesCount, int dislikesCount, Instant createdAt, Instant updatedAt
    ) {
        static CommentResponse from(Comment c, String authorAvatarUrl) {
            return new CommentResponse(
                    c.getId(), c.getPostId(), c.getParentCommentId(), c.getAuthorId(), c.getAuthorUsername(), authorAvatarUrl,
                    c.getContent(), c.getLikesCount(), c.getDislikesCount(), c.getCreatedAt(), c.getUpdatedAt()
            );
        }
    }
}
