package com.socialapp.feed.infrastructure.web;

import com.socialapp.feed.application.trends.TrendsService;
import com.socialapp.feed.domain.TrendingHashtag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/trends")
@RequiredArgsConstructor
public class TrendsController {

    private final TrendsService trendsService;

    /**
     * Trending hashtags: {@code [{tag, posts, authors}]}, most used first (distinct authors, then posts, then tag).
     * {@code hours} (window, default 24) is clamped to 1..168, {@code limit} (default 10) to 1..20. Nothing used in the
     * window -> 200 with an empty list.
     */
    @GetMapping("/hashtags")
    public ResponseEntity<List<TrendingHashtag>> trendingHashtags(
            @RequestParam(defaultValue = "" + TrendsService.DEFAULT_HOURS) int hours,
            @RequestParam(defaultValue = "" + TrendsService.DEFAULT_LIMIT) int limit) {
        return ResponseEntity.ok(trendsService.trendingHashtags(hours, limit));
    }
}
