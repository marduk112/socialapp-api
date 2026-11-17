package com.socialapp.usermanagement.infrastructure.web;

import com.socialapp.usermanagement.application.command.CreateUserCommand;
import com.socialapp.usermanagement.application.command.UpdateUserCommand;
import com.socialapp.usermanagement.application.usecase.UserService;
import com.socialapp.shared.domain.ForbiddenOperationException;
import com.socialapp.shared.security.AuthenticatedUser;
import com.socialapp.usermanagement.domain.User;
import com.socialapp.usermanagement.infrastructure.security.JwtService;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final JwtService jwtService;

    @PostMapping
    public ResponseEntity<AuthResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        var command = new CreateUserCommand(request.username(), request.email(), request.password(), request.avatarUrl());
        var user = userService.handle(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(withToken(user));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
        var user = userService.login(request.identifier(), request.password());
        return ResponseEntity.ok(withToken(user));
    }

    /**
     * Only the account owner may update the profile. The response carries a fresh token
     * because the username (a token claim) may have changed.
     */
    @PutMapping("/{id}")
    public ResponseEntity<AuthResponse> updateUser(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser me,
            @Valid @RequestBody UpdateUserRequest request) {
        if (!me.id().equals(id)) {
            throw new ForbiddenOperationException("You can only update your own profile");
        }
        var command = new UpdateUserCommand(id, request.username(), request.bio(), request.avatarUrl());
        var user = userService.handle(command);
        return ResponseEntity.ok(withToken(user));
    }

    /** Public profile. The e-mail address is private: it is only included when the caller asks for their own account. */
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUser(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser me) {
        var user = userService.findById(id);
        return ResponseEntity.ok(UserResponse.from(user, me));
    }

    @GetMapping(params = "username")
    public ResponseEntity<UserResponse> getUserByUsername(@RequestParam String username,
                                                          @AuthenticationPrincipal AuthenticatedUser me) {
        var user = userService.findByUsername(username);
        return ResponseEntity.ok(UserResponse.from(user, me));
    }

    // ── Inner records (DTOs stay close to controller) ──────────────────────
    record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 30) String username,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            String avatarUrl) {}

    /** Username is required (the profile always carries one); bio is optional but capped. */
    record UpdateUserRequest(
            @NotBlank @Size(min = 3, max = 30) String username,
            @Size(max = 500) String bio,
            String avatarUrl) {}
    record LoginRequest(String identifier, String password) {}

    private AuthResponse withToken(User user) {
        return AuthResponse.from(user, jwtService.issueToken(user.getId(), user.getUsername()));
    }

    /** {@code email} is omitted (not null) for everybody but the account owner. */
    record UserResponse(UUID id, String username, @JsonInclude(JsonInclude.Include.NON_NULL) String email, String bio,
                        String avatarUrl) {
        static UserResponse from(User u, AuthenticatedUser viewer) {
            boolean self = viewer != null && viewer.id().equals(u.getId());
            return new UserResponse(u.getId(), u.getUsername(), self ? u.getEmail() : null, u.getBio(), u.getAvatarUrl());
        }
    }

    /** UserResponse fields plus the JWT access token (flat, so existing clients keep working). */
    record AuthResponse(UUID id, String username, String email, String bio, String avatarUrl, String token) {
        static AuthResponse from(User u, String token) {
            return new AuthResponse(u.getId(), u.getUsername(), u.getEmail(), u.getBio(), u.getAvatarUrl(), token);
        }
    }
}
