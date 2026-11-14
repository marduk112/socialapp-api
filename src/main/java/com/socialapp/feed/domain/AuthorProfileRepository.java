package com.socialapp.feed.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AuthorProfileRepository extends JpaRepository<AuthorProfile, UUID> {

    /**
     * Profiles whose username lower-cased by the database is one of {@code lowerUsernames} (uses the
     * {@code lower(username)} index from V17). Callers re-check the match in Java
     * ({@link MentionParser#key}), which is authoritative.
     */
    @Query("select a from AuthorProfile a where lower(a.username) in :lowerUsernames")
    List<AuthorProfile> findByLowerUsernameIn(@Param("lowerUsernames") Collection<String> lowerUsernames);
}
