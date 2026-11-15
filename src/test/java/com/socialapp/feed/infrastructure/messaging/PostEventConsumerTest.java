package com.socialapp.feed.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.feed.application.usecase.PostService;
import com.socialapp.feed.domain.*;
import com.socialapp.feed.infrastructure.sse.FeedSseService;
import com.socialapp.shared.events.PostCreatedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PostEventConsumerTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();

    private final FeedSseService sse = mock(FeedSseService.class);
    private final AuthorProfileRepository profiles = mock(AuthorProfileRepository.class);
    private final PostRepository posts = mock(PostRepository.class);
    private final PostReactionRepository reactions = mock(PostReactionRepository.class);
    private final PostHashtagRepository hashtags = mock(PostHashtagRepository.class);
    private final PostMentionRepository mentions = mock(PostMentionRepository.class);
    private final PostService postService = new PostService(posts, reactions, mock(CommentRepository.class),
            mock(CommentReactionRepository.class), profiles, hashtags, mentions,
            mock(BookmarkRepository.class));
    private final PostEventConsumer consumer = new PostEventConsumer(sse, profiles, posts, postService);

    private FeedSseService.PostSsePayload broadcastOf(PostCreatedEvent event) {
        consumer.onPostCreated(event);
        var captor = ArgumentCaptor.forClass(FeedSseService.PostSsePayload.class);
        verify(sse).broadcast(captor.capture());
        return captor.getValue();
    }

    private static PostCreatedEvent eventOf(Post post) {
        return new PostCreatedEvent(post.getId(), post.getAuthorId(), post.getAuthorUsername(), post.getContent(),
                post.getImageUrl(), post.getType().name(), post.getOriginalPostId());
    }

    @SuppressWarnings("unchecked")
    private void stored(Post... stored) {
        var byId = new java.util.HashMap<UUID, Post>();
        for (Post p : stored) byId.put(p.getId(), p);
        when(posts.findById(any())).thenAnswer(inv -> Optional.ofNullable(byId.get(inv.<UUID>getArgument(0))));
        when(posts.findAllById(any())).thenAnswer(inv -> ((Collection<UUID>) inv.getArgument(0)).stream()
                .map(byId::get).filter(java.util.Objects::nonNull).toList());
    }

    @Test
    void ssePayloadCarriesTheStoredHashtagsAndMentions() {
        var post = Post.create(ALICE, "alice", "#Hello @bob", null);
        stored(post);
        when(profiles.findById(ALICE)).thenReturn(Optional.of(new AuthorProfile(ALICE, "alice", "a.png", null)));
        when(hashtags.findByPostIds(List.of(post.getId()))).thenReturn(Map.of(post.getId(), List.of("hello")));
        when(mentions.findByPostIds(List.of(post.getId())))
                .thenReturn(Map.of(post.getId(), List.of(new Mention(BOB, "bob"))));

        var payload = broadcastOf(new PostCreatedEvent(post.getId(), ALICE, "alice", post.getContent(), null));

        assertThat(payload.hashtags()).containsExactly("hello");
        assertThat(payload.mentions()).containsExactly(new Mention(BOB, "bob"));
        assertThat(payload.authorAvatarUrl()).isEqualTo("a.png");
        assertThat(payload.type()).isEqualTo(PostType.POST);
        assertThat(payload.original()).isNull();
        assertThat(payload.originalAuthorId()).isNull();
    }

    @Test
    void postWithoutTagsOrAlreadyDeletedGetsEmptyLists() {
        when(posts.findById(any())).thenReturn(Optional.empty());

        var payload = broadcastOf(new PostCreatedEvent(UUID.randomUUID(), ALICE, "alice", "#gone", null));

        assertThat(payload.hashtags()).isEmpty();
        assertThat(payload.mentions()).isEmpty();
        assertThat(payload.type()).isEqualTo(PostType.POST);
        verifyNoInteractions(hashtags, mentions);
    }

    @Test
    void deletedRepostIsBroadcastWithTheTypeFromTheEvent() {
        when(posts.findById(any())).thenReturn(Optional.empty());

        var payload = broadcastOf(new PostCreatedEvent(UUID.randomUUID(), ALICE, "alice", "", null, "REPOST",
                UUID.randomUUID()));

        assertThat(payload.type()).isEqualTo(PostType.REPOST);
        assertThat(payload.original()).isNull();
    }

    @Test
    void repostPayloadEmbedsTheFullOriginal_withoutViewerSpecificState() throws Exception {
        var original = Post.create(BOB, "bob", "#Orig by bob @carol", "img.png");
        var repost = Post.repost(ALICE, "alice", original);
        stored(original, repost);
        when(profiles.findAllById(any())).thenReturn(List.of(new AuthorProfile(BOB, "bob", "b.png", null)));
        when(hashtags.findByPostIds(any())).thenReturn(Map.of(original.getId(), List.of("orig")));
        when(mentions.findByPostIds(any())).thenReturn(Map.of(original.getId(), List.of(new Mention(CAROL, "carol"))));
        when(posts.countRepostsAndQuotes(any())).thenReturn(List.<Object[]>of(
                new Object[] {original.getId(), PostType.REPOST, 1L}, new Object[] {original.getId(), PostType.QUOTE, 2L}));

        var payload = broadcastOf(eventOf(repost));

        assertThat(payload.type()).isEqualTo(PostType.REPOST);
        assertThat(payload.content()).isEmpty();
        assertThat(payload.originalPostId()).isEqualTo(original.getId());
        assertThat(payload.originalAuthorId()).isEqualTo(BOB);
        var o = payload.original();
        assertThat(o.id()).isEqualTo(original.getId());
        assertThat(o.authorAvatarUrl()).isEqualTo("b.png");
        assertThat(o.imageUrl()).isEqualTo("img.png");
        assertThat(o.hashtags()).containsExactly("orig");
        assertThat(o.mentions()).containsExactly(new Mention(CAROL, "carol"));
        assertThat(o.repostsCount()).isEqualTo(1);
        assertThat(o.quotesCount()).isEqualTo(2);
        assertThat(o.viewerReposted()).isFalse();
        assertThat(o.userReaction()).isNull();
        assertThat(o.original()).isNull();
        // a broadcast has no viewer: no per-viewer queries at all
        verify(posts, never()).findRepostedOriginalIds(any(), any());
        verifyNoInteractions(reactions);

        // JSON shape: pre-2C names kept (postId), new fields present, nested original has REST names (id)
        var json = new ObjectMapper().findAndRegisterModules().valueToTree(payload);
        assertThat(json.get("postId").asText()).isEqualTo(repost.getId().toString());
        assertThat(json.get("type").asText()).isEqualTo("REPOST");
        assertThat(json.get("originalAuthorId").asText()).isEqualTo(BOB.toString());
        assertThat(json.get("original").get("id").asText()).isEqualTo(original.getId().toString());
        assertThat(json.get("original").get("type").asText()).isEqualTo("POST");
        assertThat(json.get("original").get("viewerReposted").asBoolean()).isFalse();
        assertThat(json.get("original").get("userReaction").isNull()).isTrue();
        assertThat(json.get("original").get("original").isNull()).isTrue();
    }

    @Test
    void quotePayloadHasItsOwnTagsAndTheOriginal_originalGoneMeansNull() {
        var original = Post.create(BOB, "bob", "orig", null);
        var quote = Post.quote(ALICE, "alice", original, "my #take");
        stored(quote); // original deleted meanwhile
        when(hashtags.findByPostIds(any())).thenReturn(Map.of(quote.getId(), List.of("take")));

        var payload = broadcastOf(eventOf(quote));

        assertThat(payload.type()).isEqualTo(PostType.QUOTE);
        assertThat(payload.content()).isEqualTo("my #take");
        assertThat(payload.hashtags()).containsExactly("take");
        assertThat(payload.original()).isNull();
        assertThat(payload.originalAuthorId()).isNull();
    }

    @Test
    void repostOfAQuoteCarriesTheQuotedPostAsSecondLevel() {
        var quoted = Post.create(CAROL, "carol", "quoted", null);
        var quote = Post.quote(BOB, "bob", quoted, "bob's take");
        var repost = Post.repost(ALICE, "alice", quote);
        stored(quoted, quote, repost);

        var payload = broadcastOf(eventOf(repost));

        assertThat(payload.original().id()).isEqualTo(quote.getId());
        assertThat(payload.original().type()).isEqualTo(PostType.QUOTE);
        assertThat(payload.original().original().id()).isEqualTo(quoted.getId());
        assertThat(payload.original().original().original()).isNull();
        assertThat(payload.original().originalAuthorId()).isEqualTo(CAROL);
    }
}
