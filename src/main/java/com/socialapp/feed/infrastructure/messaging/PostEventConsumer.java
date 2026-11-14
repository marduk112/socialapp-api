package com.socialapp.feed.infrastructure.messaging;

import com.socialapp.feed.application.usecase.PostService;
import com.socialapp.feed.domain.AuthorProfile;
import com.socialapp.feed.domain.AuthorProfileRepository;
import com.socialapp.feed.domain.PostRepository;
import com.socialapp.feed.domain.PostType;
import com.socialapp.feed.infrastructure.sse.FeedSseService;
import com.socialapp.feed.infrastructure.web.PostResponse;
import com.socialapp.shared.events.PostCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes PostCreatedEvents (posts, reposts, quotes) from Kafka and fans out to SSE subscribers.
 * Avatar is resolved from author_profiles so it is always current; the type, the embedded original
 * (built by {@link PostService#views} without a viewer, so no N+1 and the same shape as REST), hashtags and
 * mentions are read from Feed's own tables (committed together with the post, before the event left the outbox).
 * A post deleted before its event is consumed is still broadcast from the event's own fields (type from the
 * event; no original, tags or mentions).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PostEventConsumer {

    private final FeedSseService feedSseService;
    private final AuthorProfileRepository authorProfileRepository;
    private final PostRepository postRepository;
    private final PostService postService;

    @KafkaListener(
            topics = "${kafka.topics.post-events}",
            groupId = "feed-sse-group",
            containerFactory = "postEventListenerContainerFactory"
    )
    public void onPostCreated(@Payload PostCreatedEvent event) {
        log.info("Received PostCreatedEvent postId={} type={}, broadcasting via SSE", event.postId(), event.postType());

        String avatarUrl = authorProfileRepository.findById(event.authorId())
                .map(AuthorProfile::getAvatarUrl)
                .orElse(null);
        var view = postRepository.findById(event.postId()).map(p -> postService.view(p, null));
        var post = view.map(v -> v.post());
        var original = view.map(v -> PostResponse.from(v.original())).orElse(null);

        feedSseService.broadcast(new FeedSseService.PostSsePayload(
                event.postId(),
                event.authorId(),
                event.authorUsername(),
                avatarUrl,
                event.content(),
                event.imageUrl(),
                event.occurredAt(),
                post.map(p -> p.getOriginalPostId()).orElse(null),
                post.map(p -> p.getOriginalAuthorUsername()).orElse(null),
                post.map(p -> p.getOriginalContent()).orElse(null),
                view.map(v -> v.hashtags()).orElse(null),
                view.map(v -> v.mentions()).orElse(null),
                post.map(p -> p.getType()).orElseGet(() -> typeOf(event)),
                original == null ? null : original.authorId(),
                original
        ));
    }

    private static PostType typeOf(PostCreatedEvent event) {
        try {
            return event.postType() == null ? PostType.POST : PostType.valueOf(event.postType());
        } catch (IllegalArgumentException e) {
            return PostType.POST;
        }
    }
}
