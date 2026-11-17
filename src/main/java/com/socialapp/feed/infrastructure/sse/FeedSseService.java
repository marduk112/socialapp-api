package com.socialapp.feed.infrastructure.sse;

import com.socialapp.feed.application.timeline.FeedScope;
import com.socialapp.feed.application.timeline.FolloweeLookup;
import com.socialapp.feed.application.media.MediaView;
import com.socialapp.feed.domain.Mention;
import com.socialapp.feed.domain.PostType;
import com.socialapp.feed.infrastructure.web.PostResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * SSE broker. Uses CopyOnWriteArrayList for thread-safe subscription management.
 * When a PostCreatedEvent arrives from Kafka, it is broadcast to every connected client whose scope admits
 * it: {@link FeedScope#ALL} receives every post, {@link FeedScope#FOLLOWING} only posts by the viewer or by
 * users the viewer follows, evaluated per post against Feed's follows projection
 * ({@link FolloweeLookup}, short-TTL cache), so follows made while connected take effect without
 * reconnecting. Event names ("connected", "new-post") and payload are the same for both scopes.
 */
@Service
@Slf4j
public class FeedSseService {

    /** One open stream: who is watching which timeline. */
    record Subscription(SseEmitter emitter, UUID viewerId, FeedScope scope) {}

    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final JsonMapper objectMapper;
    private final FolloweeLookup followeeLookup;
    private final Supplier<SseEmitter> emitterFactory;

    @Autowired
    public FeedSseService(JsonMapper objectMapper, FolloweeLookup followeeLookup) {
        this(objectMapper, followeeLookup, () -> new SseEmitter(Long.MAX_VALUE));
    }

    FeedSseService(JsonMapper objectMapper, FolloweeLookup followeeLookup, Supplier<SseEmitter> emitterFactory) {
        this.objectMapper = objectMapper;
        this.followeeLookup = followeeLookup;
        this.emitterFactory = emitterFactory;
    }

    /**
     * SSE payload sent to frontend — avatar comes from author_profiles, not from the post. Field names of the
     * pre-2C fields are unchanged ({@code postId}, not {@code id}). {@code type}, {@code originalAuthorId} and
     * {@code original} (a {@link PostResponse}, same shape and nesting rules as in REST, built from the database at
     * broadcast time; viewer-specific fields are {@code viewerReposted = false}, {@code bookmarked = false}, {@code userReaction = null}; the
     * payload itself has no viewer flags, a broadcast is viewer-agnostic: clients treat a live post as not bookmarked) were
     * added in stage 2C; {@code media} (public data, like PostResponse.media) in stage 3C.
     */
    public record PostSsePayload(
            UUID postId,
            UUID authorId,
            String authorUsername,
            String authorAvatarUrl,
            String content,
            String imageUrl,
            Instant occurredAt,
            // Set only for reposts/quotes (same names as in PostResponse); null otherwise.
            UUID originalPostId,
            String originalAuthorUsername,
            String originalContent,
            // Same names and shapes as in PostResponse; never null (empty when none / post already deleted).
            List<String> hashtags,
            List<Mention> mentions,
            // POST | REPOST | QUOTE (never null)
            PostType type,
            // Author of the embedded original; null for a POST or when the original is gone.
            UUID originalAuthorId,
            // The embedded original of a REPOST/QUOTE (null for a POST or when it is gone); carries its own media.
            PostResponse original,
            // The post's own images, same shape and order as PostResponse.media (stage 3C); never null.
            List<MediaView> media
    ) {
        public PostSsePayload {
            hashtags = hashtags == null ? List.of() : List.copyOf(hashtags);
            mentions = mentions == null ? List.of() : List.copyOf(mentions);
            type = type == null ? PostType.POST : type;
            media = media == null ? List.of() : List.copyOf(media);
        }

        /** Pre-3C shape (no media). */
        public PostSsePayload(UUID postId, UUID authorId, String authorUsername, String authorAvatarUrl,
                              String content, String imageUrl, Instant occurredAt, UUID originalPostId,
                              String originalAuthorUsername, String originalContent, List<String> hashtags,
                              List<Mention> mentions, PostType type, UUID originalAuthorId, PostResponse original) {
            this(postId, authorId, authorUsername, authorAvatarUrl, content, imageUrl, occurredAt, originalPostId,
                    originalAuthorUsername, originalContent, hashtags, mentions, type, originalAuthorId, original, null);
        }

        /** A plain post (pre-2C shape). */
        public PostSsePayload(UUID postId, UUID authorId, String authorUsername, String authorAvatarUrl,
                              String content, String imageUrl, Instant occurredAt, UUID originalPostId,
                              String originalAuthorUsername, String originalContent, List<String> hashtags,
                              List<Mention> mentions) {
            this(postId, authorId, authorUsername, authorAvatarUrl, content, imageUrl, occurredAt, originalPostId,
                    originalAuthorUsername, originalContent, hashtags, mentions, PostType.POST, null, null, null);
        }
    }

    public SseEmitter subscribe(UUID viewerId, FeedScope scope) {
        var emitter = emitterFactory.get();
        var subscription = new Subscription(emitter, viewerId, scope == null ? FeedScope.ALL : scope);

        emitter.onCompletion(() -> subscriptions.remove(subscription));
        emitter.onTimeout(() -> {
            subscriptions.remove(subscription);
            emitter.complete();
        });
        emitter.onError(e -> subscriptions.remove(subscription));

        subscriptions.add(subscription);
        log.debug("New SSE subscriber (scope={}). Total: {}", subscription.scope(), subscriptions.size());

        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            subscriptions.remove(subscription);
        }

        return emitter;
    }

    /**
     * Spring Boot 3.4 shuts Tomcat down gracefully and waits for "active requests" — an open SSE
     * stream never finishes on its own, so without this the JVM sits out the whole
     * spring.lifecycle.timeout-per-shutdown-phase (30s). ContextClosedEvent is published before the
     * lifecycle beans are stopped, so completing the emitters here lets the graceful wait return at once.
     * Browsers' EventSource reconnects automatically once the new instance is up.
     */
    @EventListener(ContextClosedEvent.class)
    public void completeAllOnShutdown() {
        log.info("Shutting down: completing {} SSE stream(s)", subscriptions.size());
        for (Subscription subscription : subscriptions) {
            try {
                subscription.emitter().complete();
            } catch (RuntimeException e) {
                log.debug("SSE emitter already closed during shutdown", e);
            }
        }
        subscriptions.clear();
    }

    int subscriberCount() {
        return subscriptions.size();
    }

    @Async
    public void broadcast(PostSsePayload payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            log.error("Cannot serialize SSE payload for post {}", payload.postId(), e);
            return;
        }
        List<Subscription> dead = new ArrayList<>();

        for (Subscription subscription : subscriptions) {
            if (!admits(subscription, payload)) continue;
            try {
                subscription.emitter().send(SseEmitter.event()
                        .name("new-post")
                        .data(json));
            } catch (IOException e) {
                dead.add(subscription);
                log.debug("SSE emitter dead, removing. Remaining: {}", subscriptions.size() - dead.size());
            }
        }
        subscriptions.removeAll(dead);
    }

    private boolean admits(Subscription subscription, PostSsePayload payload) {
        if (subscription.scope() == FeedScope.ALL) return true;
        try {
            return followeeLookup.isVisibleInFollowing(subscription.viewerId(), payload.authorId());
        } catch (RuntimeException e) {
            // Projection unreadable (DB down): skip rather than leak unrelated posts; the client's
            // REST refresh catches up later.
            log.warn("Cannot evaluate following filter for viewer {}: {}", subscription.viewerId(), e.toString());
            return false;
        }
    }
}
