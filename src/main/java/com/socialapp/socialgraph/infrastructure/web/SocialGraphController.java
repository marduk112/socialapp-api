package com.socialapp.socialgraph.infrastructure.web;

import com.socialapp.socialgraph.application.command.FollowUserCommand;
import com.socialapp.socialgraph.application.usecase.FollowSuggestion;
import com.socialapp.socialgraph.application.usecase.SocialGraphService;
import com.socialapp.socialgraph.domain.UserNode;
import com.socialapp.shared.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/social")
@RequiredArgsConstructor
public class SocialGraphController {

    private final SocialGraphService socialGraphService;

    @PostMapping("/follow")
    public ResponseEntity<Void> follow(
            @AuthenticationPrincipal AuthenticatedUser me,
            @RequestBody FollowRequest request) {
        socialGraphService.follow(new FollowUserCommand(me.id(), requireFollowee(request)));
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/follow")
    public ResponseEntity<Void> unfollow(
            @AuthenticationPrincipal AuthenticatedUser me,
            @RequestBody FollowRequest request) {
        socialGraphService.unfollow(new FollowUserCommand(me.id(), requireFollowee(request)));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{userId}/following")
    public ResponseEntity<List<UserNodeResponse>> getFollowing(@PathVariable String userId) {
        return ResponseEntity.ok(
                socialGraphService.getFollowing(userId).stream()
                        .map(UserNodeResponse::from).toList()
        );
    }

    @GetMapping("/{userId}/followers")
    public ResponseEntity<List<UserNodeResponse>> getFollowers(@PathVariable String userId) {
        return ResponseEntity.ok(
                socialGraphService.getFollowers(userId).stream()
                        .map(UserNodeResponse::from).toList()
        );
    }

    /** "Who to follow" for the caller: friends of friends, topped up with the most followed users. */
    @GetMapping("/suggestions")
    public ResponseEntity<List<SuggestionResponse>> getSuggestions(
            @AuthenticationPrincipal AuthenticatedUser me,
            @RequestParam(defaultValue = "" + SocialGraphService.DEFAULT_SUGGESTIONS) int limit) {
        return ResponseEntity.ok(socialGraphService.getSuggestions(me.id(), limit).stream()
                .map(SuggestionResponse::from).toList());
    }

    /** Read-only query; {@code followerId} defaults to the caller when omitted. */
    @GetMapping("/is-following")
    public ResponseEntity<Boolean> isFollowing(
            @AuthenticationPrincipal AuthenticatedUser me,
            @RequestParam(required = false) String followerId,
            @RequestParam String followeeId) {
        String follower = followerId == null || followerId.isBlank() ? me.getName() : followerId;
        return ResponseEntity.ok(socialGraphService.isFollowing(follower, followeeId));
    }

    private static UUID requireFollowee(FollowRequest request) {
        if (request == null || request.followeeId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "followeeId is required");
        }
        return request.followeeId();
    }

    /** {@code followerId} is ignored if sent: the follower is always the authenticated caller. */
    record FollowRequest(UUID followeeId) {}

    record UserNodeResponse(String userId, String username, String avatarUrl) {
        static UserNodeResponse from(UserNode node) {
            return new UserNodeResponse(node.getUserId(), node.getUsername(), node.getAvatarUrl());
        }
    }

    record SuggestionResponse(String userId, String username, String avatarUrl, long mutualFollowers) {
        static SuggestionResponse from(FollowSuggestion s) {
            return new SuggestionResponse(s.userId(), s.username(), s.avatarUrl(), s.mutualFollowers());
        }
    }
}
