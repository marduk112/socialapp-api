package com.socialapp.usermanagement.infrastructure.web;

import com.socialapp.usermanagement.application.command.CreateUserCommand;
import com.socialapp.usermanagement.application.command.UpdateUserCommand;
import com.socialapp.usermanagement.application.usecase.UserService;
import com.socialapp.usermanagement.domain.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        var command = new CreateUserCommand(request.username(), request.email(), request.password(), request.avatarUrl());
        var user = userService.handle(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    @PostMapping("/login")
    public ResponseEntity<UserResponse> login(@RequestBody LoginRequest request) {
        var user = userService.login(request.identifier(), request.password());
        return ResponseEntity.ok(UserResponse.from(user));
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserResponse> updateUser(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest request) {
        var command = new UpdateUserCommand(id, request.username(), request.bio(), request.avatarUrl());
        var user = userService.handle(command);
        return ResponseEntity.ok(UserResponse.from(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUser(@PathVariable UUID id) {
        var user = userService.findById(id);
        return ResponseEntity.ok(UserResponse.from(user));
    }

    @GetMapping(params = "username")
    public ResponseEntity<UserResponse> getUserByUsername(@RequestParam String username) {
        var user = userService.findByUsername(username);
        return ResponseEntity.ok(UserResponse.from(user));
    }

    // ── Inner records (DTOs stay close to controller) ──────────────────────
    record CreateUserRequest(String username, String email, String password, String avatarUrl) {}
    record UpdateUserRequest(String username, String bio, String avatarUrl) {}
    record LoginRequest(String identifier, String password) {}

    record UserResponse(UUID id, String username, String email, String bio, String avatarUrl) {
        static UserResponse from(User u) {
            return new UserResponse(u.getId(), u.getUsername(), u.getEmail(), u.getBio(), u.getAvatarUrl());
        }
    }
}
