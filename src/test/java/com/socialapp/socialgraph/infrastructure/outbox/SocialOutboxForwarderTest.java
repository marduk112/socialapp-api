package com.socialapp.socialgraph.infrastructure.outbox;

import com.socialapp.config.outbox.OutboxImporter;
import com.socialapp.shared.events.DomainEvent;
import com.socialapp.shared.events.UserBlockedEvent;
import com.socialapp.shared.events.UserFollowedEvent;
import com.socialapp.shared.events.UserMutedEvent;
import com.socialapp.shared.events.UserUnblockedEvent;
import com.socialapp.shared.events.UserUnmutedEvent;
import com.socialapp.shared.events.UserUnfollowedEvent;
import com.socialapp.socialgraph.infrastructure.outbox.SocialOutboxStore.PendingSocialEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Hand-over protocol Neo4j -> Postgres outbox, with both stores mocked. */
class SocialOutboxForwarderTest {

    private static final String A = UUID.randomUUID().toString();
    private static final String B = UUID.randomUUID().toString();

    private final SocialOutboxStore store = mock(SocialOutboxStore.class);
    private final OutboxImporter importer = mock(OutboxImporter.class);
    private final List<Runnable> queuedWakeUps = new ArrayList<>();
    private final SocialOutboxForwarder forwarder = new SocialOutboxForwarder(store, importer, queuedWakeUps::add, 2);

    private static PendingSocialEvent pending(String type, long at) {
        return new PendingSocialEvent(UUID.randomUUID().toString(), type, A, B, at);
    }

    @SuppressWarnings("unchecked")
    private List<DomainEvent> imported(int call) {
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(importer, atLeast(call + 1)).importBatch(captor.capture());
        return (List<DomainEvent>) captor.getAllValues().get(call);
    }

    @Test
    void importsThenDeletesInOrder() {
        var follow = pending("UserFollowedEvent", 1000);
        var unfollow = pending("UserUnfollowedEvent", 1001);
        when(store.findPending(2)).thenReturn(List.of(follow, unfollow), List.of());
        when(importer.importBatch(anyList())).thenReturn(true);

        assertThat(forwarder.forwardPending()).isEqualTo(2);

        var events = imported(0);
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isEqualTo(new UserFollowedEvent(UUID.fromString(follow.eventId()),
                Instant.ofEpochMilli(1000), UUID.fromString(A), UUID.fromString(B)));
        assertThat(events.get(1)).isInstanceOf(UserUnfollowedEvent.class);
        var order = inOrder(importer, store);
        order.verify(importer).importBatch(anyList());
        order.verify(store).delete(List.of(follow.eventId(), unfollow.eventId()));
    }

    @Test
    void failedImportKeepsEventsInNeo4j_andTheNextRunForwardsThem() {
        var follow = pending("UserFollowedEvent", 1000);
        when(store.findPending(2)).thenReturn(List.of(follow));
        when(importer.importBatch(anyList()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("postgres down"))
                .thenReturn(true);

        forwarder.poll(); // failure is caught and logged
        verify(store, never()).delete(any());

        forwarder.poll();
        verify(store).delete(List.of(follow.eventId()));
        assertThat(imported(0)).isEqualTo(imported(1)); // the same event (same eventId) offered again
    }

    @Test
    void importLockHeldElsewhereLeavesEventsForLater() {
        when(store.findPending(2)).thenReturn(List.of(pending("UserFollowedEvent", 1)));
        when(importer.importBatch(anyList())).thenReturn(false);

        assertThat(forwarder.forwardPending()).isZero();

        verify(store, never()).delete(any());
    }

    @Test
    void failedDeleteAfterImportIsRetriedAndReImportIsHarmless() {
        var follow = pending("UserFollowedEvent", 1000);
        when(store.findPending(2)).thenReturn(List.of(follow));
        when(importer.importBatch(anyList())).thenReturn(true);
        doThrow(new IllegalStateException("neo4j down")).doNothing().when(store).delete(any());

        forwarder.poll();
        forwarder.poll();

        verify(importer, times(2)).importBatch(anyList()); // idempotent by eventId on the Postgres side
        verify(store, times(2)).delete(List.of(follow.eventId()));
    }

    @Test
    void fullBatchesAreDrainedUntilEmpty() {
        var e1 = pending("UserFollowedEvent", 1);
        var e2 = pending("UserUnfollowedEvent", 2);
        var e3 = pending("UserFollowedEvent", 3);
        when(store.findPending(2)).thenReturn(List.of(e1, e2), List.of(e3));
        when(importer.importBatch(anyList())).thenReturn(true);

        assertThat(forwarder.forwardPending()).isEqualTo(3);

        verify(store).delete(List.of(e1.eventId(), e2.eventId()));
        verify(store).delete(List.of(e3.eventId()));
    }

    @Test
    void malformedNodesAreSkippedAndLeftForRepair() {
        var bad = new PendingSocialEvent("not-a-uuid", "UserFollowedEvent", A, B, 1L);
        var unknownType = new PendingSocialEvent(UUID.randomUUID().toString(), "Nope", A, B, 2L);
        var missing = new PendingSocialEvent(UUID.randomUUID().toString(), "UserFollowedEvent", A, B, null);
        var good = pending("UserFollowedEvent", 3);
        var forwarder3 = new SocialOutboxForwarder(store, importer, Runnable::run, 10);
        when(store.findPending(10)).thenReturn(List.of(bad, unknownType, missing, good));
        when(importer.importBatch(anyList())).thenReturn(true);

        assertThat(forwarder3.forwardPending()).isEqualTo(1);

        verify(store).delete(List.of(good.eventId()));
    }

    @Test
    void wakeUpsAreCoalescedAndIgnoredAfterShutdown() {
        forwarder.wakeUp();
        forwarder.wakeUp();
        assertThat(queuedWakeUps).hasSize(1);

        queuedWakeUps.get(0).run();
        verify(store).findPending(2);

        forwarder.stop();
        forwarder.wakeUp();
        forwarder.poll();
        assertThat(queuedWakeUps).hasSize(1);
        verify(store, times(1)).findPending(anyInt());
    }

    @Test
    void blockAndMuteNodesBecomeTheirEvents() {
        var id = UUID.randomUUID().toString();
        var at = Instant.ofEpochMilli(42L);
        var a = UUID.fromString(A);
        var b = UUID.fromString(B);

        assertThat(SocialOutboxForwarder.toEvent(new PendingSocialEvent(id, "UserBlockedEvent", A, B, 42L)))
                .isEqualTo(new UserBlockedEvent(UUID.fromString(id), at, a, b));
        assertThat(SocialOutboxForwarder.toEvent(new PendingSocialEvent(id, "UserUnblockedEvent", A, B, 42L)))
                .isEqualTo(new UserUnblockedEvent(UUID.fromString(id), at, a, b));
        assertThat(SocialOutboxForwarder.toEvent(new PendingSocialEvent(id, "UserMutedEvent", A, B, 42L)))
                .isEqualTo(new UserMutedEvent(UUID.fromString(id), at, a, b));
        assertThat(SocialOutboxForwarder.toEvent(new PendingSocialEvent(id, "UserUnmutedEvent", A, B, 42L)))
                .isEqualTo(new UserUnmutedEvent(UUID.fromString(id), at, a, b));
        assertThat(SocialOutboxForwarder.toEvent(new PendingSocialEvent(id, "UserBlockedEvent", A, null, 42L))).isNull();
    }
}
