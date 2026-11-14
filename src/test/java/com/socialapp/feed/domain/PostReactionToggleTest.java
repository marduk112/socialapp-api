package com.socialapp.feed.domain;

import com.socialapp.shared.events.ReactionEvent.ReactionType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PostReactionToggleTest {

    private static final UUID USER = UUID.randomUUID();

    @Test
    void likeIsIdempotent() {
        var post = Post.create(UUID.randomUUID(), "author", "text", null);

        var first = post.react(USER, Set.of(), ReactionType.LIKE, true);
        var second = post.react(USER, Set.of(ReactionType.LIKE), ReactionType.LIKE, true);

        assertThat(first.added()).isEqualTo(ReactionType.LIKE);
        assertThat(second.isNoop()).isTrue();
        assertThat(post.getLikesCount()).isEqualTo(1);
    }

    @Test
    void dislikeReplacesLike() {
        var post = Post.create(UUID.randomUUID(), "author", "text", null);
        post.react(USER, Set.of(), ReactionType.LIKE, true);

        var change = post.react(USER, Set.of(ReactionType.LIKE), ReactionType.DISLIKE, true);

        assertThat(change.removed()).containsExactly(ReactionType.LIKE);
        assertThat(change.added()).isEqualTo(ReactionType.DISLIKE);
        assertThat(post.getLikesCount()).isZero();
        assertThat(post.getDislikesCount()).isEqualTo(1);
    }

    @Test
    void removingAbsentReactionIsNoop() {
        var post = Post.create(UUID.randomUUID(), "author", "text", null);

        var change = post.react(USER, Set.of(), ReactionType.LIKE, false);

        assertThat(change.isNoop()).isTrue();
        assertThat(post.getLikesCount()).isZero();
    }

    @Test
    void commentToggleFollowsSameRules() {
        var comment = Comment.create(UUID.randomUUID(), UUID.randomUUID(), "author", "c", null);

        comment.react(Set.of(), ReactionType.DISLIKE, true);
        comment.react(EnumSet.of(ReactionType.DISLIKE), ReactionType.DISLIKE, true);
        assertThat(comment.getDislikesCount()).isEqualTo(1);

        comment.react(EnumSet.of(ReactionType.DISLIKE), ReactionType.DISLIKE, false);
        assertThat(comment.getDislikesCount()).isZero();
    }
}
