package com.socialapp.feed.infrastructure.web;

import com.socialapp.feed.application.usecase.PostService;
import com.socialapp.feed.domain.Comment;
import com.socialapp.feed.domain.Post;
import com.socialapp.feed.infrastructure.sse.FeedSseService;
import com.socialapp.shared.events.ReactionEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final FeedSseService feedSseService;

    // ── Feed ──────────────────────────────────────────────────────────────────
    @GetMapping("/feed")
    public ResponseEntity<List<PostResponse>> getFeed(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var posts = postService.getFeed(page, size);
        Set<UUID> authorIds = posts.stream().map(Post::getAuthorId).collect(Collectors.toSet());
        var avatarMap = postService.getAuthorAvatarUrls(authorIds);
        return ResponseEntity.ok(
                posts.stream().map(p -> PostResponse.from(p, avatarMap.get(p.getAuthorId()))).toList()
        );
    }

    @GetMapping(value = "/feed/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamFeed() {
        return feedSseService.subscribe();
    }

    // ── Posts CRUD ─────────────────────────────────────────────────────────────
    @PostMapping("/posts")
    public ResponseEntity<PostResponse> createPost(@Valid @RequestBody CreatePostRequest req) {
        var post = postService.createPost(req.authorId(), req.authorUsername(), req.content(), req.imageUrl());
        return ResponseEntity.status(HttpStatus.CREATED).body(enriched(post));
    }

    @GetMapping("/posts/{id}")
    public ResponseEntity<PostResponse> getPost(@PathVariable UUID id) {
        return ResponseEntity.ok(enriched(postService.findById(id)));
    }

    @PutMapping("/posts/{id}")
    public ResponseEntity<PostResponse> updatePost(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePostRequest req) {
        return ResponseEntity.ok(enriched(postService.updatePost(id, req.requesterId(), req.content(), req.imageUrl())));
    }

    @DeleteMapping("/posts/{id}")
    public ResponseEntity<Void> deletePost(
            @PathVariable UUID id,
            @RequestParam UUID requesterId) {
        postService.deletePost(id, requesterId);
        return ResponseEntity.noContent().build();
    }

    // ── Sharing ───────────────────────────────────────────────────────────────
    @PostMapping("/posts/{id}/share")
    public ResponseEntity<PostResponse> sharePost(
            @PathVariable UUID id,
            @Valid @RequestBody SharePostRequest req) {
        var post = postService.sharePost(id, req.authorId(), req.authorUsername(), req.comment());
        return ResponseEntity.status(HttpStatus.CREATED).body(enriched(post));
    }

    // ── Reactions ─────────────────────────────────────────────────────────────
    @PostMapping("/posts/{id}/like")
    public ResponseEntity<PostResponse> likePost(
            @PathVariable UUID id,
            @RequestParam UUID userId) {
        return ResponseEntity.ok(enriched(postService.react(id, userId, ReactionEvent.ReactionType.LIKE, true)));
    }

    @DeleteMapping("/posts/{id}/like")
    public ResponseEntity<PostResponse> unlikePost(
            @PathVariable UUID id,
            @RequestParam UUID userId) {
        return ResponseEntity.ok(enriched(postService.react(id, userId, ReactionEvent.ReactionType.LIKE, false)));
    }

    @PostMapping("/posts/{id}/dislike")
    public ResponseEntity<PostResponse> dislikePost(
            @PathVariable UUID id,
            @RequestParam UUID userId) {
        return ResponseEntity.ok(enriched(postService.react(id, userId, ReactionEvent.ReactionType.DISLIKE, true)));
    }

    @DeleteMapping("/posts/{id}/dislike")
    public ResponseEntity<PostResponse> removeDislikePost(
            @PathVariable UUID id,
            @RequestParam UUID userId) {
        return ResponseEntity.ok(enriched(postService.react(id, userId, ReactionEvent.ReactionType.DISLIKE, false)));
    }

    // ── Comments ──────────────────────────────────────────────────────────────
    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<CommentResponse> addComment(
            @PathVariable UUID postId,
            @Valid @RequestBody CreateCommentRequest req) {
        var comment = postService.addComment(postId, req.authorId(), req.authorUsername(), req.content(), req.parentCommentId());
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
            @Valid @RequestBody UpdateCommentRequest req) {
        var comment = postService.updateComment(commentId, req.requesterId(), req.content());
        return ResponseEntity.ok(enrichedComment(comment));
    }

    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @PathVariable UUID commentId,
            @RequestParam UUID requesterId) {
        postService.deleteComment(commentId, requesterId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/comments/{commentId}/like")
    public ResponseEntity<CommentResponse> likeComment(
            @PathVariable UUID commentId,
            @RequestParam UUID userId) {
        return ResponseEntity.ok(enrichedComment(
                postService.reactToComment(commentId, userId, ReactionEvent.ReactionType.LIKE, true)));
    }

    @DeleteMapping("/comments/{commentId}/like")
    public ResponseEntity<CommentResponse> unlikeComment(
            @PathVariable UUID commentId,
            @RequestParam UUID userId) {
        return ResponseEntity.ok(enrichedComment(
                postService.reactToComment(commentId, userId, ReactionEvent.ReactionType.LIKE, false)));
    }

    @PostMapping("/comments/{commentId}/dislike")
    public ResponseEntity<CommentResponse> dislikeComment(
            @PathVariable UUID commentId,
            @RequestParam UUID userId) {
        return ResponseEntity.ok(enrichedComment(
                postService.reactToComment(commentId, userId, ReactionEvent.ReactionType.DISLIKE, true)));
    }

    @DeleteMapping("/comments/{commentId}/dislike")
    public ResponseEntity<CommentResponse> removeDislikeComment(
            @PathVariable UUID commentId,
            @RequestParam UUID userId) {
        return ResponseEntity.ok(enrichedComment(
                postService.reactToComment(commentId, userId, ReactionEvent.ReactionType.DISLIKE, false)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private PostResponse enriched(Post post) {
        String avatarUrl = postService.getAuthorAvatarUrl(post.getAuthorId()).orElse(null);
        return PostResponse.from(post, avatarUrl);
    }

    private CommentResponse enrichedComment(Comment comment) {
        String avatarUrl = postService.getAuthorAvatarUrl(comment.getAuthorId()).orElse(null);
        return CommentResponse.from(comment, avatarUrl);
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────
    record CreatePostRequest(
            UUID authorId,
            @NotBlank String authorUsername,
            @NotBlank @Size(max = 2000) String content,
            String imageUrl
    ) {}

    record UpdatePostRequest(
            UUID requesterId,
            @NotBlank @Size(max = 2000) String content,
            String imageUrl
    ) {}

    record SharePostRequest(
            UUID authorId,
            @NotBlank String authorUsername,
            String comment
    ) {}

    record CreateCommentRequest(
            UUID authorId,
            @NotBlank String authorUsername,
            @NotBlank @Size(max = 2000) String content,
            UUID parentCommentId
    ) {}

    record UpdateCommentRequest(
            UUID requesterId,
            @NotBlank @Size(max = 2000) String content
    ) {}

    record PostResponse(
            UUID id, UUID authorId, String authorUsername, String authorAvatarUrl,
            String content, String imageUrl,
            long likesCount, long dislikesCount, long commentsCount,
            UUID originalPostId, String originalAuthorUsername, String originalContent,
            Instant createdAt, Instant updatedAt
    ) {
        static PostResponse from(Post p, String authorAvatarUrl) {
            return new PostResponse(
                    p.getId(), p.getAuthorId(), p.getAuthorUsername(), authorAvatarUrl,
                    p.getContent(), p.getImageUrl(),
                    p.getLikesCount(), p.getDislikesCount(), p.getCommentsCount(),
                    p.getOriginalPostId(), p.getOriginalAuthorUsername(), p.getOriginalContent(),
                    p.getCreatedAt(), p.getUpdatedAt()
            );
        }
    }

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
