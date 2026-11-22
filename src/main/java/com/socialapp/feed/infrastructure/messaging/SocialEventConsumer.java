package com.socialapp.feed.infrastructure.messaging;

import com.socialapp.feed.application.timeline.BlockMuteLookup;
import com.socialapp.feed.application.timeline.FolloweeLookup;
import com.socialapp.feed.application.usecase.BlockMuteProjection;
import com.socialapp.feed.application.usecase.FollowProjection;
import com.socialapp.shared.events.CommentCreatedEvent;
import com.socialapp.shared.events.DomainEvent;
import com.socialapp.shared.events.PostCreatedEvent;
import com.socialapp.shared.events.ReactionEvent;
import com.socialapp.shared.events.UserBlockedEvent;
import com.socialapp.shared.events.UserCreatedEvent;
import com.socialapp.shared.events.UserFollowedEvent;
import com.socialapp.shared.events.UserMutedEvent;
import com.socialapp.shared.events.UserUnblockedEvent;
import com.socialapp.shared.events.UserUnfollowedEvent;
import com.socialapp.shared.events.UserUnmutedEvent;
import com.socialapp.shared.events.UserUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Feed's single listener on social-events (one consumer group for the BC): follows -> {@code follows}, blocks and mutes
 * (stage 5A) -> {@code feed_blocks} / {@code feed_mutes}. A follower's (blocker's, muter's) events share a key,
 * hence a partition, so they normally arrive in order - but the projection does not rely on it.
 * Malformed records go to {@code social-events.DLT} (ErrorHandlingDeserializer + DefaultErrorHandler).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SocialEventConsumer {

    private final FollowProjection projection;
    private final FolloweeLookup followeeLookup;
    private final BlockMuteProjection blockMuteProjection;
    private final BlockMuteLookup blockMuteLookup;

    @KafkaListener(
            topics = "${kafka.topics.social-events}",
            groupId = "feed-follows-group",
            containerFactory = "feedSocialEventListenerContainerFactory"
    )
    public void onSocialEvent(@Payload DomainEvent event) {
        switch (event) {
            case UserFollowedEvent e -> {
                log.debug("UserFollowed {} -> {}", e.followerId(), e.followeeId());
                if (projection.on(e)) followeeLookup.invalidate(e.followerId()); // after commit
            }
            case UserUnfollowedEvent e -> {
                log.debug("UserUnfollowed {} -> {}", e.followerId(), e.followeeId());
                if (projection.on(e)) followeeLookup.invalidate(e.followerId());
            }
            // A block changes what BOTH users see; a mute only the muter's view.
            case UserBlockedEvent e -> {
                if (blockMuteProjection.on(e)) invalidateBoth(e.blockerId(), e.blockedId());
            }
            case UserUnblockedEvent e -> {
                if (blockMuteProjection.on(e)) invalidateBoth(e.blockerId(), e.blockedId());
            }
            case UserMutedEvent e -> {
                if (blockMuteProjection.on(e)) blockMuteLookup.invalidate(e.muterId());
            }
            case UserUnmutedEvent e -> {
                if (blockMuteProjection.on(e)) blockMuteLookup.invalidate(e.muterId());
            }
            case UserCreatedEvent e -> log.warn("Unexpected UserCreatedEvent on social-events, ignoring: {}", e.eventId());
            case UserUpdatedEvent e -> log.warn("Unexpected UserUpdatedEvent on social-events, ignoring: {}", e.eventId());
            case PostCreatedEvent e -> log.warn("Unexpected PostCreatedEvent on social-events, ignoring: {}", e.eventId());
            case ReactionEvent e -> log.warn("Unexpected ReactionEvent on social-events, ignoring: {}", e.eventId());
            case CommentCreatedEvent e -> log.warn("Unexpected CommentCreatedEvent on social-events, ignoring: {}", e.eventId());
        }
    }

    private void invalidateBoth(java.util.UUID a, java.util.UUID b) {
        blockMuteLookup.invalidate(a);
        blockMuteLookup.invalidate(b);
    }
}
