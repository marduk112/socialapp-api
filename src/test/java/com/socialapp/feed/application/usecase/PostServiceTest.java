package com.socialapp.feed.application.usecase;

import com.socialapp.feed.domain.*;
import com.socialapp.shared.domain.NotFoundException;
import com.socialapp.shared.events.ReactionEvent.ReactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PostServiceTest {

    private static final UUID ALICE = UUID.randomUUID();

    private final PostRepository posts = mock(PostRepository.class);
    private final PostReactionRepository reactions = mock(PostReactionRepository.class);
    private final CommentRepository comments = mock(CommentRepository.class);
    private final CommentReactionRepository commentReactions = mock(CommentReactionRepository.class);
    private final AuthorProfileRepository profiles = mock(AuthorProfileRepository.class);
    private final PostHashtagRepository hashtags = mock(PostHashtagRepository.class);
    private final PostMentionRepository mentions = mock(PostMentionRepository.class);
    private final BookmarkRepository bookmarks = mock(BookmarkRepository.class);
    private final PostService service = new PostService(posts, reactions, comments, commentReactions, profiles,
            hashtags, mentions, bookmarks);

    @BeforeEach
    void saveReturnsArgument() {
        when(posts.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));
        when(comments.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── Replies (item 10) ────────────────────────────────────────────────────

    @Test
    void replyToCommentOfAnotherPostIsRejected() {
        var post = Post.create(ALICE, "alice", "p1", null);
        var otherPostsComment = Comment.create(Post.create(ALICE, "alice", "other", null), null, ALICE, "alice", "c");
        when(posts.findByIdForUpdate(post.getId())).thenReturn(Optional.of(post));
        when(comments.findByIdForUpdate(otherPostsComment.getId())).thenReturn(Optional.of(otherPostsComment));

        assertThatThrownBy(() -> service.addComment(post.getId(), ALICE, "alice", "reply", otherPostsComment.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to post");
        verify(comments, never()).save(any());
    }

    @Test
    void replyToMissingCommentIsNotFound() {
        var post = Post.create(ALICE, "alice", "p1", null);
        when(posts.findByIdForUpdate(post.getId())).thenReturn(Optional.of(post));
        when(comments.findByIdForUpdate(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addComment(post.getId(), ALICE, "alice", "reply", UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
        verify(comments, never()).save(any());
    }

    @Test
    void replyToCommentOfSamePostIsAcceptedAndDoesNotCountAsTopLevel() {
        var post = Post.create(ALICE, "alice", "p1", null);
        var parent = Comment.create(post, null, ALICE, "alice", "c");
        when(posts.findByIdForUpdate(post.getId())).thenReturn(Optional.of(post));
        when(comments.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));

        var reply = service.addComment(post.getId(), ALICE, "alice", "reply", parent.getId());

        assertThat(reply.getParentCommentId()).isEqualTo(parent.getId());
        assertThat(post.getCommentsCount()).isZero();
    }

    @Test
    void commentOnMissingPostIsNotFound() {
        when(posts.findByIdForUpdate(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addComment(UUID.randomUUID(), ALICE, "alice", "c", null))
                .isInstanceOf(NotFoundException.class);
    }

    // ── Reactions (item 5) ───────────────────────────────────────────────────

    @Test
    void duplicateLikeIsIdempotent() {
        var post = Post.create(UUID.randomUUID(), "bob", "p", null);
        post.react(ALICE, Set.of(), ReactionType.LIKE, true);
        when(posts.findByIdForUpdate(post.getId())).thenReturn(Optional.of(post));
        when(reactions.findByPostIdAndUserId(post.getId(), ALICE))
                .thenReturn(List.of(PostReaction.of(post.getId(), ALICE, ReactionType.LIKE)));

        var result = service.react(post.getId(), ALICE, ReactionType.LIKE, true);

        assertThat(result.getLikesCount()).isEqualTo(1);
        verify(reactions, never()).save(any());
        verify(reactions, never()).deleteAll(any());
    }

    @Test
    void reactionsLockThePostRow() {
        var post = Post.create(UUID.randomUUID(), "bob", "p", null);
        when(posts.findByIdForUpdate(post.getId())).thenReturn(Optional.of(post));

        service.react(post.getId(), ALICE, ReactionType.DISLIKE, true);

        verify(posts).findByIdForUpdate(post.getId());
        verify(posts, never()).findById(any());
        assertThat(post.getDislikesCount()).isEqualTo(1);
    }

    @Test
    void reactionOnMissingPostIsNotFound() {
        when(posts.findByIdForUpdate(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.react(UUID.randomUUID(), ALICE, ReactionType.LIKE, true))
                .isInstanceOf(NotFoundException.class);
    }

    // ── Username projection (item 7) ─────────────────────────────────────────

    @Test
    void newPostUsesProjectedUsernameOverStaleTokenClaim() {
        when(profiles.findById(ALICE)).thenReturn(Optional.of(new AuthorProfile(ALICE, "alice-renamed", null, null)));

        var post = service.createPost(ALICE, "alice-old-token", "hi", null);

        assertThat(post.getAuthorUsername()).isEqualTo("alice-renamed");
    }

    @Test
    void newPostFallsBackToTokenUsernameWithoutProjection() {
        when(profiles.findById(ALICE)).thenReturn(Optional.empty());

        assertThat(service.createPost(ALICE, "alice", "hi", null).getAuthorUsername()).isEqualTo("alice");
    }

    // ── Hashtags and mentions (stage 2B) ─────────────────────────────────────

    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();

    @Test
    void createIndexesHashtagsAndResolvedMentions_afterFlushingThePost() {
        when(profiles.findByLowerUsernameIn(any())).thenReturn(List.of(
                new AuthorProfile(BOB, "Bob", null, null), new AuthorProfile(ALICE, "alice", null, null)));

        var post = service.createPost(ALICE, "alice", "#Hello #hello @BOB @ghost @alice #World", null);

        var order = inOrder(posts, hashtags, mentions);
        order.verify(posts).save(any(Post.class));
        order.verify(posts).flush();
        order.verify(hashtags).replace(post.getId(), List.of("hello", "world"));
        // case-insensitive, canonical username; unknown @ghost and self-mention @alice dropped
        order.verify(mentions).replace(post.getId(), List.of(new Mention(BOB, "Bob")));
        verify(profiles).findByLowerUsernameIn(Set.of("bob", "ghost", "alice")); // one batched lookup
    }

    @Test
    void newPostAnnouncesItsResolvedMentionsInThePostCreatedEvent_beforeSave() {
        when(profiles.findByLowerUsernameIn(any())).thenReturn(List.of(
                new AuthorProfile(BOB, "bob", null, null), new AuthorProfile(ALICE, "alice", null, null)));
        when(posts.save(any(Post.class))).thenAnswer(inv -> {
            // what Spring Data publishes on save(): the event already carries the mentions
            var event = com.socialapp.feed.domain.NotificationEventEnrichmentTest.events(inv.getArgument(0),
                    com.socialapp.shared.events.PostCreatedEvent.class).get(0);
            assertThat(event.mentionedUserIds()).containsExactly(BOB); // self-mention dropped
            return inv.getArgument(0);
        });

        service.createPost(ALICE, "alice", "hi @bob and @alice and @ghost", null);

        verify(posts).save(any(Post.class));
    }

    @Test
    void quoteEventCarriesTheQuotedAuthor_andMentionsOfTheComment() {
        var original = Post.create(BOB, "bob", "original", null);
        when(posts.findById(original.getId())).thenReturn(Optional.of(original));
        when(posts.findByIdForUpdate(original.getId())).thenReturn(Optional.of(original));
        when(profiles.findByLowerUsernameIn(any())).thenReturn(List.of(new AuthorProfile(CAROL, "carol", null, null)));

        var quote = service.quote(original.getId(), ALICE, "alice", "cc @carol");

        var event = com.socialapp.feed.domain.NotificationEventEnrichmentTest.events(quote,
                com.socialapp.shared.events.PostCreatedEvent.class).get(0);
        assertThat(event.originalAuthorId()).isEqualTo(BOB);
        assertThat(event.mentionedUserIds()).containsExactly(CAROL);
    }

    @Test
    void addCommentRegistersCommentCreatedEventWithPostAndParentAuthors() {
        var post = Post.create(BOB, "bob", "p", null);
        var parent = Comment.create(post, null, CAROL, "carol", "c");
        when(posts.findByIdForUpdate(post.getId())).thenReturn(Optional.of(post));
        when(comments.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));

        var reply = service.addComment(post.getId(), ALICE, "alice", "a reply", parent.getId());

        var event = com.socialapp.feed.domain.NotificationEventEnrichmentTest.events(reply,
                com.socialapp.shared.events.CommentCreatedEvent.class).get(0);
        assertThat(event.postAuthorId()).isEqualTo(BOB);
        assertThat(event.parentCommentAuthorId()).isEqualTo(CAROL);
        assertThat(event.contentSnippet()).isEqualTo("a reply");
    }

    @Test
    void noMentionCandidatesMeansNoProfileLookup() {
        var post = service.createPost(ALICE, "alice", "plain text, mail me at a@b.com", null);

        verify(profiles, never()).findByLowerUsernameIn(any());
        verify(hashtags).replace(post.getId(), List.of());
        verify(mentions).replace(post.getId(), List.of());
    }

    @Test
    void profileReturnedByTheDatabaseButNotMatchingInJavaIsIgnored() {
        // DB lower() and Java lower-casing could disagree for exotic characters: Java's check is authoritative.
        when(profiles.findByLowerUsernameIn(any())).thenReturn(List.of(new AuthorProfile(BOB, "bobby", null, null),
                new AuthorProfile(CAROL, null, null, null)));

        var post = service.createPost(ALICE, "alice", "hi @bob", null);

        verify(mentions).replace(post.getId(), List.of());
    }

    @Test
    void editReplacesTheIndexedSets_andLocksThePost() {
        var post = Post.create(ALICE, "alice", "#old @carol", null);
        when(posts.findByIdForUpdate(post.getId())).thenReturn(Optional.of(post));
        when(profiles.findByLowerUsernameIn(any())).thenReturn(List.of(new AuthorProfile(BOB, "bob", null, null)));

        service.updatePost(post.getId(), ALICE, "#New text @bob", null);

        verify(hashtags).replace(post.getId(), List.of("new"));
        verify(mentions).replace(post.getId(), List.of(new Mention(BOB, "bob")));
        verify(posts, never()).findById(any());
    }

    @Test
    void rejectedEditTouchesNothing() {
        var post = Post.create(ALICE, "alice", "#old", null);
        when(posts.findByIdForUpdate(post.getId())).thenReturn(Optional.of(post));

        assertThatThrownBy(() -> service.updatePost(post.getId(), ALICE, "x".repeat(501), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("500");
        assertThatThrownBy(() -> service.updatePost(post.getId(), BOB, "#hijack", null))
                .isInstanceOf(com.socialapp.shared.domain.ForbiddenOperationException.class);

        verify(posts, never()).save(any());
        verifyNoInteractions(hashtags, mentions);
    }

    @Test
    void shareIndexesOnlyItsOwnComment() {
        var original = Post.create(BOB, "bob", "#original @carol", null);
        when(posts.findById(original.getId())).thenReturn(Optional.of(original));
        when(posts.findByIdForUpdate(original.getId())).thenReturn(Optional.of(original));

        var share = service.sharePost(original.getId(), ALICE, "alice", "so #True").post();

        verify(hashtags).replace(share.getId(), List.of("true"));
        verify(mentions).replace(share.getId(), List.of());
        verify(profiles, never()).findByLowerUsernameIn(any());
    }

    @Test
    void viewsBatchLoadAvatarsTagsAndMentionsWithOneQueryEach() {
        var p1 = Post.create(ALICE, "alice", "#a", null);
        var p2 = Post.create(BOB, "bob", "@alice", null);
        var p3 = Post.create(ALICE, "alice", "none", null);
        when(hashtags.findByPostIds(any())).thenReturn(java.util.Map.of(p1.getId(), List.of("a")));
        when(mentions.findByPostIds(any())).thenReturn(java.util.Map.of(p2.getId(), List.of(new Mention(ALICE, "alice"))));
        when(profiles.findAllById(any())).thenReturn(List.of(new AuthorProfile(ALICE, "alice", "a.png", null)));

        var views = service.views(List.of(p1, p2, p3), ALICE);

        assertThat(views).extracting(PostView::post).containsExactly(p1, p2, p3);
        assertThat(views.get(0).hashtags()).containsExactly("a");
        assertThat(views.get(0).authorAvatarUrl()).isEqualTo("a.png");
        assertThat(views.get(1).mentions()).containsExactly(new Mention(ALICE, "alice"));
        assertThat(views.get(2).hashtags()).isEmpty();
        assertThat(views.get(2).mentions()).isEmpty();
        verify(hashtags, times(1)).findByPostIds(List.of(p1.getId(), p2.getId(), p3.getId()));
        verify(mentions, times(1)).findByPostIds(List.of(p1.getId(), p2.getId(), p3.getId()));
        verify(profiles, times(1)).findAllById(any());
    }

    @Test
    void viewsOfNoPostsRunNoQueries() {
        assertThat(service.views(List.of(), ALICE)).isEmpty();
        verifyNoInteractions(hashtags, mentions, profiles);
    }

    // ── Reposts and quotes (stage 2C) ────────────────────────────────────────

    private void exists(Post... stored) {
        for (Post p : stored) {
            when(posts.findById(p.getId())).thenReturn(Optional.of(p));
            when(posts.findByIdForUpdate(p.getId())).thenReturn(Optional.of(p));
        }
    }

    @Test
    void repostCreatesARepostOfTheLockedOriginal() {
        var original = Post.create(BOB, "bob", "orig", null);
        exists(original);

        var result = service.repost(original.getId(), ALICE, "alice");

        assertThat(result.created()).isTrue();
        assertThat(result.post().getType()).isEqualTo(PostType.REPOST);
        assertThat(result.post().getOriginalPostId()).isEqualTo(original.getId());
        verify(posts).findByIdForUpdate(original.getId());
        verify(posts).save(result.post());
    }

    @Test
    void secondRepostReturnsTheExistingOne_noSave() {
        var original = Post.create(BOB, "bob", "orig", null);
        var existing = Post.repost(ALICE, "alice", original);
        exists(original);
        when(posts.findRepost(ALICE, original.getId())).thenReturn(Optional.of(existing));

        var result = service.repost(original.getId(), ALICE, "alice");

        assertThat(result.created()).isFalse();
        assertThat(result.post()).isSameAs(existing);
        verify(posts, never()).save(any());
        verifyNoInteractions(hashtags, mentions);
    }

    @Test
    void repostingARepostItemTargetsItsOriginal_lockingOnlyTheOriginal() {
        var original = Post.create(BOB, "bob", "orig", null);
        var carolsRepost = Post.repost(CAROL, "carol", original);
        exists(original);
        when(posts.findById(carolsRepost.getId())).thenReturn(Optional.of(carolsRepost));

        var repost = service.repost(carolsRepost.getId(), ALICE, "alice").post();
        var quote = service.quote(carolsRepost.getId(), ALICE, "alice", "q");

        assertThat(repost.getOriginalPostId()).isEqualTo(original.getId());
        assertThat(quote.getOriginalPostId()).isEqualTo(original.getId());
        verify(posts, never()).findByIdForUpdate(carolsRepost.getId());
        verify(posts, never()).findRepost(ALICE, carolsRepost.getId());
    }

    @Test
    void quotingAQuoteTargetsTheQuoteItself() {
        var original = Post.create(BOB, "bob", "orig", null);
        var carolsQuote = Post.quote(CAROL, "carol", original, "c");
        exists(original, carolsQuote);

        assertThat(service.quote(carolsQuote.getId(), ALICE, "alice", "qq").getOriginalPostId())
                .isEqualTo(carolsQuote.getId());
        assertThat(service.repost(carolsQuote.getId(), ALICE, "alice").post().getOriginalPostId())
                .isEqualTo(carolsQuote.getId());
    }

    @Test
    void unknownOrConcurrentlyDeletedTargetIsNotFound() {
        var original = Post.create(BOB, "bob", "orig", null);
        when(posts.findById(original.getId())).thenReturn(Optional.of(original));
        when(posts.findByIdForUpdate(original.getId())).thenReturn(Optional.empty()); // deleted before the lock

        assertThatThrownBy(() -> service.repost(UUID.randomUUID(), ALICE, "alice")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.quote(UUID.randomUUID(), ALICE, "alice", "x")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.repost(original.getId(), ALICE, "alice")).isInstanceOf(NotFoundException.class);
        verify(posts, never()).save(any());
    }

    @Test
    void blankOrTooLongQuoteIsRejectedAndNothingIsWritten() {
        var original = Post.create(BOB, "bob", "orig", null);
        exists(original);

        for (String bad : new String[] {null, "", "   ", "x".repeat(501)}) {
            assertThatThrownBy(() -> service.quote(original.getId(), ALICE, "alice", bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verify(posts, never()).save(any());
        verifyNoInteractions(hashtags, mentions);
    }

    @Test
    void quoteIndexesItsCommentLikeAPost() {
        var original = Post.create(BOB, "bob", "#orig", null);
        exists(original);
        when(profiles.findByLowerUsernameIn(any())).thenReturn(List.of(new AuthorProfile(BOB, "bob", null, null)));

        var quote = service.quote(original.getId(), ALICE, "alice", "#Mine @bob");

        verify(hashtags).replace(quote.getId(), List.of("mine"));
        verify(mentions).replace(quote.getId(), List.of(new Mention(BOB, "bob")));
    }

    @Test
    void undoDeletesTheCallersRepostOfTheResolvedOriginal() {
        var original = Post.create(BOB, "bob", "orig", null);
        var carolsRepost = Post.repost(CAROL, "carol", original);
        exists(original);
        when(posts.findById(carolsRepost.getId())).thenReturn(Optional.of(carolsRepost));

        service.undoRepost(original.getId(), ALICE);
        service.undoRepost(carolsRepost.getId(), ALICE);

        verify(posts, times(2)).deleteRepost(ALICE, original.getId());
        verify(posts, times(2)).findByIdForUpdate(original.getId());
    }

    @Test
    void undoOfUnknownPostIsANoOp() {
        service.undoRepost(UUID.randomUUID(), ALICE);
        verify(posts, never()).deleteRepost(any(), any());
    }

    @Test
    void deletingAnOriginalDeletesItsRepostsAndDetachesItsQuotes_underTheRowLock() {
        var original = Post.create(ALICE, "alice", "orig", null);
        exists(original);

        service.deletePost(original.getId(), ALICE);

        var order = inOrder(posts);
        order.verify(posts).findByIdForUpdate(original.getId());
        order.verify(posts).deleteRepostsOf(original.getId());
        order.verify(posts).detachQuotesOf(original.getId());
        order.verify(posts).delete(original);
    }

    @Test
    void deletingARepostDeletesOnlyIt_andOthersCannot() {
        var original = Post.create(BOB, "bob", "orig", null);
        var repost = Post.repost(ALICE, "alice", original);
        exists(repost);

        assertThatThrownBy(() -> service.deletePost(repost.getId(), BOB))
                .isInstanceOf(com.socialapp.shared.domain.ForbiddenOperationException.class);
        service.deletePost(repost.getId(), ALICE);

        verify(posts).delete(repost);
        verify(posts, never()).deleteRepostsOf(any());
        verify(posts, never()).detachQuotesOf(any());
    }

    @Test
    void editingARepostIsAConflict_quoteEditReindexes() {
        var original = Post.create(BOB, "bob", "orig", null);
        var repost = Post.repost(ALICE, "alice", original);
        var quote = Post.quote(ALICE, "alice", original, "#old");
        exists(repost, quote);

        assertThatThrownBy(() -> service.updatePost(repost.getId(), ALICE, "text", null))
                .isInstanceOf(com.socialapp.shared.domain.ConflictException.class);
        service.updatePost(quote.getId(), ALICE, "#new", null);

        verify(hashtags).replace(quote.getId(), List.of("new"));
        verify(hashtags, never()).replace(eq(repost.getId()), any());
    }

    @Test
    void deprecatedShareAlias_blankMeansRepost_textMeansQuote() {
        var original = Post.create(BOB, "bob", "orig", null);
        exists(original);

        var bare = service.sharePost(original.getId(), ALICE, "alice", "  ");
        var withText = service.sharePost(original.getId(), ALICE, "alice", "look");

        assertThat(bare.created()).isTrue();
        assertThat(bare.post().getType()).isEqualTo(PostType.REPOST);
        assertThat(withText.post().getType()).isEqualTo(PostType.QUOTE);
        assertThat(withText.post().getContent()).isEqualTo("look");
    }

    // ── Read model: counters, viewer state, nested original, no N+1 ──────────

    @Test
    void viewsCarryCountersViewerStateAndTheNestedOriginal() {
        var original = Post.create(BOB, "bob", "orig", "img.png");
        var repost = Post.repost(CAROL, "carol", original);
        var quote = Post.quote(CAROL, "carol", original, "q");
        when(posts.findAllById(any())).thenReturn(List.of(original));
        when(posts.countRepostsAndQuotes(any())).thenReturn(List.<Object[]>of(
                new Object[] {original.getId(), PostType.REPOST, 3L}, new Object[] {original.getId(), PostType.QUOTE, 1L}));
        when(posts.findRepostedOriginalIds(eq(ALICE), any())).thenReturn(List.of(original.getId()));
        when(reactions.findByUserIdAndPostIdIn(eq(ALICE), any()))
                .thenReturn(List.of(PostReaction.of(original.getId(), ALICE, ReactionType.DISLIKE)));

        var views = service.views(List.of(repost, quote), ALICE);

        var r = views.get(0);
        assertThat(r.repostsCount()).isZero();
        assertThat(r.viewerReposted()).isFalse(); // the repost row itself
        assertThat(r.original().post()).isSameAs(original);
        assertThat(r.original().repostsCount()).isEqualTo(3);
        assertThat(r.original().quotesCount()).isEqualTo(1);
        assertThat(r.original().viewerReposted()).isTrue();
        assertThat(r.original().userReaction()).isEqualTo(ReactionType.DISLIKE);
        assertThat(r.original().original()).isNull();
        assertThat(views.get(1).original().post()).isSameAs(original);
        verify(posts, times(1)).findAllById(java.util.Set.of(original.getId()));
    }

    @Test
    void originalAlreadyOnThePageIsNotLoadedAgain_deletedOriginalIsNull() {
        var original = Post.create(BOB, "bob", "orig", null);
        var repost = Post.repost(CAROL, "carol", original);
        var orphanQuote = Post.quote(CAROL, "carol", Post.create(BOB, "bob", "gone", null), "q");

        var views = service.views(List.of(repost, original, orphanQuote), ALICE);

        assertThat(views.get(0).original().post()).isSameAs(original);
        assertThat(views.get(2).original()).isNull();
        // only the missing original is looked up
        verify(posts, times(1)).findAllById(java.util.Set.of(orphanQuote.getOriginalPostId()));
    }

    @Test
    void repostOfAQuoteNestsTheQuotedPost_secondLevelOnlyThere() {
        var quoted = Post.create(BOB, "bob", "quoted", null);
        var quote = Post.quote(CAROL, "carol", quoted, "c");
        var repostOfQuote = Post.repost(ALICE, "alice", quote);
        var quoteOfQuote = Post.quote(ALICE, "alice", quote, "qq");
        when(posts.findAllById(java.util.Set.of(quote.getId()))).thenReturn(List.of(quote));
        when(posts.findAllById(java.util.Set.of(quoted.getId()))).thenReturn(List.of(quoted));

        var views = service.views(List.of(repostOfQuote, quoteOfQuote), ALICE);

        var nested = views.get(0).original();
        assertThat(nested.post()).isSameAs(quote);
        assertThat(nested.original().post()).isSameAs(quoted);
        assertThat(nested.original().original()).isNull();
        // a QUOTE of a quote stays one level
        assertThat(views.get(1).original().post()).isSameAs(quote);
        assertThat(views.get(1).original().original()).isNull();
        verify(posts, times(2)).findAllById(any()); // level 1, level 2: one query each
    }

    @Test
    void repostOfAQuoteWhoseQuotedPostIsDeleted_secondLevelIsNull() {
        var quote = Post.quote(CAROL, "carol", Post.create(BOB, "bob", "gone", null), "c");
        var repostOfQuote = Post.repost(ALICE, "alice", quote);
        when(posts.findAllById(java.util.Set.of(quote.getId()))).thenReturn(List.of(quote));

        var nested = service.views(List.of(repostOfQuote), ALICE).get(0).original();

        assertThat(nested.post()).isSameAs(quote);
        assertThat(nested.original()).isNull();
    }

    @Test
    void numberOfQueriesDoesNotGrowWithPageSize() {
        for (int size : new int[] {3, 30}) {
            clearInvocations(posts, reactions, profiles, hashtags, mentions);
            var page = new java.util.ArrayList<Post>();
            var originals = new java.util.ArrayList<Post>();
            var quotedOnes = new java.util.ArrayList<Post>();
            for (int i = 0; i < size; i++) {
                var quoted = Post.create(BOB, "bob", "quoted " + i, null);
                var quote = Post.quote(CAROL, "carol", quoted, "q" + i);
                quotedOnes.add(quoted);
                originals.add(quote);
                page.add(Post.repost(ALICE, "alice", quote));
                page.add(Post.quote(ALICE, "alice", quote, "again " + i));
                page.add(Post.create(ALICE, "alice", "plain " + i, null));
            }
            when(posts.findAllById(any())).thenReturn(originals, quotedOnes);

            var views = service.views(page, ALICE);

            assertThat(views).hasSize(3 * size);
            assertThat(views.get(0).original().original()).isNotNull();
            verify(posts, times(2)).findAllById(any());
            verify(posts, times(1)).countRepostsAndQuotes(any());
            verify(posts, times(1)).findRepostedOriginalIds(any(), any());
            verify(reactions, times(1)).findByUserIdAndPostIdIn(any(), any());
            verify(profiles, times(1)).findAllById(any());
            verify(hashtags, times(1)).findByPostIds(any());
            verify(mentions, times(1)).findByPostIds(any());
            verify(posts, never()).findById(any());
            verify(profiles, never()).findById(any());
        }
    }
}
