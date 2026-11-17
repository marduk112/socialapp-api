package com.socialapp.feed.domain;

import com.socialapp.shared.events.CommentCreatedEvent;
import com.socialapp.shared.events.PostCreatedEvent;
import com.socialapp.shared.events.ReactionEvent;
import com.socialapp.shared.events.ReactionEvent.ReactionAction;
import com.socialapp.shared.events.ReactionEvent.ReactionTarget;
import com.socialapp.shared.events.ReactionEvent.ReactionType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Stage 3A: what Feed's events carry so that Notification needs no lookup into Feed. */
public class NotificationEventEnrichmentTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    public static List<Object> events(Object aggregate) {
        return new ArrayList<>((Collection<Object>) ReflectionTestUtils.invokeMethod(aggregate, "domainEvents"));
    }

    public static <T> List<T> events(Object aggregate, Class<T> type) {
        return events(aggregate).stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    void plainPostEventHasNoOriginal_andAnnouncesResolvedMentions() {
        var post = Post.create(ALICE, "alice", "hi @bob @carol", null);
        post.announceMentions(List.of(new Mention(BOB, "bob"), new Mention(CAROL, "carol"), new Mention(BOB, "bob")));

        var event = events(post, PostCreatedEvent.class).get(0);
        assertThat(events(post)).hasSize(1); // replaced, not added
        assertThat(event.postType()).isEqualTo("POST");
        assertThat(event.originalAuthorId()).isNull();
        assertThat(event.originalContent()).isNull();
        assertThat(event.mentionedUserIds()).containsExactly(BOB, CAROL);
    }

    @Test
    void repostAndQuoteEventsCarryTheOriginalAuthorAndText() {
        var original = Post.create(BOB, "bob", "bob's words", null);

        var repost = events(Post.repost(ALICE, "alice", original), PostCreatedEvent.class).get(0);
        assertThat(repost.postType()).isEqualTo("REPOST");
        assertThat(repost.originalPostId()).isEqualTo(original.getId());
        assertThat(repost.originalAuthorId()).isEqualTo(BOB);
        assertThat(repost.originalContent()).isEqualTo("bob's words");
        assertThat(repost.mentionedUserIds()).isEmpty();

        var quote = events(Post.quote(ALICE, "alice", original, "so true"), PostCreatedEvent.class).get(0);
        assertThat(quote.postType()).isEqualTo("QUOTE");
        assertThat(quote.originalAuthorId()).isEqualTo(BOB);
        assertThat(quote.content()).isEqualTo("so true");
    }

    @Test
    void announcingMentionsOnAnEditedPostChangesNothing() {
        var post = Post.create(ALICE, "alice", "x", null);
        ReflectionTestUtils.invokeMethod(post, "clearDomainEvents"); // as after the first save
        post.announceMentions(List.of(new Mention(BOB, "bob")));
        assertThat(events(post)).isEmpty();
    }

    @Test
    void postReactionEventsCarryTargetOwnerAndSnippet() {
        var post = Post.create(BOB, "bob", "  liked\ntext ", null);
        post.react(ALICE, Set.of(), ReactionType.LIKE, true);
        post.react(ALICE, EnumSet.of(ReactionType.LIKE), ReactionType.DISLIKE, true);

        var reactions = events(post, ReactionEvent.class);
        assertThat(reactions).extracting(ReactionEvent::reactionType, ReactionEvent::action).containsExactly(
                org.assertj.core.groups.Tuple.tuple(ReactionType.LIKE, ReactionAction.ADDED),
                org.assertj.core.groups.Tuple.tuple(ReactionType.LIKE, ReactionAction.REMOVED),
                org.assertj.core.groups.Tuple.tuple(ReactionType.DISLIKE, ReactionAction.ADDED));
        assertThat(reactions).allSatisfy(r -> {
            assertThat(r.target()).isEqualTo(ReactionTarget.POST);
            assertThat(r.postId()).isEqualTo(post.getId());
            assertThat(r.commentId()).isNull();
            assertThat(r.targetOwnerId()).isEqualTo(BOB);
            assertThat(r.userId()).isEqualTo(ALICE);
            assertThat(r.targetSnippet()).isEqualTo("liked text");
        });
    }

    @Test
    void commentCreationRegistersCommentCreatedEvent_withPostAuthor() {
        var post = Post.create(BOB, "bob", "p", null);
        var comment = Comment.create(post, null, ALICE, "alice", "first!");

        var event = events(comment, CommentCreatedEvent.class).get(0);
        assertThat(event.commentId()).isEqualTo(comment.getId());
        assertThat(event.postId()).isEqualTo(post.getId());
        assertThat(event.postAuthorId()).isEqualTo(BOB);
        assertThat(event.parentCommentId()).isNull();
        assertThat(event.parentCommentAuthorId()).isNull();
        assertThat(event.authorId()).isEqualTo(ALICE);
        assertThat(event.authorUsername()).isEqualTo("alice");
        assertThat(event.contentSnippet()).isEqualTo("first!");
    }

    @Test
    void replyEventCarriesTheParentAndItsAuthor_parentOfAnotherPostIsRejected() {
        var post = Post.create(BOB, "bob", "p", null);
        var parent = Comment.create(post, null, CAROL, "carol", "top");
        var reply = Comment.create(post, parent, ALICE, "alice", "x".repeat(500));

        var event = events(reply, CommentCreatedEvent.class).get(0);
        assertThat(event.parentCommentId()).isEqualTo(parent.getId());
        assertThat(event.parentCommentAuthorId()).isEqualTo(CAROL);
        assertThat(event.contentSnippet()).hasSize(120).endsWith("…");

        var otherPost = Post.create(BOB, "bob", "other", null);
        assertThatThrownBy(() -> Comment.create(otherPost, parent, ALICE, "alice", "r"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not belong to post");
    }

    @Test
    void commentReactionsArePublishedWithTargetComment_noopPublishesNothing() {
        var post = Post.create(BOB, "bob", "p", null);
        var comment = Comment.create(post, null, CAROL, "carol", "nice comment");
        ReflectionTestUtils.invokeMethod(comment, "clearDomainEvents");

        comment.react(ALICE, Set.of(), ReactionType.LIKE, true);
        comment.react(ALICE, EnumSet.of(ReactionType.LIKE), ReactionType.LIKE, true); // no-op
        comment.react(ALICE, EnumSet.of(ReactionType.LIKE), ReactionType.LIKE, false);

        var reactions = events(comment, ReactionEvent.class);
        assertThat(reactions).extracting(ReactionEvent::action)
                .containsExactly(ReactionAction.ADDED, ReactionAction.REMOVED);
        assertThat(reactions).allSatisfy(r -> {
            assertThat(r.target()).isEqualTo(ReactionTarget.COMMENT);
            assertThat(r.postId()).isEqualTo(post.getId());
            assertThat(r.commentId()).isEqualTo(comment.getId());
            assertThat(r.targetOwnerId()).isEqualTo(CAROL);
            assertThat(r.targetSnippet()).isEqualTo("nice comment");
        });
    }
}
