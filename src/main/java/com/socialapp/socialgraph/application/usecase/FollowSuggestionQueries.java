package com.socialapp.socialgraph.application.usecase;

import java.util.Collection;
import java.util.List;

/**
 * Read side of "who to follow"; every candidate excludes the viewer, everyone the viewer already follows, blocks or mutes,
 * and everyone who blocks the viewer.
 */
public interface FollowSuggestionQueries {

    /** Friends of friends, most mutual followees first (ties: username, id). */
    List<FollowSuggestion> friendsOfFriends(String viewerId, int limit);

    /** Most followed users, skipping {@code excludeIds} (ties: username, id); {@code mutualFollowers} is 0. */
    List<FollowSuggestion> mostFollowed(String viewerId, Collection<String> excludeIds, int limit);
}
