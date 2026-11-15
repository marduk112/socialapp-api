package com.socialapp.notification.infrastructure.persistence;

import com.socialapp.notification.application.ActorProfileProjection;
import com.socialapp.notification.application.NotificationCursor;
import com.socialapp.notification.application.NotificationService;
import com.socialapp.notification.application.NotificationView;
import com.socialapp.notification.domain.NotificationType;
import com.socialapp.shared.events.CommentCreatedEvent;
import com.socialapp.shared.events.PostCreatedEvent;
import com.socialapp.shared.events.ReactionEvent;
import com.socialapp.shared.events.ReactionEvent.ReactionAction;
import com.socialapp.shared.events.ReactionEvent.ReactionTarget;
import com.socialapp.shared.events.ReactionEvent.ReactionType;
import com.socialapp.shared.events.UserCreatedEvent;
import com.socialapp.shared.events.UserFollowedEvent;
import com.socialapp.shared.events.UserUnfollowedEvent;
import com.socialapp.shared.events.UserUpdatedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Notification BC against a real PostgreSQL with the Flyway schema (V20 incl. entity validation): event handling
 * (dedupe, toggling, out-of-order, precedence, cutoff), the actor profile projection and the read side (keyset
 * pagination with ties, isolation between recipients, read marking). Each test runs in a rolled-back transaction.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import({NotificationService.class, ActorProfileProjection.class, JdbcNotificationQueries.class,
        JdbcNotificationCutoff.class})
class NotificationPostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /** Safely after the V20 cutoff (written when the schema was migrated). */
    private static final Instant T0 = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();

    @Autowired NotificationService service;
    @Autowired ActorProfileProjection profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager em;

    private static Instant t(int seconds) {
        return T0.plusSeconds(seconds);
    }

    private List<NotificationView> all(UUID recipient) {
        em.flush(); // the projection writes through JPA, the read side through JDBC (one test transaction)
        return service.page(recipient, null, 100).items();
    }

    private ReactionEvent like(UUID post, UUID owner, UUID actor, ReactionAction action, Instant at) {
        return new ReactionEvent(UUID.randomUUID(), at, post, actor, ReactionType.LIKE, action, ReactionTarget.POST,
                null, owner, "liked post");
    }

    // ── Dedupe / toggling / order ───────────────────────────────────────────

    @Test
    void followNotifiesOnce_redeliveryIsANoop_unfollowWhileUnreadHides_followAgainResurrectsTheSameId() {
        var follow = new UserFollowedEvent(UUID.randomUUID(), t(1), alice, bob);
        var created = service.on(follow);
        assertThat(created).singleElement().satisfies(n -> {
            assertThat(n.type()).isEqualTo(NotificationType.FOLLOW);
            assertThat(n.recipientId()).isEqualTo(bob);
            assertThat(n.actorId()).isEqualTo(alice);
            assertThat(n.postId()).isNull();
            assertThat(n.read()).isFalse();
        });
        assertThat(service.on(follow)).isEmpty(); // Kafka redelivery
        assertThat(all(bob)).hasSize(1);
        assertThat(service.unreadCount(bob)).isEqualTo(1);

        assertThat(service.on(new UserUnfollowedEvent(UUID.randomUUID(), t(2), alice, bob))).isEmpty();
        assertThat(all(bob)).isEmpty();
        assertThat(service.unreadCount(bob)).isZero();

        var again = service.on(new UserFollowedEvent(UUID.randomUUID(), t(3), alice, bob));
        assertThat(again).singleElement().satisfies(n -> {
            assertThat(n.id()).isEqualTo(created.get(0).id());
            assertThat(n.createdAt()).isEqualTo(t(3));
        });
        assertThat(all(bob)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id = ?", Long.class, bob))
                .isEqualTo(1);
    }

    @Test
    void likeUnlikeLikeAfterReadingNeverNotifiesAgain_andTheReadNotificationStays() {
        UUID post = UUID.randomUUID();
        assertThat(service.on(like(post, bob, alice, ReactionAction.ADDED, t(1)))).hasSize(1);
        service.markRead(bob, null);

        assertThat(service.on(like(post, bob, alice, ReactionAction.REMOVED, t(2)))).isEmpty();
        assertThat(service.on(like(post, bob, alice, ReactionAction.ADDED, t(3)))).isEmpty();
        assertThat(all(bob)).singleElement().satisfies(n -> {
            assertThat(n.read()).isTrue();
            assertThat(n.snippet()).isEqualTo("liked post");
        });
        assertThat(service.unreadCount(bob)).isZero();
    }

    @Test
    void outOfOrder_unlikeBeforeItsLike_staysHidden_dislikesNeverNotify() {
        UUID post = UUID.randomUUID();
        assertThat(service.on(like(post, bob, alice, ReactionAction.REMOVED, t(2)))).isEmpty();
        assertThat(service.on(like(post, bob, alice, ReactionAction.ADDED, t(1)))).isEmpty(); // late, older
        assertThat(all(bob)).isEmpty();

        assertThat(service.on(new ReactionEvent(UUID.randomUUID(), t(3), post, carol, ReactionType.DISLIKE,
                ReactionAction.ADDED, ReactionTarget.POST, null, bob, "x"))).isEmpty();
        assertThat(all(bob)).isEmpty();
    }

    @Test
    void commentLikeTargetsTheCommentAndOpensItsPost() {
        UUID post = UUID.randomUUID();
        UUID comment = UUID.randomUUID();
        var created = service.on(new ReactionEvent(UUID.randomUUID(), t(1), post, alice, ReactionType.LIKE,
                ReactionAction.ADDED, ReactionTarget.COMMENT, comment, carol, "a comment"));
        assertThat(created).singleElement().satisfies(n -> {
            assertThat(n.recipientId()).isEqualTo(carol);
            assertThat(n.postId()).isEqualTo(post);
            assertThat(n.commentId()).isEqualTo(comment);
        });
        // a like of the post itself by the same actor is a different notification (different target)
        assertThat(service.on(like(post, carol, alice, ReactionAction.ADDED, t(2)))).hasSize(1);
        assertThat(all(carol)).hasSize(2);
    }

    @Test
    void replyOnYourOwnPostToYourCommentIsASingleReply_redeliveredCommentEventIsANoop() {
        UUID post = UUID.randomUUID();
        var reply = new CommentCreatedEvent(UUID.randomUUID(), t(1), UUID.randomUUID(), post, bob, UUID.randomUUID(),
                bob, alice, "alice", "a reply");
        assertThat(service.on(reply)).extracting(NotificationView::type).containsExactly(NotificationType.REPLY);
        assertThat(service.on(reply)).isEmpty();
        assertThat(all(bob)).extracting(NotificationView::type).containsExactly(NotificationType.REPLY);
    }

    @Test
    void repostOnceAfterUndoAndRepostAgain_quoteAndMentionPrecedence() {
        UUID original = UUID.randomUUID();
        assertThat(service.on(new PostCreatedEvent(UUID.randomUUID(), t(1), UUID.randomUUID(), alice, "alice", "",
                null, "REPOST", original, bob, "bob's post", List.of()))).hasSize(1);
        // undo + repost again = a new repost row, same original: no second notification
        assertThat(service.on(new PostCreatedEvent(UUID.randomUUID(), t(2), UUID.randomUUID(), alice, "alice", "",
                null, "REPOST", original, bob, "bob's post", List.of()))).isEmpty();

        UUID quote = UUID.randomUUID();
        var created = service.on(new PostCreatedEvent(UUID.randomUUID(), t(3), quote, alice, "alice", "@bob @carol look",
                null, "QUOTE", original, bob, "bob's post", List.of(bob, carol)));
        assertThat(created).extracting(NotificationView::recipientId, NotificationView::type).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(bob, NotificationType.QUOTE),
                org.assertj.core.groups.Tuple.tuple(carol, NotificationType.MENTION));
        assertThat(all(bob)).extracting(NotificationView::type, NotificationView::snippet).containsExactly(
                org.assertj.core.groups.Tuple.tuple(NotificationType.QUOTE, "@bob @carol look"),
                org.assertj.core.groups.Tuple.tuple(NotificationType.REPOST, "bob's post"));
    }

    @Test
    void eventsBeforeTheCutoffAreIgnored_noHistoricNotifications() {
        var cutoff = jdbc.queryForObject("SELECT since FROM notification_cutoff", java.sql.Timestamp.class).toInstant();
        assertThat(service.on(new UserFollowedEvent(UUID.randomUUID(), cutoff.minusSeconds(1), alice, bob))).isEmpty();
        assertThat(service.on(new UserUnfollowedEvent(UUID.randomUUID(), cutoff.minusSeconds(1), alice, bob))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
    }

    // ── Actor display data ─────────────────────────────────────────────────

    @Test
    void actorNameComesFromTheProfile_elseTheEvent_elsePlaceholder_andRefreshesLater() {
        UUID post = UUID.randomUUID();
        // no profile, no name in the event (a follow): placeholder
        assertThat(service.on(new UserFollowedEvent(UUID.randomUUID(), t(1), alice, bob)))
                .singleElement().satisfies(n -> {
                    assertThat(n.actorUsername()).isEqualTo(NotificationView.UNKNOWN_ACTOR);
                    assertThat(n.actorAvatarUrl()).isNull();
                });
        // no profile, name carried by the event (a comment)
        service.on(new CommentCreatedEvent(UUID.randomUUID(), t(2), UUID.randomUUID(), post, bob, null, null, carol,
                "carol-from-event", "c"));
        assertThat(all(bob)).extracting(NotificationView::actorUsername)
                .containsExactly("carol-from-event", NotificationView.UNKNOWN_ACTOR);

        // profiles arrive later (out of order: the update before the create)
        profiles.on(new UserUpdatedEvent(UUID.randomUUID(), t(10), alice, "alice2", "a2.png", null));
        profiles.on(new UserCreatedEvent(UUID.randomUUID(), t(5), alice, "alice", "a@x.io", "a.png"));
        profiles.on(new UserCreatedEvent(UUID.randomUUID(), t(5), carol, "carol", "c@x.io", null));
        profiles.on(new UserUpdatedEvent(UUID.randomUUID(), t(4), carol, "stale", "stale.png", null)); // older
        assertThat(all(bob)).extracting(NotificationView::actorUsername, NotificationView::actorAvatarUrl)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("carol", null),
                        org.assertj.core.groups.Tuple.tuple("alice2", "a2.png"));

        // redelivery of the same update is a no-op; malformed events are dropped, not retried
        profiles.on(new UserUpdatedEvent(UUID.randomUUID(), t(10), alice, "other", null, null));
        profiles.on(new UserCreatedEvent(UUID.randomUUID(), t(11), null, "nobody", "n@x.io", null));
        assertThat(all(bob).get(1).actorUsername()).isEqualTo("alice2");
    }

    // ── Read side ──────────────────────────────────────────────────────────

    @Test
    void pagesNewestFirst_tiesOnCreatedAtBrokenById_noDuplicatesOrSkips() {
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            // 7 distinct followers at the SAME instant, plus older ones
            expected.addAll(service.on(new UserFollowedEvent(UUID.randomUUID(), t(i < 5 ? 100 : i), UUID.randomUUID(),
                    bob)).stream().map(NotificationView::id).toList());
        }
        List<UUID> seen = new ArrayList<>();
        NotificationCursor cursor = null;
        for (int i = 0; i < 10; i++) {
            var page = service.page(bob, cursor, 2);
            page.items().forEach(n -> seen.add(n.id()));
            if (page.next() == null) break;
            cursor = NotificationCursor.decode(page.next().encode());
        }
        assertThat(seen).hasSize(7).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
        var ordered = all(bob);
        assertThat(ordered).extracting(NotificationView::id).containsExactlyElementsOf(seen);
        for (int i = 1; i < ordered.size(); i++) {
            var prev = ordered.get(i - 1);
            var cur = ordered.get(i);
            assertThat(prev.createdAt()).isAfterOrEqualTo(cur.createdAt());
            // PostgreSQL orders uuids bytewise, i.e. like their lower-case hex strings (not like UUID.compareTo)
            if (prev.createdAt().equals(cur.createdAt())) {
                assertThat(prev.id().toString().compareTo(cur.id().toString())).isPositive();
            }
        }
    }

    @Test
    void sizeIsClampedAndValidated() {
        assertThat(service.page(bob, null, 1000).items()).isEmpty();
        assertThatThrownBy(() -> service.page(bob, null, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recipientsAreIsolated_markReadIgnoresOtherUsersIds_emptyMeansAll_idempotent() {
        var forBob = service.on(new UserFollowedEvent(UUID.randomUUID(), t(1), alice, bob)).get(0);
        var forBob2 = service.on(new UserFollowedEvent(UUID.randomUUID(), t(2), carol, bob)).get(0);
        var forCarol = service.on(new UserFollowedEvent(UUID.randomUUID(), t(3), alice, carol)).get(0);

        assertThat(all(bob)).extracting(NotificationView::id).containsExactly(forBob2.id(), forBob.id());
        assertThat(all(carol)).extracting(NotificationView::id).containsExactly(forCarol.id());

        service.markRead(bob, List.of(forCarol.id(), forBob.id(), UUID.randomUUID()));
        assertThat(service.unreadCount(carol)).isEqualTo(1); // carol's untouched
        assertThat(service.unreadCount(bob)).isEqualTo(1);
        assertThat(all(bob)).extracting(NotificationView::read).containsExactly(false, true);

        service.markRead(bob, List.of());
        assertThat(service.unreadCount(bob)).isZero();
        service.markRead(bob, null); // idempotent
        assertThat(service.unreadCount(bob)).isZero();
        assertThat(service.unreadCount(carol)).isEqualTo(1);
    }
}
