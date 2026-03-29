package com.socialapp.feed.infrastructure.messaging;

import com.socialapp.feed.domain.AuthorProfile;
import com.socialapp.feed.domain.AuthorProfileRepository;
import com.socialapp.shared.events.DomainEvent;
import com.socialapp.shared.events.UserCreatedEvent;
import com.socialapp.shared.events.UserUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps author_profiles in sync with user-events so posts always show the current avatar.
 */
@Component("feedUserEventConsumer")
@RequiredArgsConstructor
@Slf4j
public class UserEventConsumer {

    private final AuthorProfileRepository authorProfileRepository;

    @KafkaListener(
            topics = "${kafka.topics.user-events}",
            groupId = "feed-avatar-group",
            containerFactory = "feedUserEventListenerContainerFactory"
    )
    @Transactional
    public void onUserCreated(@Payload DomainEvent event) {
        if (!(event instanceof UserCreatedEvent e)) return;
        log.debug("Caching avatar for new userId={}", e.userId());
        upsert(e.userId(), e.avatarUrl());
    }

    @KafkaListener(
            topics = "${kafka.topics.user-events}",
            groupId = "feed-avatar-update-group",
            containerFactory = "feedUserUpdatedEventListenerContainerFactory"
    )
    @Transactional
    public void onUserUpdated(@Payload DomainEvent event) {
        if (!(event instanceof UserUpdatedEvent e)) return;
        log.debug("Updating avatar cache for userId={}", e.userId());
        upsert(e.userId(), e.avatarUrl());
    }

    private void upsert(java.util.UUID userId, String avatarUrl) {
        var profile = authorProfileRepository.findById(userId)
                .orElseGet(() -> new AuthorProfile(userId, avatarUrl));
        profile.updateAvatarUrl(avatarUrl);
        authorProfileRepository.save(profile);
    }
}
