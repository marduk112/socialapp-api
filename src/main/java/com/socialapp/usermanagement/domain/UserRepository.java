package com.socialapp.usermanagement.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Port (outbound) — implemented by the infrastructure adapter.
 * This keeps the domain layer free of Spring Data dependencies.
 */
public interface UserRepository {
    User save(User user);
    Optional<User> findById(UUID id);
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByUsername(String username);
}
