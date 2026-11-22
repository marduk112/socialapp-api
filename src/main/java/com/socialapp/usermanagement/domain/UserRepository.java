package com.socialapp.usermanagement.domain;

import java.util.List;
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

    /**
     * Users whose username contains {@code term} (case-insensitive, literal: LIKE wildcards in {@code term} are
     * not special), best first: exact match, then prefix, then the rest by username; at most {@code limit}. Users
     * {@code viewerId} blocks or is blocked by are left out (stage 5A).
     */
    List<User> searchByUsername(String term, UUID viewerId, int limit);
}
