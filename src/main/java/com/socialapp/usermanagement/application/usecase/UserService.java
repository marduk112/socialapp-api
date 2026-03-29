package com.socialapp.usermanagement.application.usecase;

import com.socialapp.usermanagement.application.command.CreateUserCommand;
import com.socialapp.usermanagement.application.command.UpdateUserCommand;
import com.socialapp.usermanagement.domain.User;
import com.socialapp.usermanagement.domain.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Application Service implementing both use cases.
 * Transactional: Spring Data's @DomainEvents fires after save() within the transaction;
 * the KafkaEventPublisher listens with @TransactionalEventListener(AFTER_COMMIT).
 */
@Service
@RequiredArgsConstructor
public class UserService implements CreateUserUseCase, UpdateUserUseCase {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    @Transactional
    public User handle(CreateUserCommand command) {
        if (userRepository.existsByEmail(command.email())) {
            throw new IllegalArgumentException("Email already registered: " + command.email());
        }
        if (userRepository.existsByUsername(command.username())) {
            throw new IllegalArgumentException("Username already taken: " + command.username());
        }
        var passwordHash = passwordEncoder.encode(command.password());
        var user = User.register(command.username(), command.email(), passwordHash, command.avatarUrl());
        return userRepository.save(user);
    }

    @Override
    @Transactional
    public User handle(UpdateUserCommand command) {
        var user = userRepository.findById(command.userId())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + command.userId()));
        user.updateProfile(command.username(), command.bio(), command.avatarUrl());
        return userRepository.save(user);
    }

    @Transactional
    public User updateAvatar(UUID userId, String avatarUrl) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        user.updateAvatarUrl(avatarUrl);
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public User findById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
    }

    @Transactional(readOnly = true)
    public User findByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
    }

    @Transactional(readOnly = true)
    public User login(String identifier, String password) {
        var user = userRepository.findByUsername(identifier)
                .or(() -> userRepository.findByEmail(identifier))
                .orElseThrow(() -> new SecurityException("Invalid credentials"));
        var hash = user.getPasswordHash();
        if (hash == null || hash.isBlank() || !passwordEncoder.matches(password, hash)) {
            throw new SecurityException("Invalid credentials");
        }
        return user;
    }
}
