package com.socialapp.socialgraph.application.usecase;

import com.socialapp.shared.domain.ForbiddenOperationException;
import com.socialapp.shared.domain.NotFoundException;
import com.socialapp.shared.events.UserCreatedEvent;
import com.socialapp.shared.events.UserUpdatedEvent;
import com.socialapp.socialgraph.application.command.FollowUserCommand;
import com.socialapp.socialgraph.domain.UserNodeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SocialGraphServiceTest {

    private final UserNodeRepository repo = mock(UserNodeRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final FollowSuggestionQueries suggestions = mock(FollowSuggestionQueries.class);
    private final SocialGraphService service = new SocialGraphService(repo, events, suggestions);

    @Test
    void selfFollowIsRejectedBeforeTouchingTheGraph() {
        var me = UUID.randomUUID();

        assertThatThrownBy(() -> service.follow(new FollowUserCommand(me, me)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repo);
    }

    @Test
    void followUsesSingleRelationshipStatementInsteadOfSavingTheNode() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        when(repo.existsById(anyString())).thenReturn(true);
        when(repo.follow(eq(a.toString()), eq(b.toString()), anyString(), anyLong()))
                .thenReturn(Optional.of(1L), Optional.empty());

        assertThat(service.follow(new FollowUserCommand(a, b))).isTrue();
        assertThat(service.follow(new FollowUserCommand(a, b))).isFalse(); // already following: no-op

        verify(repo, times(2)).follow(eq(a.toString()), eq(b.toString()), anyString(), anyLong());
        verify(repo, never()).save(any());
    }

    @Test
    void graphChangeSignalsTheForwarder_noOpSignalsNothing() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        when(repo.existsById(anyString())).thenReturn(true);
        when(repo.follow(anyString(), anyString(), anyString(), anyLong())).thenReturn(Optional.of(1L));
        when(repo.unfollow(anyString(), anyString(), anyString(), anyLong())).thenReturn(Optional.empty());

        service.follow(new FollowUserCommand(a, b));
        assertThat(service.unfollow(new FollowUserCommand(a, b))).isFalse(); // pretend nothing to delete

        verify(events, times(1)).publishEvent(any(FollowGraphChanged.class));
    }

    @Test
    void eachGraphChangeGetsAFreshEventId() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        when(repo.existsById(anyString())).thenReturn(true);
        when(repo.follow(anyString(), anyString(), anyString(), anyLong())).thenReturn(Optional.of(1L));
        when(repo.unfollow(anyString(), anyString(), anyString(), anyLong())).thenReturn(Optional.of(2L));

        service.follow(new FollowUserCommand(a, b));
        service.unfollow(new FollowUserCommand(a, b));

        var ids = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repo).follow(anyString(), anyString(), ids.capture(), anyLong());
        verify(repo).unfollow(anyString(), anyString(), ids.capture(), anyLong());
        assertThat(ids.getAllValues()).doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(UUID.fromString(id)).isNotNull());
    }

    @Test
    void followUnknownUserIsNotFound() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        when(repo.existsById(a.toString())).thenReturn(true);
        when(repo.existsById(b.toString())).thenReturn(false);

        assertThatThrownBy(() -> service.follow(new FollowUserCommand(a, b)))
                .isInstanceOf(NotFoundException.class);
        verify(repo, never()).follow(anyString(), anyString(), anyString(), anyLong());
        verifyNoInteractions(events);
    }

    @Test
    void userEventsAreProjectedWithTheirTimestamp() {
        var id = UUID.randomUUID();
        var at = Instant.parse("2026-01-01T10:00:00Z");

        service.syncUserCreated(new UserCreatedEvent(UUID.randomUUID(), at, id, "alice", "a@x.io", null));
        service.syncUserUpdated(new UserUpdatedEvent(UUID.randomUUID(), at.plusSeconds(1), id, "alice2", "a.png", null));

        verify(repo).createIfAbsent(id.toString(), "alice", null, at.toEpochMilli());
        verify(repo).upsertProfile(id.toString(), "alice2", "a.png", at.plusSeconds(1).toEpochMilli());
    }

    private static FollowSuggestion suggestion(String id, long mutual) {
        return new FollowSuggestion(id, "user-" + id, null, mutual);
    }

    @Test
    void suggestionsAreToppedUpWithMostFollowedExcludingAlreadySuggested() {
        var me = UUID.randomUUID();
        when(suggestions.friendsOfFriends(me.toString(), 3)).thenReturn(List.of(suggestion("a", 2)));
        when(suggestions.mostFollowed(eq(me.toString()), any(), eq(2)))
                .thenReturn(List.of(suggestion("b", 0), suggestion("c", 0)));

        var result = service.getSuggestions(me, 3);

        assertThat(result).extracting(FollowSuggestion::userId).containsExactly("a", "b", "c");
        verify(suggestions).mostFollowed(me.toString(), java.util.Set.of("a"), 2);
    }

    @Test
    void suggestionsSkipTheFallbackWhenFriendsOfFriendsFillTheLimit() {
        var me = UUID.randomUUID();
        when(suggestions.friendsOfFriends(me.toString(), 2))
                .thenReturn(List.of(suggestion("a", 3), suggestion("b", 1)));

        assertThat(service.getSuggestions(me, 2)).hasSize(2);
        verify(suggestions, never()).mostFollowed(anyString(), any(), anyInt());
    }

    @Test
    void suggestionLimitIsClamped() {
        var me = UUID.randomUUID();
        when(suggestions.friendsOfFriends(anyString(), anyInt())).thenReturn(List.of());
        when(suggestions.mostFollowed(anyString(), any(), anyInt())).thenReturn(List.of());

        service.getSuggestions(me, 1000);
        service.getSuggestions(me, 0);

        verify(suggestions).friendsOfFriends(me.toString(), SocialGraphService.MAX_SUGGESTIONS);
        verify(suggestions).friendsOfFriends(me.toString(), 1);
    }

    // ── Blocks and mutes (stage 5A) ─────────────────────────────────────────

    @Test
    void selfBlockAndSelfMuteAre400_beforeTouchingTheGraph() {
        var me = UUID.randomUUID();

        assertThatThrownBy(() -> service.block(me, me)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.mute(me, me)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repo, events);
    }

    @Test
    void blockingOrMutingAnUnknownUserIs404() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        when(repo.existsById(a.toString())).thenReturn(true);
        when(repo.existsById(b.toString())).thenReturn(false);

        assertThatThrownBy(() -> service.block(a, b)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.mute(a, b)).isInstanceOf(NotFoundException.class);
        verify(repo, never()).block(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(repo, never()).mute(anyString(), anyString(), anyString(), anyLong());
        verifyNoInteractions(events);
    }

    @Test
    void blockIsIdempotent_onlyAChangeSignalsTheForwarder_withThreeDistinctEventIds() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        when(repo.existsById(anyString())).thenReturn(true);
        when(repo.block(eq(a.toString()), eq(b.toString()), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(Optional.of(1L), Optional.empty());

        assertThat(service.block(a, b)).isTrue();
        assertThat(service.block(a, b)).isFalse(); // already blocked: no-op

        verify(events, times(1)).publishEvent(any(FollowGraphChanged.class));
        var first = org.mockito.ArgumentCaptor.forClass(String.class);
        var second = org.mockito.ArgumentCaptor.forClass(String.class);
        var third = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repo, times(2)).block(anyString(), anyString(), first.capture(), second.capture(), third.capture(),
                anyLong());
        assertThat(List.of(first.getValue(), second.getValue(), third.getValue())).doesNotHaveDuplicates();
    }

    @Test
    void unblockAndUnmuteNeverValidate_andANoOpSignalsNothing() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        when(repo.unblock(anyString(), anyString(), anyString(), anyLong())).thenReturn(Optional.empty());
        when(repo.unmute(anyString(), anyString(), anyString(), anyLong())).thenReturn(Optional.of(5L));

        assertThat(service.unblock(a, b)).isFalse();
        assertThat(service.unblock(a, a)).isFalse(); // self: nothing to remove, still no error
        assertThat(service.unmute(a, b)).isTrue();

        verify(repo, never()).existsById(anyString());
        verify(events, times(1)).publishEvent(any(FollowGraphChanged.class));
    }

    @Test
    void muteIsIdempotentAndSignalsOnlyOnChange() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        when(repo.existsById(anyString())).thenReturn(true);
        when(repo.mute(eq(a.toString()), eq(b.toString()), anyString(), anyLong()))
                .thenReturn(Optional.of(1L), Optional.empty());

        assertThat(service.mute(a, b)).isTrue();
        assertThat(service.mute(a, b)).isFalse();
        verify(events, times(1)).publishEvent(any(FollowGraphChanged.class));
    }

    @Test
    void followingAcrossABlockIsForbiddenInBothDirections_alreadyFollowingIsStillANoOp() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        when(repo.existsById(anyString())).thenReturn(true);
        when(repo.follow(anyString(), anyString(), anyString(), anyLong())).thenReturn(Optional.empty());
        when(repo.isBlockedEitherWay(a.toString(), b.toString())).thenReturn(true);
        when(repo.isBlockedEitherWay(b.toString(), a.toString())).thenReturn(true);

        assertThatThrownBy(() -> service.follow(new FollowUserCommand(a, b)))
                .isInstanceOf(ForbiddenOperationException.class).hasMessage("You cannot follow this user");
        assertThatThrownBy(() -> service.follow(new FollowUserCommand(b, a)))
                .isInstanceOf(ForbiddenOperationException.class).hasMessage("You cannot follow this user");

        var c = UUID.randomUUID();
        assertThat(service.follow(new FollowUserCommand(a, c))).isFalse(); // no block: plain no-op
        verifyNoInteractions(events);
    }

    @Test
    void relationshipHasNoBlockedByFlag_andIsAllFalseForSelf() {
        var me = UUID.randomUUID();
        var other = UUID.randomUUID();
        when(repo.isFollowing(me.toString(), other.toString())).thenReturn(true);
        when(repo.isFollowing(other.toString(), me.toString())).thenReturn(false);
        when(repo.isBlocking(me.toString(), other.toString())).thenReturn(false);
        when(repo.isMuting(me.toString(), other.toString())).thenReturn(true);

        assertThat(service.getRelationship(me, other))
                .isEqualTo(new SocialGraphService.Relationship(true, false, false, true));
        assertThat(service.getRelationship(me, me))
                .isEqualTo(new SocialGraphService.Relationship(false, false, false, false));
        verify(repo, never()).isBlocking(other.toString(), me.toString()); // "blocked by" is never asked
        assertThat(SocialGraphService.Relationship.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("following", "followedBy", "blocked", "muted");
    }
}
