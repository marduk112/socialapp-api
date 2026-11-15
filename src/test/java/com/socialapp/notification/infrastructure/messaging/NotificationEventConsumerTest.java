package com.socialapp.notification.infrastructure.messaging;

import com.socialapp.notification.application.ActorProfileProjection;
import com.socialapp.notification.application.NotificationService;
import com.socialapp.notification.application.NotificationView;
import com.socialapp.notification.domain.NotificationType;
import com.socialapp.notification.infrastructure.sse.NotificationSseService;
import com.socialapp.shared.events.UserCreatedEvent;
import com.socialapp.shared.events.UserFollowedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationEventConsumerTest {

    private final NotificationService service = mock(NotificationService.class);
    private final ActorProfileProjection profiles = mock(ActorProfileProjection.class);
    private final NotificationSseService sse = mock(NotificationSseService.class);
    private final NotificationEventConsumer consumer = new NotificationEventConsumer(service, profiles, sse);

    @Test
    void pushesOnlyWhatBecameVisible_afterTheHandlerReturned() {
        var follow = new UserFollowedEvent(UUID.randomUUID(), Instant.now(), UUID.randomUUID(), UUID.randomUUID());
        var view = new NotificationView(UUID.randomUUID(), NotificationType.FOLLOW, follow.followerId(), "a", null,
                null, null, null, false, Instant.now(), follow.followeeId());
        when(service.on(follow)).thenReturn(List.of(view));

        consumer.onSocialEvent(follow);

        var order = inOrder(service, sse);
        order.verify(service).on(follow);
        order.verify(sse).push(List.of(view));

        when(service.on(follow)).thenReturn(List.of()); // redelivery: nothing new
        consumer.onSocialEvent(follow);
        verifyNoMoreInteractions(sse);
    }

    @Test
    void userEventsFeedTheProfileProjectionOnly() {
        var created = new UserCreatedEvent(UUID.randomUUID(), "u", "u@x.io", null);
        consumer.onUserEvent(created);
        verify(profiles).on(created);
        verifyNoInteractions(service, sse);
        verify(sse, never()).push(any());
    }
}
