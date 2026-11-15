package com.socialapp.feed.application.usecase;

import com.socialapp.feed.domain.*;
import com.socialapp.shared.domain.NotFoundException;
import com.socialapp.shared.events.ReactionEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
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
    private final PostHashtagRepository hashtagRepository;
    private final PostMentionRepository mentionRepository;
    private final BookmarkRepository bookmarkRepository;

    // ── Posts CRUD ──────────────────────────────────────────────────────────────

    @Transactional
    public Post createPost(UUID authorId, String authorUsername, String content, String imageUrl) {
        var post = Post.create(authorId, currentUsername(authorId, authorUsername), content, imageUrl);
        return saveWithTextIndex(post);
    }

    @Transactional(readOnly = true)
    public Post findById(UUID id) {
        return postRepository.findById(id).orElseThrow(() -> postNotFound(id));
    }

    /**
     * Edits the post (a QUOTE: its comment) and replaces its hashtags and mentions. The row is locked so concurrent edits of the
     * same post (e.g. a double submit) replace the index sets one after the other instead of colliding.
     */
    @Transactional
    public Post updatePost(UUID postId, UUID requesterId, String content, String imageUrl) {
        // Ownership first, then type (a REPOST cannot be edited: 409), then content rules (Post#edit).
        var post = postRepository.findByIdForUpdate(postId).orElseThrow(() -> postNotFound(postId));
        post.edit(requesterId, content, imageUrl);
        return saveWithTextIndex(post);
    }

    /**
     * Deletes a post (also a repost or quote) of the requester. Deleting a post that others reposted/quoted also
     * deletes its REPOSTs and detaches its QUOTEs (snapshot cleared; the FK nulls their {@code original_post_id},
     * so they show "original unavailable"). The row is locked first, which serialises the deletion with
     * concurrent reposts/quotes of it (they lock the same row), so no repost can slip in and be orphaned.
     */
    @Transactional
    public void deletePost(UUID postId, UUID requesterId) {
        var post = postRepository.findByIdForUpdate(postId).orElseThrow(() -> postNotFound(postId));
        post.ensureAuthoredBy(requesterId, "delete");
        if (!post.isRepost()) { // nothing ever points at a REPOST (flattened)
            postRepository.deleteRepostsOf(postId);
            postRepository.detachQuotesOf(postId);
        }
        postRepository.delete(post);
    }

    // ── Reposts and quotes ─────────────────────────────────────────────────────

    /** Result of {@link #repost}: the caller's repost and whether this call created it (201) or found it (200). */
    public record RepostResult(Post post, boolean created) {}

    /**
     * Reposts {@code targetId} (a repost item resolves to its original). Idempotent per (author, original): a
     * second call returns the existing repost. The original's row is locked, so concurrent reposts by the same
     * user (double click) are serialised and the second one finds the first one's row (same approach as
     * reactions); the V18 partial unique index is the backstop.
     *
     * @throws NotFoundException unknown/deleted target
     */
    @Transactional
    public RepostResult repost(UUID targetId, UUID authorId, String authorUsername) {
        var original = lockTarget(targetId);
        var existing = postRepository.findRepost(authorId, original.getId());
        if (existing.isPresent()) return new RepostResult(existing.get(), false);
        var repost = Post.repost(authorId, currentUsername(authorId, authorUsername), original);
        return new RepostResult(saveWithTextIndex(repost), true);
    }

    /**
     * Removes the caller's repost of {@code targetId} (an original, or a repost item resolved to its original).
     * Idempotent: no repost, or an unknown id, is a no-op.
     */
    @Transactional
    public void undoRepost(UUID targetId, UUID authorId) {
        var item = postRepository.findById(targetId);
        if (item.isEmpty()) return;
        var originalId = item.get().repostTargetId();
        if (originalId.isEmpty()) return;
        // Same lock as repost(), so an undo racing a repost of the same original is serialised with it.
        if (postRepository.findByIdForUpdate(originalId.get()).isEmpty()) return;
        postRepository.deleteRepost(authorId, originalId.get());
    }

    /**
     * Quotes {@code targetId} (a repost item resolves to its original) with a required comment; hashtags and
     * mentions of the comment are indexed like a post's. Unlimited per user.
     *
     * @throws NotFoundException unknown/deleted target
     * @throws IllegalArgumentException blank comment or longer than {@link Post#MAX_CONTENT_LENGTH} code points
     */
    @Transactional
    public Post quote(UUID targetId, UUID authorId, String authorUsername, String comment) {
        var original = lockTarget(targetId);
        var quote = Post.quote(authorId, currentUsername(authorId, authorUsername), original, comment);
        return saveWithTextIndex(quote);
    }

    /**
     * Deprecated {@code POST /posts/{id}/share}: a blank/missing comment means repost (idempotent), otherwise
     * quote.
     */
    @Deprecated
    @Transactional
    public RepostResult sharePost(UUID targetId, UUID authorId, String authorUsername, String comment) {
        if (comment == null || comment.isBlank()) return repost(targetId, authorId, authorUsername);
        return new RepostResult(quote(targetId, authorId, authorUsername, comment), true);
    }

    /**
     * Resolves the post a repost/quote of {@code targetId} must point at (a REPOST item -> its original) and locks
     * its row. The item itself is read without a lock: locking a repost row and then its original would invert
     * the lock order of {@link #deletePost} (original, then its reposts) and could deadlock.
     */
    private Post lockTarget(UUID targetId) {
        var item = postRepository.findById(targetId).orElseThrow(() -> postNotFound(targetId));
        var originalId = item.repostTargetId().orElseThrow(() -> postNotFound(targetId));
        return postRepository.findByIdForUpdate(originalId).orElseThrow(() -> postNotFound(targetId));
    }

    // ── Read model ─────────────────────────────────────────────────────────────

    /**
     * The posts as seen by {@code viewerId} (null = nobody, e.g. an SSE broadcast), in the given order, with
     * author avatar, hashtags, mentions, repost/quote counters, the viewer's repost flag, bookmark flag and reaction, and the
     * embedded original of reposts/quotes (see {@link PostView#original()}).
     *
     * <p>Constant number of queries regardless of the number of posts: at most two for the originals (the
     * embedded originals, then the quoted posts of reposted quotes), then one IN query each for avatars,
     * hashtags, mentions, repost/quote counts and - with a viewer - reposted flags, bookmarks and reactions, all covering the
     * page posts and their originals together. No N+1.
     */
    @Transactional(readOnly = true)
    public List<PostView> views(List<Post> posts, UUID viewerId) {
        if (posts.isEmpty()) return List.of();
        Map<UUID, Post> byId = new LinkedHashMap<>();
        posts.forEach(p -> byId.putIfAbsent(p.getId(), p));

        // Level 1: originals of reposts/quotes.
        loadMissing(byId, posts.stream()
                .filter(Post::embedsOriginal)
                .map(Post::getOriginalPostId));
        // Level 2: only a REPOST of a QUOTE shows the quoted post inside the quote.
        loadMissing(byId, posts.stream()
                .filter(Post::isRepost)
                .map(p -> byId.get(p.getOriginalPostId()))
                .filter(o -> o != null && o.getType() == PostType.QUOTE)
                .map(Post::getOriginalPostId));

        var all = List.copyOf(byId.values());
        var ids = all.stream().map(Post::getId).toList();
        var avatars = getAuthorAvatarUrls(all.stream().map(Post::getAuthorId).collect(Collectors.toSet()));
        var hashtags = hashtagRepository.findByPostIds(ids);
        var mentions = mentionRepository.findByPostIds(ids);
        Map<UUID, long[]> counts = new HashMap<>(); // [reposts, quotes]
        for (Object[] row : postRepository.countRepostsAndQuotes(ids)) {
            var c = counts.computeIfAbsent((UUID) row[0], k -> new long[2]);
            long n = ((Number) row[2]).longValue();
            if (row[1] == PostType.REPOST) c[0] += n;
            else if (row[1] == PostType.QUOTE) c[1] += n;
        }
        Set<UUID> reposted = viewerId == null ? Set.of()
                : new HashSet<>(postRepository.findRepostedOriginalIds(viewerId, ids));
        Set<UUID> bookmarked = viewerId == null ? Set.of()
                : new HashSet<>(bookmarkRepository.findBookmarkedPostIds(viewerId, ids));
        Map<UUID, ReactionEvent.ReactionType> reactions = new HashMap<>();
        if (viewerId != null) {
            for (var r : reactionRepository.findByUserIdAndPostIdIn(viewerId, ids)) {
                reactions.merge(r.getPostId(), r.getReactionType(),
                        (a, b) -> a == ReactionEvent.ReactionType.LIKE ? a : b);
            }
        }

        java.util.function.BiFunction<Post, PostView, PostView> view = (p, original) -> {
            var c = counts.getOrDefault(p.getId(), new long[2]);
            return new PostView(p, avatars.get(p.getAuthorId()),
                    hashtags.getOrDefault(p.getId(), List.of()),
                    mentions.getOrDefault(p.getId(), List.of()),
                    c[0], c[1], reposted.contains(p.getId()), bookmarked.contains(p.getId()), reactions.get(p.getId()), original);
        };
        return posts.stream().map(p -> {
            var original = p.embedsOriginal() ? byId.get(p.getOriginalPostId()) : null;
            if (original == null) return view.apply(p, null);
            var quoted = p.isRepost() && original.getType() == PostType.QUOTE
                    ? byId.get(original.getOriginalPostId()) : null;
            var quotedView = quoted == null ? null : view.apply(quoted, null);
            return view.apply(p, view.apply(original, quotedView));
        }).toList();
    }

    @Transactional(readOnly = true)
    public PostView view(Post post, UUID viewerId) {
        return views(List.of(post), viewerId).get(0);
    }

    /** Adds the posts among {@code ids} that are not in {@code byId} yet, with ONE query (none if nothing new). */
    private void loadMissing(Map<UUID, Post> byId, java.util.stream.Stream<UUID> ids) {
        var missing = ids.filter(id -> id != null && !byId.containsKey(id)).collect(Collectors.toSet());
        if (missing.isEmpty()) return;
        postRepository.findAllById(missing).forEach(p -> byId.putIfAbsent(p.getId(), p));
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

    /**
     * Idempotent like/dislike toggle. The post row is locked for the transaction, so concurrent
     * reactions (including a double-click) are serialised: no lost counter updates and no duplicate
     * reaction rows (the second request sees the first one's row and becomes a no-op).
     */
    @Transactional
    public Post react(UUID postId, UUID userId, ReactionEvent.ReactionType type, boolean add) {
        var post = postRepository.findByIdForUpdate(postId).orElseThrow(() -> postNotFound(postId));

        var existing = reactionRepository.findByPostIdAndUserId(postId, userId);
        var change = post.react(userId, typesOf(existing, PostReaction::getReactionType), type, add);
        if (change.isNoop()) return post;

        reactionRepository.deleteAll(existing.stream()
                .filter(r -> change.removed().contains(r.getReactionType()))
                .toList());
        if (change.added() != null) {
            reactionRepository.save(PostReaction.of(postId, userId, change.added()));
        }
        return postRepository.save(post);
    }

    // ── Comments ───────────────────────────────────────────────────────────────

    @Transactional
    public Comment addComment(UUID postId, UUID authorId, String authorUsername, String content, UUID parentCommentId) {
        // Lock order is always post -> comment (same as deleteComment) to avoid deadlocks.
        // The post lock is needed for commentsCount and turns a missing post into a 404, not an FK error.
        var post = postRepository.findByIdForUpdate(postId).orElseThrow(() -> postNotFound(postId));
        Comment parent = null;
        if (parentCommentId != null) {
            // Locked so a concurrent delete of the parent cannot slip in between check and insert.
            parent = commentRepository.findByIdForUpdate(parentCommentId)
                    .orElseThrow(() -> new NotFoundException("Parent comment not found: " + parentCommentId));
        }
        // Rejects a parent of another post (400); registers CommentCreatedEvent (published on save via the outbox).
        var comment = Comment.create(post, parent, authorId, currentUsername(authorId, authorUsername), content);
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
        var comment = commentRepository.findById(commentId).orElseThrow(() -> commentNotFound(commentId));
        comment.edit(requesterId, content);
        return commentRepository.save(comment);
    }

    @Transactional
    public void deleteComment(UUID commentId, UUID requesterId) {
        var comment = commentRepository.findById(commentId).orElseThrow(() -> commentNotFound(commentId));
        comment.ensureAuthoredBy(requesterId, "delete");
        // Only decrement for top-level comments (replies cascade via FK)
        if (comment.getParentCommentId() == null) {
            postRepository.findByIdForUpdate(comment.getPostId()).ifPresent(post -> {
                post.decrementCommentsCount();
                postRepository.save(post);
            });
        }
        commentRepository.delete(comment);
    }

    // ── Comment Reactions ──────────────────────────────────────────────────────

    /** Idempotent like/dislike toggle on a comment; serialised per comment by a row lock (see {@link #react}). */
    @Transactional
    public Comment reactToComment(UUID commentId, UUID userId, ReactionEvent.ReactionType type, boolean add) {
        var comment = commentRepository.findByIdForUpdate(commentId).orElseThrow(() -> commentNotFound(commentId));

        var existing = commentReactionRepository.findByCommentIdAndUserId(commentId, userId);
        var change = comment.react(userId, typesOf(existing, CommentReaction::getReactionType), type, add);
        if (change.isNoop()) return comment;

        commentReactionRepository.deleteAll(existing.stream()
                .filter(r -> change.removed().contains(r.getReactionType()))
                .toList());
        if (change.added() != null) {
            commentReactionRepository.save(CommentReaction.of(commentId, userId, change.added()));
        }
        return commentRepository.save(comment);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Saves the post and (re)writes its hashtags and resolved mentions in the same transaction. The post is
     * flushed first because the index rows reference (and copy {@code created_at} from) its row. A new post's
     * resolved mention ids are also put into its PostCreatedEvent ({@link Post#announceMentions}).
     */
    private Post saveWithTextIndex(Post post) {
        // Resolved before save(): a new post announces its mentions in its PostCreatedEvent, which Spring Data
        // publishes (to the outbox) when save() is called.
        var mentions = resolveMentions(post);
        post.announceMentions(mentions);
        var saved = postRepository.save(post);
        postRepository.flush();
        hashtagRepository.replace(saved.getId(), saved.hashtags());
        mentionRepository.replace(saved.getId(), mentions);
        return saved;
    }

    /**
     * Resolves the post's {@code @name} candidates against Feed's own {@code author_profiles} projection with
     * one batched lookup (no call into UserManagement; see {@link MentionLookup}, shared with the legacy backfill).
     * Unknown/ambiguous names and self-mentions are dropped.
     */
    private List<Mention> resolveMentions(Post post) {
        var candidates = post.mentionCandidates();
        if (candidates.isEmpty()) return List.of();
        var keys = candidates.stream().map(MentionParser::key).collect(Collectors.toSet());
        return MentionParser.resolve(candidates, MentionLookup.knownUsers(authorProfileRepository, keys),
                post.getAuthorId());
    }

    /**
     * The username to denormalise into new posts/comments. Feed's own projection (kept current by
     * user-events) wins over the caller's token claim, which can be stale after a rename until the
     * user logs in again. Falls back to the token when no projection exists yet.
     */
    private String currentUsername(UUID authorId, String tokenUsername) {
        return authorProfileRepository.findById(authorId)
                .map(AuthorProfile::getUsername)
                .filter(u -> !u.isBlank())
                .orElse(tokenUsername);
    }

    private static <R> Set<ReactionEvent.ReactionType> typesOf(
            List<R> reactions, java.util.function.Function<R, ReactionEvent.ReactionType> type) {
        var types = EnumSet.noneOf(ReactionEvent.ReactionType.class);
        reactions.forEach(r -> types.add(type.apply(r)));
        return types;
    }

    private static NotFoundException postNotFound(UUID id) {
        return new NotFoundException("Post not found: " + id);
    }

    private static NotFoundException commentNotFound(UUID id) {
        return new NotFoundException("Comment not found: " + id);
    }
}
