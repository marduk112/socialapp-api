package com.socialapp.socialgraph.application.usecase;

import com.socialapp.socialgraph.application.command.FollowUserCommand;
import com.socialapp.socialgraph.domain.UserNode;
import com.socialapp.socialgraph.domain.UserNodeRepository;
import com.socialapp.shared.events.UserCreatedEvent;
import com.socialapp.shared.events.UserUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SocialGraphService {

    private final UserNodeRepository userNodeRepository;

    @Transactional("neo4jTransactionManager")
    public void syncUserCreated(UserCreatedEvent event) {
        if (userNodeRepository.existsById(event.userId().toString())) {
            log.debug("UserNode already exists for userId={}", event.userId());
            return;
        }
        var node = new UserNode(event.userId().toString(), event.username(), event.avatarUrl());
        userNodeRepository.save(node);
        log.info("Created UserNode in Neo4j for userId={}", event.userId());
    }

    @Transactional("neo4jTransactionManager")
    public void syncUserUpdated(UserUpdatedEvent event) {
        userNodeRepository.findByUserId(event.userId().toString()).ifPresent(node -> {
            node.updateProfile(event.username(), event.avatarUrl());
            userNodeRepository.save(node);
            log.info("Updated UserNode in Neo4j for userId={}", event.userId());
        });
    }

    @Transactional("neo4jTransactionManager")
    public void follow(FollowUserCommand command) {
        var follower = userNodeRepository.findByUserId(command.followerId().toString())
                .orElseThrow(() -> new IllegalArgumentException("Follower not found: " + command.followerId()));
        var followee = userNodeRepository.findByUserId(command.followeeId().toString())
                .orElseThrow(() -> new IllegalArgumentException("Followee not found: " + command.followeeId()));

        follower.follow(followee);
        userNodeRepository.save(follower);
        log.info("User {} now follows {}", command.followerId(), command.followeeId());
    }

    @Transactional("neo4jTransactionManager")
    public void unfollow(FollowUserCommand command) {
        var follower = userNodeRepository.findByUserId(command.followerId().toString())
                .orElseThrow(() -> new IllegalArgumentException("Follower not found: " + command.followerId()));
        var followee = userNodeRepository.findByUserId(command.followeeId().toString())
                .orElseThrow(() -> new IllegalArgumentException("Followee not found: " + command.followeeId()));

        follower.unfollow(followee);
        userNodeRepository.save(follower);
    }

    @Transactional(value = "neo4jTransactionManager", readOnly = true)
    public List<UserNode> getFollowing(String userId) {
        return userNodeRepository.findFollowing(userId);
    }

    @Transactional(value = "neo4jTransactionManager", readOnly = true)
    public List<UserNode> getFollowers(String userId) {
        return userNodeRepository.findFollowers(userId);
    }

    @Transactional(value = "neo4jTransactionManager", readOnly = true)
    public boolean isFollowing(String followerId, String followeeId) {
        return userNodeRepository.isFollowing(followerId, followeeId);
    }
}
