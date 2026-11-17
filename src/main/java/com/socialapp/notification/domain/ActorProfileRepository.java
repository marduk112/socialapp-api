package com.socialapp.notification.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ActorProfileRepository extends JpaRepository<ActorProfile, UUID> {
}
