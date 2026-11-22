package com.socialapp.notification.domain;

import com.socialapp.notification.domain.NotificationPolicy.CommentFact;
import com.socialapp.notification.domain.NotificationPolicy.PostFact;
import com.socialapp.notification.domain.NotificationPolicy.ReactionFact;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPolicyTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();
    private static final UUID POST = UUID.randomUUID();
    private static final UUID ORIGINAL = UUID.randomUUID();
    private static final UUID COMMENT = UUID.randomUUID();
    private static final UUID PARENT = UUID.randomUUID();
    private static final Instant T = Instant.parse("2026-11-15T10:00:00Z");

    // ── FOLLOW ──────────────────────────────────────────────────────────────

    @Test
    void followNotifiesTheFollowee_unfollowRetracts_selfFollowNothing() {
        var follow = NotificationPolicy.follow(ALICE, BOB, T, true);
        assertThat(follow).singleElement().satisfies(i -> {
            assertThat(i.recipientId()).isEqualTo(BOB);
            assertThat(i.type()).isEqualTo(NotificationType.FOLLOW);
            assertThat(i.actorId()).isEqualTo(ALICE);
            assertThat(i.targetId()).isEqualTo(BOB);
            assertThat(i.postId()).isNull();
            assertThat(i.snippet()).isNull();
            assertThat(i.retraction()).isFalse();
        });
        assertThat(NotificationPolicy.follow(ALICE, BOB, T, false)).singleElement()
                .satisfies(i -> assertThat(i.retraction()).isTrue());
        assertThat(NotificationPolicy.follow(ALICE, ALICE, T, true)).isEmpty();
        assertThat(NotificationPolicy.follow(null, BOB, T, true)).isEmpty();
        assertThat(NotificationPolicy.follow(ALICE, BOB, null, true)).isEmpty();
    }

    // ── LIKE ────────────────────────────────────────────────────────────────

    @Test
    void likeOfAPostNotifiesItsAuthor_targetIsThePost() {
        var intents = NotificationPolicy.reaction(new ReactionFact(true, true, POST, null, BOB, ALICE, "my post", T));
        assertThat(intents).singleElement().satisfies(i -> {
            assertThat(i.recipientId()).isEqualTo(BOB);
            assertThat(i.type()).isEqualTo(NotificationType.LIKE);
            assertThat(i.targetId()).isEqualTo(POST);
            assertThat(i.postId()).isEqualTo(POST);
            assertThat(i.commentId()).isNull();
            assertThat(i.snippet()).isEqualTo("my post");
        });
    }

    @Test
    void likeOfACommentNotifiesTheCommentAuthor_opensThePost() {
        var intents = NotificationPolicy.reaction(new ReactionFact(true, true, POST, COMMENT, CAROL, ALICE, "c", T));
        assertThat(intents).singleElement().satisfies(i -> {
            assertThat(i.recipientId()).isEqualTo(CAROL);
            assertThat(i.targetId()).isEqualTo(COMMENT);
            assertThat(i.postId()).isEqualTo(POST);
            assertThat(i.commentId()).isEqualTo(COMMENT);
        });
    }

    @Test
    void unlikeRetracts_dislikesNeverNotify_ownLikeNothing_unknownOwnerNothing() {
        assertThat(NotificationPolicy.reaction(new ReactionFact(true, false, POST, null, BOB, ALICE, null, T)))
                .singleElement().satisfies(i -> assertThat(i.retraction()).isTrue());
        assertThat(NotificationPolicy.reaction(new ReactionFact(false, true, POST, null, BOB, ALICE, null, T))).isEmpty();
        assertThat(NotificationPolicy.reaction(new ReactionFact(false, false, POST, null, BOB, ALICE, null, T))).isEmpty();
        assertThat(NotificationPolicy.reaction(new ReactionFact(true, true, POST, null, ALICE, ALICE, null, T))).isEmpty();
        // a pre-3A event has no owner: nobody to notify
        assertThat(NotificationPolicy.reaction(new ReactionFact(true, true, POST, null, null, ALICE, null, T))).isEmpty();
    }

    // ── COMMENT / REPLY ─────────────────────────────────────────────────────

    private static CommentFact comment(UUID postAuthor, UUID parent, UUID parentAuthor, UUID author) {
        return new CommentFact(COMMENT, POST, postAuthor, parent, parentAuthor, author, "author", "text", T);
    }

    @Test
    void commentNotifiesThePostAuthor() {
        assertThat(NotificationPolicy.commentCreated(comment(BOB, null, null, ALICE))).singleElement().satisfies(i -> {
            assertThat(i.recipientId()).isEqualTo(BOB);
            assertThat(i.type()).isEqualTo(NotificationType.COMMENT);
            assertThat(i.targetId()).isEqualTo(COMMENT);
            assertThat(i.postId()).isEqualTo(POST);
            assertThat(i.commentId()).isEqualTo(COMMENT);
            assertThat(i.snippet()).isEqualTo("text");
            assertThat(i.actorUsername()).isEqualTo("author");
        });
        assertThat(NotificationPolicy.commentCreated(comment(ALICE, null, null, ALICE))).isEmpty(); // own post
    }

    @Test
    void replyToYourCommentOnYourOwnPostIsOnlyAReply() {
        assertThat(NotificationPolicy.commentCreated(comment(BOB, PARENT, BOB, ALICE)))
                .extracting(NotificationIntent::recipientId, NotificationIntent::type)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(BOB, NotificationType.REPLY));
    }

    @Test
    void replyToSomeoneElsesCommentNotifiesTheCommenterAndThePostAuthor() {
        assertThat(NotificationPolicy.commentCreated(comment(BOB, PARENT, CAROL, ALICE)))
                .extracting(NotificationIntent::recipientId, NotificationIntent::type)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(CAROL, NotificationType.REPLY),
                        org.assertj.core.groups.Tuple.tuple(BOB, NotificationType.COMMENT));
    }

    @Test
    void replyToYourOwnCommentNotifiesOnlyThePostAuthor_ownThreadNobody() {
        assertThat(NotificationPolicy.commentCreated(comment(BOB, PARENT, ALICE, ALICE)))
                .extracting(NotificationIntent::type).containsExactly(NotificationType.COMMENT);
        assertThat(NotificationPolicy.commentCreated(comment(ALICE, PARENT, ALICE, ALICE))).isEmpty();
    }

    // ── POSTS: MENTION / REPOST / QUOTE ─────────────────────────────────────

    private static PostFact post(String type, UUID originalAuthor, List<UUID> mentioned) {
        return new PostFact(POST, ALICE, "alice", type, "hello @bob", type.equals("POST") ? null : ORIGINAL,
                originalAuthor, type.equals("POST") ? null : "the original", mentioned, T);
    }

    @Test
    void mentionNotifiesEachMentionedUserOnce_neverTheAuthor() {
        var intents = NotificationPolicy.postCreated(post("POST", null, List.of(BOB, CAROL, BOB, ALICE)));
        assertThat(intents).extracting(NotificationIntent::recipientId).containsExactly(BOB, CAROL);
        assertThat(intents).allSatisfy(i -> {
            assertThat(i.type()).isEqualTo(NotificationType.MENTION);
            assertThat(i.targetId()).isEqualTo(POST);
            assertThat(i.postId()).isEqualTo(POST);
            assertThat(i.snippet()).isEqualTo("hello @bob");
        });
        assertThat(NotificationPolicy.postCreated(post("POST", null, null))).isEmpty(); // pre-3A event
    }

    @Test
    void repostNotifiesTheOriginalAuthor_targetIsTheOriginal_snippetIsTheOriginalText() {
        assertThat(NotificationPolicy.postCreated(post("REPOST", BOB, List.of()))).singleElement().satisfies(i -> {
            assertThat(i.recipientId()).isEqualTo(BOB);
            assertThat(i.type()).isEqualTo(NotificationType.REPOST);
            assertThat(i.targetId()).isEqualTo(ORIGINAL);
            assertThat(i.postId()).isEqualTo(ORIGINAL);
            assertThat(i.snippet()).isEqualTo("the original");
        });
        assertThat(NotificationPolicy.postCreated(post("REPOST", ALICE, List.of()))).isEmpty(); // own post
    }

    @Test
    void quoteThatMentionsTheQuotedAuthorIsOnlyAQuote_otherMentionsStay() {
        var intents = NotificationPolicy.postCreated(post("QUOTE", BOB, List.of(BOB, CAROL)));
        assertThat(intents).extracting(NotificationIntent::recipientId, NotificationIntent::type).containsExactly(
                org.assertj.core.groups.Tuple.tuple(BOB, NotificationType.QUOTE),
                org.assertj.core.groups.Tuple.tuple(CAROL, NotificationType.MENTION));
        var quote = intents.get(0);
        assertThat(quote.targetId()).isEqualTo(POST); // each quote is its own notification
        assertThat(quote.postId()).isEqualTo(POST);
        assertThat(quote.snippet()).isEqualTo("hello @bob"); // the quote's comment
    }

    @Test
    void snippetsAreShortenedAndBlankBecomesNull() {
        var longText = "x".repeat(300);
        var intents = NotificationPolicy.postCreated(new PostFact(POST, ALICE, " ", "POST", longText, null, null, null,
                List.of(BOB), T));
        assertThat(intents.get(0).snippet().codePointCount(0, intents.get(0).snippet().length())).isEqualTo(120);
        assertThat(intents.get(0).actorUsername()).isNull();
        var blank = NotificationPolicy.commentCreated(new CommentFact(COMMENT, POST, BOB, null, null, ALICE, "a",
                "  \n ", T));
        assertThat(blank.get(0).snippet()).isNull();
    }

    // ── Blocks / mutes (stage 5A) ───────────────────────────────────────────

    @Test
    void suppressedPairsGetNoNotification_butRetractionsAlwaysPass() {
        var comment = NotificationPolicy.commentCreated(new CommentFact(COMMENT, POST, BOB, PARENT, CAROL, ALICE,
                "alice", "hi", T)); // REPLY to carol + COMMENT to bob
        java.util.function.BiPredicate<UUID, UUID> bobSuppressesAlice = (recipient, actor) ->
                recipient.equals(BOB) && actor.equals(ALICE);

        assertThat(NotificationPolicy.withoutSuppressed(comment, bobSuppressesAlice))
                .extracting(NotificationIntent::recipientId).containsExactly(CAROL);

        var unlike = NotificationPolicy.reaction(new ReactionFact(true, false, POST, null, BOB, ALICE, "p", T));
        assertThat(NotificationPolicy.withoutSuppressed(unlike, bobSuppressesAlice)).isEqualTo(unlike);
        var like = NotificationPolicy.reaction(new ReactionFact(true, true, POST, null, BOB, ALICE, "p", T));
        assertThat(NotificationPolicy.withoutSuppressed(like, bobSuppressesAlice)).isEmpty();
        assertThat(NotificationPolicy.withoutSuppressed(like, (r, a) -> false)).isEqualTo(like);
    }
}
