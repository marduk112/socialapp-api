package com.socialapp.usermanagement.infrastructure.web;

import com.socialapp.usermanagement.application.usecase.UserService;
import com.socialapp.usermanagement.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class UserSearchController {

    private final UserService userService;

    /**
     * People search by username (case-insensitive, anywhere in the name; exact match first, then prefix).
     * {@code q}: 1..50 characters, blank or too long -> 400; {@code limit}: default 10, clamped to 1..20.
     * Public profile data only (no e-mail).
     */
    @GetMapping("/users")
    public ResponseEntity<List<UserSearchResult>> searchUsers(
            @RequestParam String q,
            @RequestParam(defaultValue = "" + UserService.DEFAULT_SEARCH_LIMIT) int limit) {
        return ResponseEntity.ok(userService.searchByUsername(q, limit).stream()
                .map(UserSearchResult::from).toList());
    }

    record UserSearchResult(UUID id, String username, String bio, String avatarUrl) {
        static UserSearchResult from(User u) {
            return new UserSearchResult(u.getId(), u.getUsername(), u.getBio(), u.getAvatarUrl());
        }
    }
}
