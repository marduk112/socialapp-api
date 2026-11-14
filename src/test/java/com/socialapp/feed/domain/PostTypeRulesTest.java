package com.socialapp.feed.domain;

import com.socialapp.shared.domain.ConflictException;
import com.socialapp.shared.domain.ForbiddenOperationException;
import com.socialapp.shared.events.PostCreatedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Stage 2C: POST / REPOST / QUOTE invariants of the aggregate. */
class PostTypeRulesTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private final Post original = Post.create(BOB, "bob", "#orig original @carol", "img.png");

    @SuppressWarnings("unchecked")
    private static PostCreatedEvent createdEvent(Post post) {
        var events = (Collection<Object>) ReflectionTestUtils.invokeMethod(post, "domainEvents");
        return (PostCreatedEvent) events.stream().filter(PostCreatedEvent.class::isInstance).findFirst().orElseThrow();
    }

    @Test
    void plainPostIsTypePostWithoutOriginal_targetIsItself() {
        assertThat(original.getType()).isEqualTo(PostType.POST);
        assertThat(original.getOriginalPostId()).isNull();
        assertThat(original.embedsOriginal()).isFalse();
        assertThat(original.repostTargetId()).contains(original.getId());
        assertThat(createdEvent(original).postType()).isEqualTo("POST");
        assertThat(createdEvent(original).originalPostId()).isNull();
    }

    @Test
    void repostHasNoTextNoImageAndPointsAtTheOriginal() {
        var repost = Post.repost(ALICE, "alice", original);

        assertThat(repost.getType()).isEqualTo(PostType.REPOST);
        assertThat(repost.getContent()).isEmpty();
        assertThat(repost.getImageUrl()).isNull();
        assertThat(repost.hashtags()).isEmpty();
        assertThat(repost.mentionCandidates()).isEmpty();
        assertThat(repost.getOriginalPostId()).isEqualTo(original.getId());
        assertThat(repost.getOriginalAuthorUsername()).isEqualTo("bob");
        assertThat(repost.getOriginalContent()).isEqualTo(original.getContent());
        var event = createdEvent(repost);
        assertThat(event.postType()).isEqualTo("REPOST");
        assertThat(event.originalPostId()).isEqualTo(original.getId());
        assertThat(event.content()).isEmpty();
    }

    @Test
    void quoteKeepsItsCommentAndParsesTagsFromItOnly() {
        var quote = Post.quote(ALICE, "alice", original, "my #Take on it @dave");

        assertThat(quote.getType()).isEqualTo(PostType.QUOTE);
        assertThat(quote.getContent()).isEqualTo("my #Take on it @dave");
        assertThat(quote.getImageUrl()).isNull();
        assertThat(quote.hashtags()).containsExactly("take");
        assertThat(quote.mentionCandidates()).containsExactly("dave");
        assertThat(quote.getOriginalPostId()).isEqualTo(original.getId());
        assertThat(createdEvent(quote).postType()).isEqualTo("QUOTE");
    }

    @Test
    void repostAndQuoteOfOwnPostAreAllowed() {
        var own = Post.create(ALICE, "alice", "mine", null);
        assertThat(Post.repost(ALICE, "alice", own).getOriginalPostId()).isEqualTo(own.getId());
        assertThat(Post.quote(ALICE, "alice", own, "me again").getOriginalPostId()).isEqualTo(own.getId());
    }

    @Test
    void flattening_aRepostItemTargetsItsOriginal_aQuoteTargetsItself() {
        var repost = Post.repost(ALICE, "alice", original);
        var quote = Post.quote(ALICE, "alice", original, "q");

        assertThat(repost.repostTargetId()).contains(original.getId());
        assertThat(quote.repostTargetId()).contains(quote.getId());
        // a quote of a quote is fine (targets the quote itself)
        assertThat(Post.quote(BOB, "bob", quote, "qq").getOriginalPostId()).isEqualTo(quote.getId());
        assertThat(Post.repost(BOB, "bob", quote).getOriginalPostId()).isEqualTo(quote.getId());
    }

    @Test
    void factoriesRejectARepostAsTarget_neverARepostOfARepost() {
        var repost = Post.repost(ALICE, "alice", original);

        assertThatThrownBy(() -> Post.repost(BOB, "bob", repost)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a repost");
        assertThatThrownBy(() -> Post.quote(BOB, "bob", repost, "x")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a repost");
    }

    @Test
    void quoteCommentBoundaries() {
        assertThat(Post.quote(ALICE, "alice", original, "x").getContent()).isEqualTo("x");
        assertThat(Post.quote(ALICE, "alice", original, "a".repeat(500)).getContent()).hasSize(500);
        assertThatThrownBy(() -> Post.quote(ALICE, "alice", original, "a".repeat(501)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most 500");
        assertThatThrownBy(() -> Post.quote(ALICE, "alice", original, " \n "))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Quote comment must not be blank");
    }

    @Test
    void repostCannotBeEdited_409_butOwnershipIsCheckedFirst() {
        var repost = Post.repost(ALICE, "alice", original);

        assertThatThrownBy(() -> repost.edit(BOB, "hijack", null)).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> repost.edit(ALICE, "now with text", null))
                .isInstanceOf(ConflictException.class).hasMessageContaining("repost cannot be edited");
        assertThat(repost.getContent()).isEmpty();
    }

    @Test
    void quoteCommentCanBeEditedWithinTheRules() {
        var quote = Post.quote(ALICE, "alice", original, "#first");

        assertThatThrownBy(() -> quote.edit(BOB, "#hijack", null)).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> quote.edit(ALICE, "  ", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> quote.edit(ALICE, "a".repeat(501), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("500");

        quote.edit(ALICE, "#second @erin", null);

        assertThat(quote.getContent()).isEqualTo("#second @erin");
        assertThat(quote.hashtags()).containsExactly("second");
        assertThat(quote.mentionCandidates()).containsExactly("erin");
        assertThat(quote.getType()).isEqualTo(PostType.QUOTE);
        assertThat(quote.getOriginalPostId()).isEqualTo(original.getId());
    }
}
