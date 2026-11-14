package com.socialapp.feed.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Resolved mentions of posts ({@code post_mentions}); written whenever a post's own text is saved. */
public interface PostMentionRepository {

    /** Replaces the mentions of {@code postId} (in order of appearance). */
    void replace(UUID postId, List<Mention> mentions);

    /** Mentions of each of {@code postIds} in order, with ONE query; posts without mentions are absent. */
    Map<UUID, List<Mention>> findByPostIds(Collection<UUID> postIds);
}
