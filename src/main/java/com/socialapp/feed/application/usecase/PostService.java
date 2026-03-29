package com.socialapp.feed.application.usecase;

import com.socialapp.feed.domain.*;
import com.socialapp.shared.events.ReactionEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PostService {

    private final PostRepository postRepository;
    private final PostReactionRepository reactionRepository;
    private final CommentRepository commentRepository;
    private final CommentReactionRepository commentReactionRepository;
    private final AuthorProfileRepository authorProfileRepository;

    // ── Posts CRUD ──────────────────────────────────────────────────────────────

    @Transactional
    public Post createPost(UUID authorId, String authorUsername, String content, String imageUrl) {
        var post = Post.create(authorId, authorUsername, content, imageUrl);
        return postRepository.save(post);
    }

    @Transactional(readOnly = true)
    public List<Post> getFeed(int page, int size) {
        return postRepository.findAll(PageRequest.of(page, size,
                Sort.by("createdAt").descending()))
                .getContent();
    }

    @Transactional(readOnly = true)
    public Post findById(UUID id) {
        return postRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + id));
    }

    @Transactional
    public Post updatePost(UUID postId, UUID requesterId, String content, String imageUrl) {
        var post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));
        if (!post.getAuthorId().equals(requesterId)) {
            throw new SecurityException("Only the author can edit this post");
        }
        post.edit(content, imageUrl);
        return postRepository.save(post);
    }

    @Transactional
    public void deletePost(UUID postId, UUID requesterId) {
        var post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));
        if (!post.getAuthorId().equals(requesterId)) {
            throw new SecurityException("Only the author can delete this post");
        }
        postRepository.delete(post);
    }

    // ── Sharing ────────────────────────────────────────────────────────────────

    @Transactional
    public Post sharePost(UUID originalPostId, UUID authorId, String authorUsername, String comment) {
        var original = postRepository.findById(originalPostId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + originalPostId));
        var shared = Post.share(authorId, authorUsername, original, comment);
        return postRepository.save(shared);
    }

    // ── Avatar helpers ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Optional<String> getAuthorAvatarUrl(UUID authorId) {
        return authorProfileRepository.findById(authorId).map(AuthorProfile::getAvatarUrl);
    }

    @Transactional(readOnly = true)
    public Map<UUID, String> getAuthorAvatarUrls(Set<UUID> authorIds) {
        return authorProfileRepository.findAllById(authorIds)
                .stream()
                .filter(p -> p.getAvatarUrl() != null)
                .collect(Collectors.toMap(AuthorProfile::getAuthorId, AuthorProfile::getAvatarUrl));
    }

    // ── Post Reactions ─────────────────────────────────────────────────────────

    @Transactional
    public Post react(UUID postId, UUID userId, ReactionEvent.ReactionType type, boolean add) {
        var post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));

        boolean alreadyReacted = reactionRepository
                .existsByPostIdAndUserIdAndReactionType(postId, userId, type);

        if (add) {
            if (alreadyReacted) return post;
            var oppositeType = type == ReactionEvent.ReactionType.LIKE
                    ? ReactionEvent.ReactionType.DISLIKE
                    : ReactionEvent.ReactionType.LIKE;
            reactionRepository.findByPostIdAndUserIdAndReactionType(postId, userId, oppositeType)
                    .ifPresent(existing -> {
                        reactionRepository.delete(existing);
                        if (oppositeType == ReactionEvent.ReactionType.LIKE) post.removeLike(userId);
                        else post.removeDislike(userId);
                    });
            reactionRepository.save(PostReaction.of(postId, userId, type));
            if (type == ReactionEvent.ReactionType.LIKE) post.addLike(userId);
            else post.addDislike(userId);
        } else {
            if (!alreadyReacted) return post;
            reactionRepository.findByPostIdAndUserIdAndReactionType(postId, userId, type)
                    .ifPresent(reactionRepository::delete);
            if (type == ReactionEvent.ReactionType.LIKE) post.removeLike(userId);
            else post.removeDislike(userId);
        }

        return postRepository.save(post);
    }

    // ── Comments ───────────────────────────────────────────────────────────────

    @Transactional
    public Comment addComment(UUID postId, UUID authorId, String authorUsername, String content, UUID parentCommentId) {
        var post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));
        var comment = Comment.create(postId, authorId, authorUsername, content, parentCommentId);
        // Only top-level comments contribute to the commentsCount
        if (parentCommentId == null) {
            post.incrementCommentsCount();
            postRepository.save(post);
        }
        return commentRepository.save(comment);
    }

    @Transactional(readOnly = true)
    public List<Comment> getComments(UUID postId, int page, int size) {
        return commentRepository.findByPostIdOrderByCreatedAtAsc(postId,
                PageRequest.of(page, size)).getContent();
    }

    @Transactional
    public Comment updateComment(UUID commentId, UUID requesterId, String content) {
        var comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("Comment not found: " + commentId));
        if (!comment.getAuthorId().equals(requesterId)) {
            throw new SecurityException("Only the author can edit this comment");
        }
        comment.edit(content);
        return commentRepository.save(comment);
    }

    @Transactional
    public void deleteComment(UUID commentId, UUID requesterId) {
        var comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("Comment not found: " + commentId));
        if (!comment.getAuthorId().equals(requesterId)) {
            throw new SecurityException("Only the author can delete this comment");
        }
        // Only decrement for top-level comments (replies cascade via FK)
        if (comment.getParentCommentId() == null) {
            var post = postRepository.findById(comment.getPostId()).orElse(null);
            if (post != null) {
                post.decrementCommentsCount();
                postRepository.save(post);
            }
        }
        commentRepository.delete(comment);
    }

    // ── Comment Reactions ──────────────────────────────────────────────────────

    @Transactional
    public Comment reactToComment(UUID commentId, UUID userId, ReactionEvent.ReactionType type, boolean add) {
        var comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("Comment not found: " + commentId));

        boolean alreadyReacted = commentReactionRepository
                .existsByCommentIdAndUserIdAndReactionType(commentId, userId, type);

        if (add) {
            if (alreadyReacted) return comment;
            var oppositeType = type == ReactionEvent.ReactionType.LIKE
                    ? ReactionEvent.ReactionType.DISLIKE
                    : ReactionEvent.ReactionType.LIKE;
            commentReactionRepository.findByCommentIdAndUserIdAndReactionType(commentId, userId, oppositeType)
                    .ifPresent(existing -> {
                        commentReactionRepository.delete(existing);
                        if (oppositeType == ReactionEvent.ReactionType.LIKE) comment.removeLike();
                        else comment.removeDislike();
                    });
            commentReactionRepository.save(CommentReaction.of(commentId, userId, type));
            if (type == ReactionEvent.ReactionType.LIKE) comment.addLike();
            else comment.addDislike();
        } else {
            if (!alreadyReacted) return comment;
            commentReactionRepository.findByCommentIdAndUserIdAndReactionType(commentId, userId, type)
                    .ifPresent(commentReactionRepository::delete);
            if (type == ReactionEvent.ReactionType.LIKE) comment.removeLike();
            else comment.removeDislike();
        }

        return commentRepository.save(comment);
    }
}
