package com.socialapp.socialgraph.infrastructure.web;

import com.socialapp.socialgraph.application.command.FollowUserCommand;
import com.socialapp.socialgraph.application.usecase.SocialGraphService;
import com.socialapp.socialgraph.domain.UserNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/social")
@RequiredArgsConstructor
public class SocialGraphController {

    private final SocialGraphService socialGraphService;

    @PostMapping("/follow")
    public ResponseEntity<Void> follow(@RequestBody FollowRequest request) {
        socialGraphService.follow(new FollowUserCommand(request.followerId(), request.followeeId()));
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/follow")
    public ResponseEntity<Void> unfollow(@RequestBody FollowRequest request) {
        socialGraphService.unfollow(new FollowUserCommand(request.followerId(), request.followeeId()));
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

    @GetMapping("/is-following")
    public ResponseEntity<Boolean> isFollowing(
            @RequestParam String followerId,
            @RequestParam String followeeId) {
        return ResponseEntity.ok(socialGraphService.isFollowing(followerId, followeeId));
    }

    record FollowRequest(UUID followerId, UUID followeeId) {}

    record UserNodeResponse(String userId, String username, String avatarUrl) {
        static UserNodeResponse from(UserNode node) {
            return new UserNodeResponse(node.getUserId(), node.getUsername(), node.getAvatarUrl());
        }
    }
}
