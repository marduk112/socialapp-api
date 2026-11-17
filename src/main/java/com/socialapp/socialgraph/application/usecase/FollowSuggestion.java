package com.socialapp.socialgraph.application.usecase;

/**
 * A "who to follow" candidate.
 *
 * @param mutualFollowers how many of the viewer's followees follow the candidate; 0 for a popularity-based
 *                        fallback suggestion
 */
public record FollowSuggestion(String userId, String username, String avatarUrl, long mutualFollowers) {}
