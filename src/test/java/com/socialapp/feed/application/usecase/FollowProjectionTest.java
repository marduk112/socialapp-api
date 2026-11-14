package com.socialapp.feed.application.usecase;

import com.socialapp.feed.domain.FollowRepository;
import com.socialapp.shared.events.UserFollowedEvent;
import com.socialapp.shared.events.UserUnfollowedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Delegation and guards; the newest-wins SQL is exercised against Postgres in TimelinePostgresTest. */
class FollowProjectionTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final Instant T1 = Instant.parse("2026-01-01T10:00:00Z");

    private final FollowRepository follows = mock(FollowRepository.class);
    private final FollowProjection projection = new FollowProjection(follows);

    @Test
    void followAndUnfollowAreAppliedWithTheirOccurredAt() {
        when(follows.applyIfNewer(A, B, true, T1)).thenReturn(1);
        when(follows.applyIfNewer(A, B, false, T1.plusMillis(1))).thenReturn(0);

        assertThat(projection.on(new UserFollowedEvent(UUID.randomUUID(), T1, A, B))).isTrue();
        assertThat(projection.on(new UserUnfollowedEvent(UUID.randomUUID(), T1.plusMillis(1), A, B))).isFalse();

        verify(follows).applyIfNewer(A, B, true, T1);
        verify(follows).applyIfNewer(A, B, false, T1.plusMillis(1));
    }

    @Test
    void malformedEventsAreDroppedWithoutTouchingTheTable() {
        assertThat(projection.on(new UserFollowedEvent(UUID.randomUUID(), T1, A, A))).isFalse();
        assertThat(projection.on(new UserFollowedEvent(UUID.randomUUID(), null, A, B))).isFalse();
        assertThat(projection.on(new UserUnfollowedEvent(UUID.randomUUID(), T1, null, B))).isFalse();

        verifyNoInteractions(follows);
    }
}
