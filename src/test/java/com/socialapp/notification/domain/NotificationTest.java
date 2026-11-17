package com.socialapp.notification.domain;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Dedupe / toggle / order-tolerance rules of one notification key. */
class NotificationTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID POST = UUID.randomUUID();
    private static final Instant T1 = Instant.parse("2026-11-15T10:00:00.000001Z");

    private static NotificationIntent like(Instant at, boolean retraction) {
        return new NotificationIntent(BOB, NotificationType.LIKE, ALICE, POST, POST, null, "s", "alice", at, retraction);
    }

    private static void markRead(Notification n) {
        ReflectionTestUtils.setField(n, "readAt", Instant.now());
    }

    @Test
    void firstAddIsVisibleUnread_redeliveryAndNewerAddsChangeNothing() {
        var n = Notification.from(like(T1, false));
        assertThat(n.isVisible()).isTrue();
        assertThat(n.isRead()).isFalse();
        assertThat(n.getCreatedAt()).isEqualTo(T1);

        assertThat(n.apply(like(T1, false))).isFalse(); // redelivery
        assertThat(n.apply(like(T1.plusSeconds(5), false))).isFalse(); // like again while visible
        assertThat(n.getCreatedAt()).isEqualTo(T1);
        assertThat(n.isVisible()).isTrue();
    }

    @Test
    void unlikeWhileUnreadHidesIt_likeAgainResurrectsTheSameNotificationAsNew() {
        var n = Notification.from(like(T1, false));
        UUID id = n.getId();

        assertThat(n.apply(like(T1.plusSeconds(1), true))).isFalse();
        assertThat(n.isVisible()).isFalse();

        assertThat(n.apply(like(T1.plusSeconds(2), false))).isTrue();
        assertThat(n.getId()).isEqualTo(id);
        assertThat(n.isVisible()).isTrue();
        assertThat(n.isRead()).isFalse();
        assertThat(n.getCreatedAt()).isEqualTo(T1.plusSeconds(2));
    }

    @Test
    void unlikeAfterItWasReadKeepsIt_andLikingAgainDoesNotNotifyAgain() {
        var n = Notification.from(like(T1, false));
        markRead(n);

        n.apply(like(T1.plusSeconds(1), true));
        assertThat(n.isVisible()).isTrue();
        assertThat(n.isRead()).isTrue();

        assertThat(n.apply(like(T1.plusSeconds(2), false))).isFalse();
        assertThat(n.isRead()).isTrue();
    }

    @Test
    void outOfOrder_anUnlikeSeenBeforeItsLikeLeavesATombstoneThatTheOlderLikeCannotResurrect() {
        var n = Notification.from(like(T1.plusSeconds(1), true));
        assertThat(n.isVisible()).isFalse();

        assertThat(n.apply(like(T1, false))).isFalse(); // older like, arriving late
        assertThat(n.isVisible()).isFalse();
    }

    @Test
    void lateOlderUnlikeDoesNotHideANewerLike() {
        var n = Notification.from(like(T1, false));
        n.apply(like(T1.plusSeconds(1), true));
        n.apply(like(T1.plusSeconds(2), false)); // resurrected
        n.apply(like(T1.plusSeconds(1), true)); // redelivered unlike
        assertThat(n.isVisible()).isTrue();
    }

    @Test
    void timestampsAreTruncatedToMicroseconds_soAnanosecondRedeliveryIsStillADuplicate() {
        var nanos = Instant.parse("2026-11-15T10:00:00.123456789Z");
        var n = Notification.from(like(nanos, false));
        assertThat(n.getCreatedAt()).isEqualTo(Instant.parse("2026-11-15T10:00:00.123456Z"));
        n.apply(like(nanos.plusNanos(100), true)); // same microsecond: treated as not newer
        assertThat(n.isVisible()).isTrue();
    }

    @Test
    void selfNotificationCannotEvenBeExpressed() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new NotificationIntent(ALICE,
                NotificationType.FOLLOW, ALICE, ALICE, null, null, null, null, T1, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
