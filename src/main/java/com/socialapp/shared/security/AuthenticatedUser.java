package com.socialapp.shared.security;

import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

/**
 * Principal placed in the SecurityContext after a valid JWT is presented.
 * Shared kernel type: every BC may read the caller's identity from it without
 * importing anything from UserManagement.
 *
 * {@link #getName()} returns the user UUID, so {@code Authentication.getName()} is the user id.
 */
public record AuthenticatedUser(UUID id, String username) implements Principal {

    public AuthenticatedUser {
        Objects.requireNonNull(id, "id");
    }

    @Override
    public String getName() {
        return id.toString();
    }
}
