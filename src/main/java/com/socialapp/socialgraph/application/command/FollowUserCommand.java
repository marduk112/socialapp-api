package com.socialapp.socialgraph.application.command;

import java.util.UUID;

public record FollowUserCommand(UUID followerId, UUID followeeId) {}
