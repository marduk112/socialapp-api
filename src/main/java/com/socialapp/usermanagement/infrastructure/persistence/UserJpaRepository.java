package com.socialapp.usermanagement.infrastructure.persistence;

import com.socialapp.usermanagement.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
interface UserJpaRepository extends JpaRepository<User, UUID> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByUsername(String username);

    /** {@code exact}/{@code prefix}/{@code contains}: lower-cased term, LIKE-escaped for the last two. Uses V24. */
    @Query(value = """
            SELECT u.* FROM users u
            WHERE lower(u.username) LIKE :contains
            ORDER BY CASE WHEN lower(u.username) = :exact THEN 0
                          WHEN lower(u.username) LIKE :prefix THEN 1 ELSE 2 END,
                     u.username, u.id
            LIMIT :limit""", nativeQuery = true)
    List<User> search(@Param("exact") String exact, @Param("prefix") String prefix,
                      @Param("contains") String contains, @Param("limit") int limit);
}
