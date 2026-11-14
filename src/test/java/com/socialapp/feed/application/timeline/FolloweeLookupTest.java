package com.socialapp.feed.application.timeline;

import com.socialapp.feed.domain.FollowRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FolloweeLookupTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();

    private final FollowRepository follows = mock(FollowRepository.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final FolloweeLookup lookup = new FolloweeLookup(follows, Duration.ofSeconds(5), clock);

    @Test
    void ownPostsAreAlwaysVisibleWithoutQuerying() {
        assertThat(lookup.isVisibleInFollowing(ALICE, ALICE)).isTrue();
        verifyNoInteractions(follows);
    }

    @Test
    void followeesAreLoadedOncePerTtl() {
        when(follows.findActiveFolloweeIds(ALICE)).thenReturn(List.of(BOB));

        assertThat(lookup.isVisibleInFollowing(ALICE, BOB)).isTrue();
        assertThat(lookup.isVisibleInFollowing(ALICE, CAROL)).isFalse();
        clock.advance(Duration.ofSeconds(4));
        assertThat(lookup.isVisibleInFollowing(ALICE, BOB)).isTrue();

        verify(follows, times(1)).findActiveFolloweeIds(ALICE);
    }

    @Test
    void changesAreSeenAfterTtl() {
        when(follows.findActiveFolloweeIds(ALICE)).thenReturn(List.of(), List.of(CAROL));

        assertThat(lookup.isVisibleInFollowing(ALICE, CAROL)).isFalse();
        clock.advance(Duration.ofSeconds(5));
        assertThat(lookup.isVisibleInFollowing(ALICE, CAROL)).isTrue();
    }

    @Test
    void invalidateMakesAChangeVisibleImmediately() {
        when(follows.findActiveFolloweeIds(ALICE)).thenReturn(List.of(BOB), List.of());

        assertThat(lookup.isVisibleInFollowing(ALICE, BOB)).isTrue();
        lookup.invalidate(ALICE);
        assertThat(lookup.isVisibleInFollowing(ALICE, BOB)).isFalse();
    }

    @Test
    void expiredEntriesOfOtherViewersArePurged() {
        when(follows.findActiveFolloweeIds(any())).thenReturn(List.of());
        lookup.isVisibleInFollowing(ALICE, BOB);
        lookup.isVisibleInFollowing(BOB, ALICE);
        assertThat(lookup.cachedViewers()).isEqualTo(2);

        clock.advance(Duration.ofSeconds(6));
        lookup.isVisibleInFollowing(CAROL, ALICE);

        assertThat(lookup.cachedViewers()).isEqualTo(1);
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
