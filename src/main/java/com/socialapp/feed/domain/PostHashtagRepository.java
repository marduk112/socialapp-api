package com.socialapp.feed.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Hashtag index of posts ({@code post_hashtags}); written whenever a post's own text is saved. */
public interface PostHashtagRepository {

    /**
     * Replaces the hashtags of {@code postId} (normalised, in display order). The post row must already be
     * written in the current transaction (flushed), its {@code created_at} is copied for keyset paging.
     */
    void replace(UUID postId, List<String> tags);

    /** Hashtags of each of {@code postIds} in display order, with ONE query; posts without tags are absent. */
    Map<UUID, List<String>> findByPostIds(Collection<UUID> postIds);

    /**
     * Hashtags of posts created at or after {@code since}, most used first: by distinct authors (so one account
     * repeating a tag cannot dominate), then by posts, then by tag; at most {@code limit}. ONE aggregate query over
     * {@code ix_post_hashtags_created} (V25). Posts of authors hidden from {@code viewerId} (blocked either way or
     * muted, stage 5A) are not counted at all.
     */
    List<TrendingHashtag> trending(UUID viewerId, Instant since, int limit);
}
