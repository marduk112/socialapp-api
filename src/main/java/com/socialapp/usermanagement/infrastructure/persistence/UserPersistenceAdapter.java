package com.socialapp.usermanagement.infrastructure.persistence;

import com.socialapp.usermanagement.domain.User;
import com.socialapp.usermanagement.domain.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter (outbound port implementation) bridging domain repository to Spring Data JPA.
 */
@Component
@RequiredArgsConstructor
public class UserPersistenceAdapter implements UserRepository {

    private final UserJpaRepository jpaRepository;

    @Override
    public User save(User user) {
        return jpaRepository.save(user);
    }

    @Override
    public Optional<User> findById(UUID id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return jpaRepository.findByUsername(username);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jpaRepository.findByEmail(User.normalizeEmail(email));
    }

    @Override
    public boolean existsByEmail(String email) {
        return jpaRepository.existsByEmail(User.normalizeEmail(email));
    }

    @Override
    public boolean existsByUsername(String username) {
        return jpaRepository.existsByUsername(username);
    }

    @Override
    public List<User> searchByUsername(String term, int limit) {
        String exact = term.toLowerCase(Locale.ROOT);
        String escaped = exact.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return jpaRepository.search(exact, escaped + "%", "%" + escaped + "%", limit);
    }
}
