package com.socialapp.feed.infrastructure.web;

import com.socialapp.feed.application.media.MediaView;
import com.socialapp.feed.application.usecase.PostView;
import com.socialapp.feed.domain.Mention;
import com.socialapp.feed.domain.Post;
import com.socialapp.feed.domain.PostType;
import com.socialapp.shared.events.ReactionEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A post as every endpoint returns it (also the nested {@code original} and the SSE payload's {@code original}).
 * <ul>
 *   <li>{@code type}: POST | REPOST | QUOTE. A REPOST has {@code content = ""}; its counters/reactions are those
 *       of the repost row itself - clients act on {@code original}.</li>
 *   <li>{@code hashtags} / {@code mentions}: of the post's own text only (a quote's comment, never the original's
 *       text); always present, possibly empty.</li>
 *   <li>{@code repostsCount} / {@code quotesCount}: REPOST / QUOTE rows pointing at this post.</li>
 *   <li>{@code viewerReposted}: the caller has a REPOST of this post; {@code bookmarked}: the caller bookmarked this
 *       post (the item itself, a repost item is bookmarked as such); {@code userReaction}: the caller's
 *       LIKE/DISLIKE or null. All false/null where there is no caller (SSE broadcast).</li>
 *   <li>{@code original}: the full original of a REPOST/QUOTE (null for a POST, or when the original was
 *       deleted: "unavailable"). One level (its {@code original} is null), except a REPOST of a QUOTE: there the
 *       nested quote carries its quoted post (second level, whose {@code original} is null).</li>
 *   <li>{@code originalPostId}, {@code originalAuthorUsername}, {@code originalContent}: legacy flat fields kept
 *       for compatibility (snapshot taken when the repost/quote was created); {@code originalAuthorId} is the
 *       current original's author (null when it is gone).</li>
 *   <li>{@code media}: the post's own images {@code [{id, url, width, height}]} ordered by position, {@code []} when
 *       none (always for a REPOST: its original's images are in {@code original.media}). Stage 3C, appended last.</li>
 * </ul>
 */
public record PostResponse(
        UUID id, UUID authorId, String authorUsername, String authorAvatarUrl,
        String content, String imageUrl,
        long likesCount, long dislikesCount, long commentsCount,
        UUID originalPostId, String originalAuthorUsername, String originalContent,
        Instant createdAt, Instant updatedAt,
        List<String> hashtags, List<Mention> mentions,
        PostType type, UUID originalAuthorId,
        long repostsCount, long quotesCount, boolean viewerReposted, boolean bookmarked,
        ReactionEvent.ReactionType userReaction,
        PostResponse original,
        List<MediaView> media
) {
    public static PostResponse from(PostView view) {
        if (view == null) return null;
        Post p = view.post();
        var original = from(view.original());
        return new PostResponse(
                p.getId(), p.getAuthorId(), p.getAuthorUsername(), view.authorAvatarUrl(),
                p.getContent(), p.getImageUrl(),
                p.getLikesCount(), p.getDislikesCount(), p.getCommentsCount(),
                p.getOriginalPostId(), p.getOriginalAuthorUsername(), p.getOriginalContent(),
                p.getCreatedAt(), p.getUpdatedAt(),
                view.hashtags(), view.mentions(),
                p.getType(), original == null ? null : original.authorId(),
                view.repostsCount(), view.quotesCount(), view.viewerReposted(), view.bookmarked(),
                view.userReaction(),
                original,
                view.media()
        );
    }
}
