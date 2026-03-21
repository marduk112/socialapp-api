package com.socialapp.usermanagement.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UpdateUserCommand(
        UUID userId,
        @NotBlank @Size(min = 3, max = 50) String username,
        @Size(max = 500) String bio,
        String avatarUrl
) {
}
