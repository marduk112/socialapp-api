package com.socialapp.feed.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuthorProfileRepository extends JpaRepository<AuthorProfile, UUID> {}
