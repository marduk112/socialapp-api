package com.socialapp.feed.application.usecase;

import com.socialapp.feed.domain.AuthorProfile;
import com.socialapp.feed.domain.AuthorProfileRepository;
import com.socialapp.feed.domain.CommentRepository;
import com.socialapp.feed.domain.PostRepository;
import com.socialapp.shared.events.UserCreatedEvent;
import com.socialapp.shared.events.UserUpdatedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthorProfileProjectionTest {

    private static final UUID USER = UUID.randomUUID();
    private static final Instant T1 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant T2 = T1.plusSeconds(60);

    private final AuthorProfileRepository profiles = mock(AuthorProfileRepository.class);
    private final PostRepository posts = mock(PostRepository.class);
    private final CommentRepository comments = mock(CommentRepository.class);
    private final AuthorProfileProjection projection = new AuthorProfileProjection(profiles, posts, comments);

    private static UserCreatedEvent created(Instant at, String username, String avatar) {
        return new UserCreatedEvent(UUID.randomUUID(), at, USER, username, username + "@x.io", avatar);
    }

    private static UserUpdatedEvent updated(Instant at, String username, String avatar) {
        return new UserUpdatedEvent(UUID.randomUUID(), at, USER, username, avatar, null);
    }

    @Test
    void createCreatesMissingProfile() {
        when(profiles.findById(USER)).thenReturn(Optional.empty());

        projection.on(created(T1, "alice", "a1.png"));

        var saved = ArgumentCaptor.forClass(AuthorProfile.class);
        verify(profiles).save(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("alice");
        assertThat(saved.getValue().getAvatarUrl()).isEqualTo("a1.png");
        assertThat(saved.getValue().getLastEventAt()).isEqualTo(T1);
    }

    @Test
    void lateCreateDoesNotOverwriteNewerUpdate() {
        var profile = new AuthorProfile(USER, "alice2", "a2.png", T2);
        when(profiles.findById(USER)).thenReturn(Optional.of(profile));

        projection.on(created(T1, "alice", "a1.png"));

        assertThat(profile.getUsername()).isEqualTo("alice2");
        assertThat(profile.getAvatarUrl()).isEqualTo("a2.png");
        verify(profiles, never()).save(any());
    }

    @Test
    void updateBeforeCreateUpserts() {
        when(profiles.findById(USER)).thenReturn(Optional.empty());

        projection.on(updated(T2, "alice2", "a2.png"));

        var saved = ArgumentCaptor.forClass(AuthorProfile.class);
        verify(profiles).save(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("alice2");
        verify(posts).renameAuthor(USER, "alice2");
        verify(comments).renameAuthor(USER, "alice2");
    }

    @Test
    void staleOrRedeliveredUpdateIsIgnored() {
        var profile = new AuthorProfile(USER, "alice2", "a2.png", T2);
        when(profiles.findById(USER)).thenReturn(Optional.of(profile));

        projection.on(updated(T1, "alice-old", "old.png"));
        projection.on(updated(T2, "alice2-dup", "dup.png")); // same timestamp = redelivery

        assertThat(profile.getUsername()).isEqualTo("alice2");
        assertThat(profile.getAvatarUrl()).isEqualTo("a2.png");
        verifyNoInteractions(posts, comments);
    }

    @Test
    void newerUpdateAppliesAndRefreshesDenormalisedUsernames() {
        var profile = new AuthorProfile(USER, "alice", "a1.png", T1);
        when(profiles.findById(USER)).thenReturn(Optional.of(profile));

        projection.on(updated(T2, "alice2", "a2.png"));

        assertThat(profile.getUsername()).isEqualTo("alice2");
        assertThat(profile.getAvatarUrl()).isEqualTo("a2.png");
        assertThat(profile.getLastEventAt()).isEqualTo(T2);
        verify(posts).renameAuthor(USER, "alice2");
        verify(posts).renameOriginalAuthor(USER, "alice2");
        verify(comments).renameAuthor(USER, "alice2");
    }

    @Test
    void legacyRowWithoutTimestampAcceptsUpdate() {
        var profile = new AuthorProfile(USER, null, "old.png", null);
        when(profiles.findById(USER)).thenReturn(Optional.of(profile));

        projection.on(updated(T1, "alice", "new.png"));

        assertThat(profile.getAvatarUrl()).isEqualTo("new.png");
        assertThat(profile.getUsername()).isEqualTo("alice");
    }
}
