package com.socialapp.feed.domain;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostContentRulesTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final String EMOJI = "😀"; // 1 code point, 2 UTF-16 units

    @Test
    void limitIsFiveHundred() {
        assertThat(Post.MAX_CONTENT_LENGTH).isEqualTo(500);
    }

    @Test
    void createAcceptsExactly500AndRejects501() {
        assertThat(Post.create(ALICE, "alice", "a".repeat(500), null).getContent()).hasSize(500);

        assertThatThrownBy(() -> Post.create(ALICE, "alice", "a".repeat(501), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Post content must be at most 500 characters (got 501)");
    }

    @Test
    void emojiCountsAsOneCharacter() {
        String fiveHundredEmoji = EMOJI.repeat(500); // 1000 UTF-16 units
        assertThat(Post.contentLength(fiveHundredEmoji)).isEqualTo(500);
        assertThat(Post.create(ALICE, "alice", fiveHundredEmoji, null).getContent()).isEqualTo(fiveHundredEmoji);

        assertThatThrownBy(() -> Post.create(ALICE, "alice", fiveHundredEmoji + "a", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500");
    }

    @Test
    void whitespaceAndNewlinesCount() {
        assertThatThrownBy(() -> Post.create(ALICE, "alice", "a" + "\n".repeat(500), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankOrNullContentIsRejected() {
        for (String blank : new String[] {null, "", "   ", "\n\t"}) {
            assertThatThrownBy(() -> Post.create(ALICE, "alice", blank, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Post content must not be blank");
        }
    }

    @Test
    void editEnforcesTheSameRules() {
        var post = Post.create(ALICE, "alice", "short", null);

        assertThatThrownBy(() -> post.edit(ALICE, "a".repeat(501), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("500");
        assertThatThrownBy(() -> post.edit(ALICE, " ", null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(post.getContent()).isEqualTo("short");

        post.edit(ALICE, "b".repeat(500), null);
        assertThat(post.getContent()).hasSize(500);
    }

    @Test
    void legacyOverLimitPostStaysReadableButAnEditMustShortenIt() {
        var post = Post.create(ALICE, "alice", "x", null);
        ReflectionTestUtils.setField(post, "content", "a".repeat(1500)); // stored before the limit existed

        assertThat(post.getContent()).hasSize(1500);
        assertThat(post.hashtags()).isEmpty();
        assertThatThrownBy(() -> post.edit(ALICE, post.getContent() + "!", null))
                .isInstanceOf(IllegalArgumentException.class);

        post.edit(ALICE, "now short", null);
        assertThat(post.getContent()).isEqualTo("now short");
    }

    @Test
    void ownershipIsCheckedBeforeContent() {
        var post = Post.create(ALICE, "alice", "mine", null);
        assertThatThrownBy(() -> post.edit(UUID.randomUUID(), "a".repeat(501), null))
                .isInstanceOf(com.socialapp.shared.domain.ForbiddenOperationException.class);
    }

    @Test
    void quoteCommentIsRequiredAndLimited_repostHasNoText() {
        var original = Post.create(ALICE, "alice", "#orig original text", null);

        assertThat(Post.repost(ALICE, "alice", original).getContent()).isEmpty();
        assertThat(Post.quote(ALICE, "alice", original, "c".repeat(500)).getContent()).hasSize(500);
        assertThat(Post.quote(ALICE, "alice", original, EMOJI.repeat(500)).getContent()).hasSize(1000);
        assertThatThrownBy(() -> Post.quote(ALICE, "alice", original, "c".repeat(501)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Quote comment must be at most 500 characters (got 501)");
        assertThatThrownBy(() -> Post.quote(ALICE, "alice", original, EMOJI.repeat(501)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("got 501");
        for (String blank : new String[] {null, "", "  ", "\n\t"}) {
            assertThatThrownBy(() -> Post.quote(ALICE, "alice", original, blank))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Quote comment must not be blank");
        }
    }

    @Test
    void hashtagsAndMentionCandidatesComeFromTheOwnTextOnly() {
        var original = Post.create(ALICE, "alice", "#orig hi @carol", null);
        var share = Post.quote(UUID.randomUUID(), "bob", original, "look #Mine @dave");

        assertThat(original.hashtags()).containsExactly("orig");
        assertThat(share.hashtags()).containsExactly("mine");
        assertThat(share.mentionCandidates()).containsExactly("dave");
        assertThat(Post.repost(UUID.randomUUID(), "bob", original).hashtags()).isEmpty();
        assertThat(Post.repost(UUID.randomUUID(), "bob", original).mentionCandidates()).isEmpty();
    }
}
