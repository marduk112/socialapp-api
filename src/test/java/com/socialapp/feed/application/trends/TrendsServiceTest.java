package com.socialapp.feed.application.trends;

import com.socialapp.feed.domain.PostHashtagRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrendsServiceTest {

    private static final java.util.UUID VIEWER = java.util.UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
    private final PostHashtagRepository repo = mock(PostHashtagRepository.class);
    private final TrendsService service = new TrendsService(repo, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void windowIsCountedBackFromNow() {
        when(repo.trending(any(), any(), anyInt())).thenReturn(List.of());

        service.trendingHashtags(VIEWER, 24, 10);

        verify(repo).trending(VIEWER, Instant.parse("2026-11-16T12:00:00Z"), 10);
    }

    @Test
    void hoursAndLimitAreClamped() {
        when(repo.trending(any(), any(), anyInt())).thenReturn(List.of());

        service.trendingHashtags(VIEWER, 100_000, 1000);
        service.trendingHashtags(VIEWER, 0, 0);

        verify(repo).trending(VIEWER, NOW.minusSeconds(TrendsService.MAX_HOURS * 3600L), TrendsService.MAX_LIMIT);
        verify(repo).trending(VIEWER, NOW.minusSeconds(3600), 1);
    }
}
