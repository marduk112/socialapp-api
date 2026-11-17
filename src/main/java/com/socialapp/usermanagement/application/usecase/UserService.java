package com.socialapp.usermanagement.application.usecase;

import com.socialapp.usermanagement.application.command.CreateUserCommand;
import com.socialapp.usermanagement.application.command.UpdateUserCommand;
import com.socialapp.usermanagement.domain.User;
import com.socialapp.usermanagement.domain.UserRepository;
import com.socialapp.shared.domain.ConflictException;
import com.socialapp.shared.domain.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Application Service implementing both use cases.
 * Transactional: Spring Data publishes the aggregate's events on save() within the transaction and
 * they are written to the outbox atomically with the change (see com.socialapp.config.outbox).
 */
@Service
@RequiredArgsConstructor
public class UserService implements CreateUserUseCase, UpdateUserUseCase {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    @Transactional
    public User handle(CreateUserCommand command) {
        var email = User.normalizeEmail(command.email());
        if (email != null && userRepository.existsByEmail(email)) {
            throw new ConflictException("Email already registered: " + email);
        }
        if (userRepository.existsByUsername(command.username())) {
            throw new ConflictException("Username already taken: " + command.username());
        }
        var passwordHash = passwordEncoder.encode(command.password());
        var user = User.register(command.username(), email, passwordHash, command.avatarUrl());
        return userRepository.save(user);
    }

    @Override
    @Transactional
    public User handle(UpdateUserCommand command) {
        var user = userRepository.findById(command.userId())
                .orElseThrow(() -> new NotFoundException("User not found: " + command.userId()));
        if (command.username() != null && !command.username().equals(user.getUsername())
                && userRepository.existsByUsername(command.username())) {
            throw new ConflictException("Username already taken: " + command.username());
        }
        user.updateProfile(command.username(), command.bio(), command.avatarUrl());
        return userRepository.save(user);
    }

    @Transactional
    public User updateAvatar(UUID userId, String avatarUrl) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        user.updateAvatarUrl(avatarUrl);
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public User findById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));
    }

    @Transactional(readOnly = true)
    public User findByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("User not found: " + username));
    }

    @Transactional(readOnly = true)
    public User login(String identifier, String password) {
        var user = userRepository.findByUsername(identifier)
                .or(() -> userRepository.findByEmail(User.normalizeEmail(identifier)))
                .orElseThrow(() -> new SecurityException("Invalid credentials"));
        var hash = user.getPasswordHash();
        if (hash == null || hash.isBlank() || !passwordEncoder.matches(password, hash)) {
            throw new SecurityException("Invalid credentials");
        }
        return user;
    }

    public static final int DEFAULT_SEARCH_LIMIT = 10;
    public static final int MAX_SEARCH_LIMIT = 20;
    public static final int MAX_SEARCH_TERM_LENGTH = 50; // = username column

    /**
     * People search by username (case-insensitive substring; exact match first, then prefix).
     *
     * @param term trimmed, 1..{@value #MAX_SEARCH_TERM_LENGTH} code points
     * @param limit clamped to 1..{@value #MAX_SEARCH_LIMIT}
     * @throws IllegalArgumentException (400) for a blank or too long term
     */
    @Transactional(readOnly = true)
    public List<User> searchByUsername(String term, int limit) {
        String t = term == null ? "" : term.trim();
        int length = t.codePointCount(0, t.length());
        if (length < 1 || length > MAX_SEARCH_TERM_LENGTH) {
            throw new IllegalArgumentException("q must be 1 to " + MAX_SEARCH_TERM_LENGTH + " characters");
        }
        return userRepository.searchByUsername(t, Math.max(1, Math.min(limit, MAX_SEARCH_LIMIT)));
    }
}
