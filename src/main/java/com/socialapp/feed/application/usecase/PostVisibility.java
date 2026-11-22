package com.socialapp.feed.application.usecase;

import com.socialapp.feed.domain.BlockMuteRepository;
import com.socialapp.feed.domain.Post;
import com.socialapp.feed.domain.PostRepository;
import com.socialapp.shared.domain.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Point checks of Feed's block projection (stage 5A) for single posts: opening, reacting to, commenting on, reposting,
 * quoting and bookmarking a post of someone who blocks the viewer or is blocked by them is answered like an unknown
 * post (404), so the blocked side learns nothing beyond "unavailable". Mutes never restrict anything here. Lists do
 * not use this class: they filter in SQL ({@link PostRepository#LISTED_FOR_VIEWER}).
 */
@Component
@RequiredArgsConstructor
public class PostVisibility {

    private final BlockMuteRepository blockMutes;
    private final PostRepository postRepository;

    /** Those of {@code userIds} blocked either way with {@code viewerId} (one query; none without a viewer). */
    public Set<UUID> blockedAmong(UUID viewerId, Collection<UUID> userIds) {
        if (viewerId == null || userIds.isEmpty()) return Set.of();
        return blockMutes.blockedAmong(viewerId, userIds);
    }

    public boolean anyBlocked(UUID viewerId, Collection<UUID> userIds) {
        return !blockedAmong(viewerId, userIds).isEmpty();
    }

    /**
     * @throws NotFoundException if the author of {@code post} - or, for a REPOST item, of its original - blocks
     *         {@code viewerId} or is blocked by them
     */
    public void ensureNotBlocked(Post post, UUID viewerId) {
        if (viewerId == null) return;
        var authors = new HashSet<UUID>();
        authors.add(post.getAuthorId());
        if (post.isRepost() && post.getOriginalPostId() != null) {
            postRepository.findById(post.getOriginalPostId()).ifPresent(o -> authors.add(o.getAuthorId()));
        }
        if (anyBlocked(viewerId, authors)) throw new NotFoundException("Post not found: " + post.getId());
    }
}
