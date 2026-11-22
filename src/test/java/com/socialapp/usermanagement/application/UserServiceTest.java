package com.socialapp.usermanagement.application;

import com.socialapp.shared.domain.ConflictException;
import com.socialapp.shared.domain.NotFoundException;
import com.socialapp.usermanagement.application.command.CreateUserCommand;
import com.socialapp.usermanagement.application.usecase.UserService;
import com.socialapp.usermanagement.domain.User;
import com.socialapp.usermanagement.domain.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Pure unit tests for UserService (no Spring context, no infrastructure). */
class UserServiceTest {

    private static final UUID VIEWER = UUID.randomUUID();
    private final UserRepository repo = mock(UserRepository.class);
    private final UserService service = new UserService(repo);

    @Test
    void registerNormalisesEmailAndChecksUniquenessOnNormalisedValue() {
        when(repo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        var user = service.handle(new CreateUserCommand("alice", "  Alice@Test.COM ", "secret123", null));

        assertThat(user.getEmail()).isEqualTo("alice@test.com");
        verify(repo).existsByEmail("alice@test.com");
    }

    @Test
    void registerWithExistingEmailThrowsConflict() {
        when(repo.existsByEmail("alice@test.com")).thenReturn(true);

        assertThatThrownBy(() -> service.handle(new CreateUserCommand("alice2", "ALICE@test.com", "secret123", null)))
                .isInstanceOf(ConflictException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void registerWithTakenUsernameThrowsConflict() {
        when(repo.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> service.handle(new CreateUserCommand("alice", "new@test.com", "secret123", null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void loginWithMixedCaseEmailLooksUpNormalisedEmail() {
        var hash = new BCryptPasswordEncoder().encode("secret123");
        var alice = User.register("alice", "alice@test.com", hash, null);
        when(repo.findByUsername("Alice@Test.com")).thenReturn(Optional.empty());
        when(repo.findByEmail("alice@test.com")).thenReturn(Optional.of(alice));

        assertThat(service.login("Alice@Test.com", "secret123")).isSameAs(alice);
    }

    @Test
    void loginWithUnknownUserStillThrowsSecurityException() {
        when(repo.findByUsername(any())).thenReturn(Optional.empty());
        when(repo.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("ghost", "whatever"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void findByIdOfMissingUserThrowsNotFound() {
        var id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void domainRejectsBlankUsernameAndEmail() {
        assertThatThrownBy(() -> User.register(" ", "a@test.com", "h", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> User.register("alice", null, "h", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void searchTrimsTheTermAndClampsTheLimit() {
        when(repo.searchByUsername(anyString(), any(), anyInt())).thenReturn(java.util.List.of());

        service.searchByUsername("  Ali  ", VIEWER, 1000);
        service.searchByUsername("ali", VIEWER, 0);

        verify(repo).searchByUsername("Ali", VIEWER, UserService.MAX_SEARCH_LIMIT);
        verify(repo).searchByUsername("ali", VIEWER, 1);
    }

    @Test
    void searchRejectsBlankAndOverlongTermsWithoutTouchingTheRepository() {
        assertThatThrownBy(() -> service.searchByUsername("  ", VIEWER, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.searchByUsername(null, VIEWER, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.searchByUsername("x".repeat(UserService.MAX_SEARCH_TERM_LENGTH + 1), VIEWER, 10))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repo, never()).searchByUsername(anyString(), any(), anyInt());
    }
}
