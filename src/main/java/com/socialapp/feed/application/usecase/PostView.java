package com.socialapp.feed.application.usecase;

import com.socialapp.feed.application.media.MediaView;
import com.socialapp.feed.domain.Mention;
import com.socialapp.feed.domain.Post;
import com.socialapp.shared.events.ReactionEvent;

import java.util.List;

/**
 * A post with the read-side data the API shows next to it (batch-loaded, see {@link PostService#views}).
 *
 * @param repostsCount   REPOST rows pointing at this post
 * @param quotesCount    QUOTE rows pointing at this post
 * @param viewerReposted the viewer has a REPOST of this post (false without a viewer, e.g. SSE broadcast)
 * @param bookmarked     the viewer bookmarked this post (false without a viewer, e.g. SSE broadcast)
 * @param userReaction   the viewer's reaction on this post, or null (always null without a viewer)
 * @param original       for a REPOST/QUOTE: the embedded original, or null when it was deleted; null for a POST.
 *                       One level only (its own {@code original} is null), except a REPOST of a QUOTE, whose
 *                       original (the quote) carries the quoted post as a second level (whose original is null).
 * @param media          the post's own images ordered by position (a REPOST has none; its original's are in
 *                       {@code original}); never null
 * @param originalHidden the original exists but its author blocks the viewer or is blocked by them (stage 5A):
 *                       {@code original} is null and the API also drops the snapshot fields, exactly like a deleted
 *                       original ("unavailable")
 */
public record PostView(Post post, String authorAvatarUrl, List<String> hashtags, List<Mention> mentions,
                       long repostsCount, long quotesCount, boolean viewerReposted, boolean bookmarked,
                       ReactionEvent.ReactionType userReaction, PostView original, List<MediaView> media,
                       boolean originalHidden) {
    public PostView {
        hashtags = List.copyOf(hashtags);
        mentions = List.copyOf(mentions);
        media = media == null ? List.of() : List.copyOf(media);
    }

    /** Pre-5A shape (original never hidden). */
    public PostView(Post post, String authorAvatarUrl, List<String> hashtags, List<Mention> mentions,
                    long repostsCount, long quotesCount, boolean viewerReposted, boolean bookmarked,
                    ReactionEvent.ReactionType userReaction, PostView original, List<MediaView> media) {
        this(post, authorAvatarUrl, hashtags, mentions, repostsCount, quotesCount, viewerReposted, bookmarked,
                userReaction, original, media, false);
    }
}
