package com.socialapp.feed.application.trends;

import com.socialapp.feed.domain.PostHashtagRepository;
import com.socialapp.feed.domain.TrendingHashtag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Trending hashtags: what was used in the last {@code hours} hours, ranked by distinct authors, then posts.
 * Computed on every request (one indexed aggregate query); no cache, so a fresh post shows up at once.
 */
@Service
public class TrendsService {

    public static final int DEFAULT_HOURS = 24;
    public static final int MAX_HOURS = 168;
    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 20;

    private final PostHashtagRepository hashtags;
    private final Clock clock;

    @Autowired
    public TrendsService(PostHashtagRepository hashtags) {
        this(hashtags, Clock.systemUTC());
    }

    TrendsService(PostHashtagRepository hashtags, Clock clock) {
        this.hashtags = hashtags;
        this.clock = clock;
    }

    /** @param hours clamped to 1..{@value #MAX_HOURS}; @param limit clamped to 1..{@value #MAX_LIMIT} */
    @Transactional(readOnly = true)
    public List<TrendingHashtag> trendingHashtags(int hours, int limit) {
        int window = Math.max(1, Math.min(hours, MAX_HOURS));
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        return hashtags.trending(clock.instant().minus(Duration.ofHours(window)), size);
    }
}
