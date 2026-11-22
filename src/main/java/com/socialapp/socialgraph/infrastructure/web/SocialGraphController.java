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

    // ── Blocks and mutes (stage 5A) ───────────────────────────────────────────

    /** Blocks {@code userId} (mutual in effect, removes follows both ways). 204, idempotent; self -> 400; unknown -> 404. */
    @PutMapping("/block/{userId}")
    public ResponseEntity<Void> block(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID userId) {
        socialGraphService.block(me.id(), userId);
        return ResponseEntity.noContent().build();
    }

    /** Lifts the caller's block of {@code userId}. Always 204 (follows are not restored). */
    @DeleteMapping("/block/{userId}")
    public ResponseEntity<Void> unblock(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID userId) {
        socialGraphService.unblock(me.id(), userId);
        return ResponseEntity.noContent().build();
    }

    /** The caller's blocked users, by username. */
    @GetMapping("/blocks")
    public ResponseEntity<List<UserNodeResponse>> getBlocks(@AuthenticationPrincipal AuthenticatedUser me) {
        return ResponseEntity.ok(socialGraphService.getBlocked(me.id()).stream().map(UserNodeResponse::from).toList());
    }

    /** Mutes {@code userId} (one-directional, silent). 204, idempotent; self -> 400; unknown -> 404. */
    @PutMapping("/mute/{userId}")
    public ResponseEntity<Void> mute(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID userId) {
        socialGraphService.mute(me.id(), userId);
        return ResponseEntity.noContent().build();
    }

    /** Unmutes {@code userId}. Always 204. */
    @DeleteMapping("/mute/{userId}")
    public ResponseEntity<Void> unmute(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID userId) {
        socialGraphService.unmute(me.id(), userId);
        return ResponseEntity.noContent().build();
    }

    /** The caller's muted users, by username. */
    @GetMapping("/mutes")
    public ResponseEntity<List<UserNodeResponse>> getMutes(@AuthenticationPrincipal AuthenticatedUser me) {
        return ResponseEntity.ok(socialGraphService.getMuted(me.id()).stream().map(UserNodeResponse::from).toList());
    }

    /**
     * The caller's relationship to {@code userId}: {@code {userId, following, followedBy, blocked, muted}} (blocked /
     * muted = BY the caller). No "blocked by them" flag on purpose. Always 200 (unknown user or self: all false).
     */
    @GetMapping("/relationship/{userId}")
    public ResponseEntity<RelationshipResponse> getRelationship(@AuthenticationPrincipal AuthenticatedUser me,
                                                                @PathVariable UUID userId) {
        var r = socialGraphService.getRelationship(me.id(), userId);
        return ResponseEntity.ok(new RelationshipResponse(userId, r.following(), r.followedBy(), r.blocked(), r.muted()));
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

    record RelationshipResponse(UUID userId, boolean following, boolean followedBy, boolean blocked, boolean muted) {}

    record SuggestionResponse(String userId, String username, String avatarUrl, long mutualFollowers) {
        static SuggestionResponse from(FollowSuggestion s) {
            return new SuggestionResponse(s.userId(), s.username(), s.avatarUrl(), s.mutualFollowers());
        }
    }
}
