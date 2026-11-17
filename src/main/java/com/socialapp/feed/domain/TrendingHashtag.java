package com.socialapp.feed.domain;

/**
 * A hashtag used recently.
 *
 * @param tag     normalised (lower-case, no '#')
 * @param posts   posts (own text: POST or QUOTE) carrying it in the window
 * @param authors distinct authors of those posts
 */
public record TrendingHashtag(String tag, long posts, long authors) {}
