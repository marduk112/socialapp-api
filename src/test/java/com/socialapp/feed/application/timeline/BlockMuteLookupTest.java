package com.socialapp.feed.application.timeline;

import com.socialapp.feed.domain.BlockMuteRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** The SSE filter's per-viewer block/mute cache (stage 5A): one load per TTL, invalidation, block vs mute semantics. */
class BlockMuteLookupTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();

    private final BlockMuteRepository repository = mock(BlockMuteRepository.class);
    private final FolloweeLookupTest.MutableClock clock =
            new FolloweeLookupTest.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final BlockMuteLookup lookup = new BlockMuteLookup(repository, Duration.ofSeconds(5), clock);

    @Test
    void blocksHideAndBlock_mutesOnlyHide() {
        when(repository.blockedEitherWay(ALICE)).thenReturn(Set.of(BOB));
        when(repository.mutedBy(ALICE)).thenReturn(Set.of(CAROL));

        var restrictions = lookup.of(ALICE);

        assertThat(restrictions.blocks(BOB)).isTrue();
        assertThat(restrictions.hides(BOB)).isTrue();
        assertThat(restrictions.blocks(CAROL)).isFalse();
        assertThat(restrictions.hides(CAROL)).isTrue();
        assertThat(restrictions.hides(UUID.randomUUID())).isFalse();
        assertThat(restrictions.hides(null)).isFalse();
        assertThat(lookup.of(null)).isSameAs(BlockMuteLookup.Restrictions.NONE);
    }

    @Test
    void setsAreLoadedOncePerTtl_andInvalidationReloadsAtOnce() {
        when(repository.blockedEitherWay(ALICE)).thenReturn(Set.of(), Set.of(BOB));

        assertThat(lookup.of(ALICE).hides(BOB)).isFalse();
        clock.advance(Duration.ofSeconds(4));
        assertThat(lookup.of(ALICE).hides(BOB)).isFalse(); // cached
        lookup.invalidate(ALICE);
        assertThat(lookup.of(ALICE).hides(BOB)).isTrue();

        verify(repository, times(2)).blockedEitherWay(ALICE);
        verify(repository, times(2)).mutedBy(ALICE);
    }

    @Test
    void expiredEntriesArePurged() {
        lookup.of(ALICE);
        lookup.of(BOB);
        clock.advance(Duration.ofSeconds(6));
        lookup.of(CAROL);

        assertThat(lookup.cachedViewers()).isEqualTo(1);
    }
}
